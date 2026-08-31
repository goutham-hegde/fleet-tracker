package com.fleettracking.simulator.emit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How each of the four feeds reports, and where their output goes.
 *
 * <p>Every interval here is in <b>simulated</b> time, not real time. A telematics unit reporting
 * every thirty seconds reports every thirty simulated seconds whether the run is at
 * {@code time-scale: 1} or {@code 3000} — which is the only definition that keeps a compressed run
 * a faithful compression rather than a differently-shaped feed.
 *
 * <p>Nested types use boxed {@code Boolean} deliberately: a primitive {@code boolean} cannot tell
 * "the user set it to false" from "the user said nothing", so an omitted block would silently
 * disable a feed. Null is normalized to the documented default in the compact constructors below.
 *
 * @param logging whether formatted payloads are logged. The log sink writes at DEBUG; see
 *     {@link LoggingMessageSink}
 * @param logSummaryEvery emit a per-feed count line every this many messages; 0 disables
 * @param captureDir directory to capture fixtures into. Blank or null means no capture, which is
 *     the default — a normal run should not write files unless asked
 * @param captureMaxPerFeed cap on captured messages per feed, so a fixture set stays a sample
 * @param captureMaxInterchanges separate, lower cap for EDI, which writes one file per message
 *     rather than one line
 * @param telematics the in-cab unit: frequent, and the only feed reporting continuously
 * @param mobile the driver's phone
 * @param edi the carrier's back-office batch
 * @param reefer the trailer temperature probe
 * @param http where to post emitted messages, when the simulator is pointed at a running gateway
 */
@ConfigurationProperties(prefix = "fleet.simulator.emit")
public record EmissionProperties(
    Boolean logging,
    long logSummaryEvery,
    String captureDir,
    long captureMaxPerFeed,
    long captureMaxInterchanges,
    Telematics telematics,
    Mobile mobile,
    Edi edi,
    Reefer reefer,
    Http http) {

  public EmissionProperties {
    logging = logging == null || logging;
    logSummaryEvery = logSummaryEvery <= 0 ? 500 : logSummaryEvery;
    captureMaxPerFeed = captureMaxPerFeed <= 0 ? 200 : captureMaxPerFeed;
    captureMaxInterchanges =
        captureMaxInterchanges <= 0 ? Math.min(captureMaxPerFeed, 15) : captureMaxInterchanges;
    telematics = telematics == null ? new Telematics(null, null, null) : telematics;
    mobile = mobile == null ? new Mobile(null, null, null, null, null) : mobile;
    edi = edi == null ? new Edi(null, null, null, null, null, null) : edi;
    reefer = reefer == null ? new Reefer(null, null, null, null) : reefer;
    http = http == null ? new Http(null, null, null, null) : http;
  }

  /** True when the simulator should post its output to a gateway. */
  public boolean posting() {
    return http.enabled();
  }

  /** True when a capture directory has actually been configured. */
  public boolean capturing() {
    return captureDir != null && !captureDir.isBlank();
  }

  /**
   * In-cab telematics. Reports on a fixed cadence regardless of what the truck is doing, which is
   * why it is the only feed that keeps producing while a truck sits on a dock for ninety minutes.
   *
   * @param enabled default true
   * @param interval simulated time between reports, default 30s
   * @param unitPrefix device id prefix; the unit is a different box from the reefer probe and
   *     carries a different identifier, which is half the point of identity resolution in S8
   */
  public record Telematics(Boolean enabled, Duration interval, String unitPrefix) {
    public Telematics {
      enabled = enabled == null || enabled;
      interval = interval == null ? Duration.ofSeconds(30) : interval;
      unitPrefix = unitPrefix == null ? "TLM" : unitPrefix;
    }
  }

  /**
   * The driver's phone.
   *
   * @param enabled default true
   * @param interval simulated time between pings when the app has signal, default 3m
   * @param outageProbability chance per report of dropping into a dead zone, default 0.04
   * @param outageDuration how long a dead zone lasts in simulated time, default 25m. Everything
   *     the app would have sent is buffered and dumped on reconnect
   * @param appVersion reported in the payload, and a realistic reason for shape drift later
   */
  public record Mobile(
      Boolean enabled,
      Duration interval,
      Double outageProbability,
      Duration outageDuration,
      String appVersion) {
    public Mobile {
      enabled = enabled == null || enabled;
      interval = interval == null ? Duration.ofMinutes(3) : interval;
      outageProbability = outageProbability == null ? 0.04 : outageProbability;
      outageDuration = outageDuration == null ? Duration.ofMinutes(25) : outageDuration;
      appVersion = appVersion == null ? "3.4.1" : appVersion;
    }
  }

  /**
   * The carrier's EDI 214 batch.
   *
   * @param enabled default true
   * @param batchInterval simulated time between interchange drops, default 30m. Statuses accumulate
   *     between drops and go out together
   * @param filingDelay how long after an event the back office gets round to filing it, default
   *     45m. This is on top of waiting for the next batch window
   * @param scac Standard Carrier Alpha Code — the carrier's identifier in EDI
   * @param senderId ISA sender qualifier value
   * @param receiverId ISA receiver qualifier value
   */
  public record Edi(
      Boolean enabled,
      Duration batchInterval,
      Duration filingDelay,
      String scac,
      String senderId,
      String receiverId) {
    public Edi {
      enabled = enabled == null || enabled;
      batchInterval = batchInterval == null ? Duration.ofMinutes(30) : batchInterval;
      filingDelay = filingDelay == null ? Duration.ofMinutes(45) : filingDelay;
      scac = scac == null ? "FLTX" : scac;
      senderId = senderId == null ? "CARRIER01" : senderId;
      receiverId = receiverId == null ? "FLEETTRACK" : receiverId;
    }
  }

  /**
   * The trailer temperature probe.
   *
   * @param enabled default true
   * @param interval simulated time between readings, default 5m
   * @param onlyColdChain when true (the default) only refrigerated lanes carry a probe, which is
   *     what actually happens — a dry van has nothing to measure
   * @param model reported hardware model, an example of a field the canonical envelope does not
   *     model and {@code raw} therefore has to preserve
   */
  public record Reefer(Boolean enabled, Duration interval, Boolean onlyColdChain, String model) {
    public Reefer {
      enabled = enabled == null || enabled;
      interval = interval == null ? Duration.ofMinutes(5) : interval;
      onlyColdChain = onlyColdChain == null || onlyColdChain;
      model = model == null ? "ThermoKing-CX7" : model;
    }
  }

  /**
   * Posting emitted messages to the ingest gateway over HTTP, the way real devices reach it.
   *
   * @param enabled default <b>false</b>, unlike every other feed setting here. The simulator's
   *     defining property is that it runs standalone: switching this on by default would make a
   *     plain {@code java -jar} run depend on a service being up, and fill the console with
   *     connection errors when it is not
   * @param baseUrl the gateway's root, default {@code http://localhost:18081} — the non-default
   *     port the rest of this project uses, since 8080 is taken on this machine
   * @param timeout connect and request timeout, default 5s. A device on a cellular link gives up
   *     quickly and tries again later rather than holding a socket open
   * @param queueCapacity how many messages may wait to be sent, default 5000. When it fills, new
   *     messages are dropped rather than the fleet being made to wait — which is what a real
   *     device with a full buffer does
   */
  public record Http(Boolean enabled, String baseUrl, Duration timeout, Integer queueCapacity) {
    public Http {
      enabled = enabled != null && enabled;
      baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:18081" : baseUrl;
      timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
      queueCapacity = queueCapacity == null || queueCapacity <= 0 ? 5000 : queueCapacity;
    }
  }
}
