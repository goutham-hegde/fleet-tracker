package com.fleettracking.simulator.emit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fleettracking.events.EventJson;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.simulator.Simulation;
import com.fleettracking.simulator.TickObserver;
import com.fleettracking.simulator.fault.FaultProfile;
import com.fleettracking.simulator.fleet.TruckPhase;
import com.fleettracking.simulator.fleet.TruckTransition;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * The driver's phone: terse, differently-shaped, and unreliable on purpose.
 *
 * <p>Where telematics is a well-behaved feed in an inconvenient shape, this one is a badly-behaved
 * feed, and that is its entire reason for existing. Three properties matter downstream:
 *
 * <ul>
 *   <li><b>It disappears.</b> A truck between Phoenix and Denver spends real stretches with no
 *       signal. The app keeps recording and sends nothing.
 *   <li><b>It comes back all at once.</b> On reconnect the whole backlog goes out in a burst,
 *       <em>out of order</em>, because the queue is drained concurrently rather than in sequence.
 *       Every message in that burst describes a moment well in the past, so a consumer that treats
 *       arrival order as chronological order will conclude the truck teleported backwards.
 *   <li><b>It repeats itself.</b> A message sent as the connection died, whose acknowledgement was
 *       lost, is sent again on reconnect. Duplicates are not a rare fault here — they are the
 *       normal consequence of at-least-once delivery over a flaky link, which is why {@code seq}
 *       exists and why M2 must dedupe on it rather than trusting the feed.
 * </ul>
 *
 * <h2>A different shape as well as a different reliability</h2>
 *
 * <p>The payload is abbreviated ({@code sid}, {@code ts}, {@code lat}, {@code lng}) because it was
 * designed to be cheap over a mobile connection, and it differs from telematics in three ways that
 * each cost a normalizer a decision:
 *
 * <ul>
 *   <li>time as <b>epoch milliseconds</b>, not an ISO-8601 string;
 *   <li>speed in <b>metres per second</b>, which is what a phone's location API reports — not km/h
 *       and not mph, so a normalizer that handles telematics correctly is still wrong here;
 *   <li>a <b>shipment id and no vehicle id</b> — the exact inverse of telematics, because a driver
 *       signs in against a load rather than a tractor.
 * </ul>
 *
 * <p>It does report {@code acc}, a real accuracy radius in metres, which telematics does not. That
 * number is load-bearing for M3: a 500-metre fix must not be allowed to trigger an arrival at a
 * stop 200 metres away.
 */
public class MobileAppEmitter implements TickObserver {

  private final EmissionProperties.Mobile config;
  private final MessageSink sink;
  private final Cadence cadence;
  private final RandomGenerator random;
  private final FaultProfile faults;
  private final Map<String, Phone> phones = new HashMap<>();

  public MobileAppEmitter(
      EmissionProperties.Mobile config,
      MessageSink sink,
      RandomGenerator random,
      FaultProfile faults) {
    this.config = config;
    this.sink = sink;
    this.random = random;
    this.faults = faults;
    this.cadence = new Cadence(config.interval(), random);
  }

  @Override
  public void onTick(Simulation.TickReport report) {
    Instant now = report.at();

    // Driver-tapped status events first: they are the ones that matter, and they are also the ones
    // most likely to be stuck in a backlog when the truck is somewhere with no signal.
    Map<String, VehicleSnapshot> byShipment =
        report.snapshots().stream()
            .collect(Collectors.toMap(VehicleSnapshot::shipmentId, s -> s, (a, b) -> a));

    for (TruckTransition transition : report.transitions()) {
      VehicleSnapshot snapshot = byShipment.get(transition.shipmentId());
      if (snapshot == null) {
        continue;
      }
      String event =
          switch (transition) {
            case TruckTransition.Arrived a -> "arrive";
            case TruckTransition.Departed d -> "depart";
            case TruckTransition.RouteCompleted c -> "delivered";
          };
      offer(phoneFor(transition.shipmentId()), snapshot, now, event, transition.stop().id());
    }

    for (VehicleSnapshot snapshot : report.snapshots()) {
      if (snapshot.phase() == TruckPhase.COMPLETED) {
        continue;
      }
      Phone phone = phoneFor(snapshot.shipmentId());
      reconnectIfDue(phone, snapshot.shipmentId(), now);

      if (cadence.due(snapshot.shipmentId(), now)) {
        maybeLoseSignal(phone, now);
        offer(phone, snapshot, now, "ping", null);
      }
    }

    java.util.Set<String> live =
        report.snapshots().stream().map(VehicleSnapshot::shipmentId).collect(Collectors.toSet());
    cadence.retainOnly(live);
    phones.keySet().retainAll(live);
  }

  private Phone phoneFor(String shipmentId) {
    return phones.computeIfAbsent(shipmentId, id -> new Phone());
  }

  /** Sends now if there is signal; queues it for the reconnect burst if there is not. */
  private void offer(
      Phone phone, VehicleSnapshot snapshot, Instant now, String event, String stopId) {
    String body = EventJson.mapper().writeValueAsString(payload(phone, snapshot, now, event, stopId));
    if (phone.offline()) {
      phone.backlog.add(new Pending(now, body));
      return;
    }
    sink.accept(SourceMessage.live(SourceSystem.MOBILE_APP, snapshot.shipmentId(), now, body));
  }

  /**
   * Rolls for a dead zone.
   *
   * <p>Set {@code outage-probability} to zero and this feed becomes merely terse rather than
   * unreliable — which is how the fault is switched off independently of the other three.
   */
  private void maybeLoseSignal(Phone phone, Instant now) {
    if (phone.offline() || config.outageProbability() <= 0) {
      return;
    }
    if (random.nextDouble() < config.outageProbability()) {
      phone.offlineUntil = now.plus(config.outageDuration());
    }
  }

  /**
   * Drains the backlog when the truck finds signal again.
   *
   * <p>Shuffled, because a queue drained by several concurrent uploads does not come out in the
   * order it went in; and with the occasional repeat, because a message whose acknowledgement was
   * lost gets sent twice. Every message keeps its original {@code occurredAt} and is emitted at
   * {@code now}, so the lag is real and visible rather than being quietly rewritten to look
   * punctual.
   */
  private void reconnectIfDue(Phone phone, String shipmentId, Instant now) {
    if (phone.offlineUntil == null || now.isBefore(phone.offlineUntil)) {
      return;
    }
    phone.offlineUntil = null;
    if (phone.backlog.isEmpty()) {
      return;
    }

    List<Pending> burst = new ArrayList<>(phone.backlog);
    phone.backlog.clear();

    // A resend of whatever was in flight when the link died.
    if (!burst.isEmpty() && random.nextDouble() < 0.5) {
      burst.add(burst.get(random.nextInt(burst.size())));
    }
    java.util.Collections.shuffle(burst, java.util.Random.from(random));

    for (Pending pending : burst) {
      sink.accept(
          SourceMessage.delayed(
              SourceSystem.MOBILE_APP, shipmentId, pending.occurredAt(), now, pending.body()));
    }
  }

  private Payload payload(
      Phone phone, VehicleSnapshot s, Instant now, String event, String stopId) {
    phone.seq++;
    phone.drainBattery(random);

    com.fleettracking.events.GeoPoint reported = faults.perturb(s.position());

    return new Payload(
        s.shipmentId(),
        now.toEpochMilli(),
        Units.round(reported.latitude(), 5),
        Units.round(reported.longitude(), 5),
        // A phone's fix is far coarser than a truck-mounted unit's, and it says so honestly --
        // which is what lets a geofence refuse to trust a wide one.
        Units.round(faults.reportedAccuracyMeters(4 + random.nextDouble() * 26), 1),
        Units.round(Units.kphToMps(s.speedKph()), 1),
        (int) Math.round(s.headingDegrees()),
        phone.batteryPct,
        event,
        stopId,
        phone.seq,
        config.appVersion());
  }

  /** One app installation's state: its sequence counter, its battery, and its outbox. */
  private static final class Phone {
    long seq;
    int batteryPct = 100;
    Instant offlineUntil;
    final List<Pending> backlog = new ArrayList<>();

    boolean offline() {
      return offlineUntil != null;
    }

    /** Falls slowly, and jumps back up when the driver remembers the charger. */
    void drainBattery(RandomGenerator random) {
      if (batteryPct <= 12 && random.nextDouble() < 0.3) {
        batteryPct = 100;
        return;
      }
      if (random.nextDouble() < 0.15) {
        batteryPct = Math.max(3, batteryPct - 1);
      }
    }
  }

  /** A message the app recorded but could not send. */
  private record Pending(Instant occurredAt, String body) {}

  /**
   * The wire shape: abbreviated keys, epoch milliseconds, metres per second.
   *
   * <p>{@code stop} is null on an ordinary ping and the shared mapper omits nulls, so a ping and a
   * status event are not the same shape on the wire — a normalizer cannot assume a fixed field set.
   */
  record Payload(
      @JsonProperty("sid") String shipmentId,
      @JsonProperty("ts") long timestampMillis,
      @JsonProperty("lat") double latitude,
      @JsonProperty("lng") double longitude,
      @JsonProperty("acc") double accuracyMeters,
      @JsonProperty("spd") double speedMps,
      @JsonProperty("hdg") int headingDegrees,
      @JsonProperty("bat") int batteryPercent,
      @JsonProperty("evt") String event,
      @JsonProperty("stop") String stopId,
      @JsonProperty("seq") long sequence,
      @JsonProperty("app") String appVersion) {}
}
