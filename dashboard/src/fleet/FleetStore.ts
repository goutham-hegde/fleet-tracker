/**
 * What the dashboard believes about the fleet, and how a live update changes it.
 *
 * <h2>Why this is not React state</h2>
 *
 * A run at a time scale of three hundred puts a position update on the wire roughly twice a second
 * per truck, and the map is expected to hold sixty-four of them. Held in `useState`, every one of
 * those would re-render a component tree in order to move a marker that MapLibre draws into the
 * DOM itself, outside React entirely. So the fleet lives here, in a plain object with subscribers,
 * and React is told only about the things a person reads rather than watches: how many trucks,
 * how many incidents, whether the stream is connected.
 *
 * <h2>The snapshot is the truth and the stream is an amendment</h2>
 *
 * Several fields on a summary are the server's conclusions rather than raw readings — whether a
 * truck counts as at a stop, which stop is next, how far is left through the plan. This store
 * amends them from stream updates where an update settles the question beyond doubt (an announced
 * arrival does), and otherwise leaves them alone and waits for the next snapshot. That is the
 * honest division: the browser must not re-derive a conclusion the platform makes with state the
 * browser does not have, and the periodic re-fetch is what keeps a drifted field from staying
 * wrong for ever.
 */
import type {
  IncidentSummary,
  LiveUpdate,
  Movement,
  Severity,
  ShipmentSummary,
  StatusPayload,
} from '../api/types';

/**
 * The same 5 km/h the ETA calculator learns from and the server's assembler colours by.
 *
 * Restated here rather than fetched, and it is a duplicate with a cost: change it on the server
 * and this file is wrong. The alternative — a marker whose colour changes only when the snapshot
 * is re-fetched — would have a truck sit green and stationary for thirty seconds after it stopped,
 * which is the one thing a live map must not do.
 */
export const MOVING_KPH = 5;

/** How many live positions are kept behind each truck. About a minute of movement at demo speed. */
const TRAIL_LIMIT = 60;

/** One `[longitude, latitude]` pair, in the order MapLibre and GeoJSON use. */
export type LngLat = [number, number];

/** A shipment as this dashboard holds it: the API's summary plus what the browser has observed. */
export interface FleetShipment extends ShipmentSummary {
  /**
   * When this browser last had news of the truck, on the browser's own clock.
   *
   * Deliberately not computed from `receivedAt` against `Date.now()`. That comparison spans two
   * machines' clocks, and a viewer's laptop being a minute fast would grey out a fleet that is
   * reporting perfectly. Seeded from the server's own `staleSeconds` at snapshot time and advanced
   * locally from there, so only elapsed time is ever measured, never an absolute instant.
   */
  lastSeenWall: number;

  /** The most recent status reading, when there has been one. A reefer temperature, usually. */
  lastStatus?: StatusPayload & { at?: string };
}

type ShipmentListener = (shipmentId: string, shipment: FleetShipment | undefined) => void;
type SnapshotListener = () => void;

/** Severity ordered worst-first, for picking the one a marker shows. */
const SEVERITY_RANK: Record<Severity, number> = { CRITICAL: 3, WARNING: 2, INFO: 1 };

function worstOf(incidents: IncidentSummary[] | undefined): Severity | undefined {
  if (!incidents || incidents.length === 0) {
    return undefined;
  }
  let worst: Severity | undefined;
  for (const incident of incidents) {
    const severity = incident.severity;
    if (!severity) {
      continue;
    }
    if (!worst || SEVERITY_RANK[severity] > SEVERITY_RANK[worst]) {
      worst = severity;
    }
  }
  return worst;
}

/**
 * An incident this browser watched clear.
 *
 * Not a wire type: it is the raise and the clear merged, which is a thing only a client that saw
 * both can produce. `openForSeconds` comes from the clear event and is the *platform's* measure of
 * how long the condition lasted, in event time — the only honest duration available here, since a
 * browser holding a wall clock cannot measure an interval in simulated time.
 */
export interface ResolvedIncident extends IncidentSummary {
  openForSeconds?: number;
}

/**
 * How many resolved incidents are kept.
 *
 * A ring rather than everything, because a repeating run raises and clears indefinitely and this
 * is a live view rather than an audit trail. The full history, including everything cleared before
 * this page was opened, is a query away on `/api/exceptions?open=false` — deliberately not fetched
 * here, because what the panel is for is watching a breach appear and then go away again.
 */
const RESOLVED_LIMIT = 40;

export class FleetStore {
  private readonly shipments = new Map<string, FleetShipment>();
  private readonly trails = new Map<string, LngLat[]>();
  private resolved: ResolvedIncident[] = [];
  private readonly shipmentListeners = new Set<ShipmentListener>();
  private readonly snapshotListeners = new Set<SnapshotListener>();

  /** Updates applied since the page opened, for the status bar. */
  updatesApplied = 0;

  /** When the last update of any kind arrived, on the browser's clock. */
  lastUpdateWall = 0;

  // -- reading ------------------------------------------------------------

  get(shipmentId: string): FleetShipment | undefined {
    return this.shipments.get(shipmentId);
  }

  all(): FleetShipment[] {
    return [...this.shipments.values()];
  }

  size(): number {
    return this.shipments.size;
  }

  trail(shipmentId: string): LngLat[] {
    return this.trails.get(shipmentId) ?? [];
  }

  /**
   * Every open incident across the fleet, worst first and then most recent.
   *
   * Assembled from the shipments rather than held as a second list, so there is exactly one place
   * an incident lives and no way for a panel and a marker to disagree about whether a truck has a
   * problem. Sixty-four shipments with a handful of incidents between them is nothing to walk.
   */
  openIncidents(): IncidentSummary[] {
    const incidents: IncidentSummary[] = [];
    for (const shipment of this.shipments.values()) {
      if (shipment.openExceptions) {
        incidents.push(...shipment.openExceptions);
      }
    }
    return incidents.sort(compareIncidents);
  }

  /**
   * Incidents this browser has watched clear, most recently resolved first.
   *
   * Worth keeping, and not only for completeness: an alerting system that can only raise is half
   * of one, and a panel where breaches accumulate and never leave says nothing about whether the
   * condition ended. These are the browser's own observations — a page opened after a breach
   * cleared has no record of it, which is honest about what a live view is.
   */
  clearedIncidents(): ResolvedIncident[] {
    return [...this.resolved].reverse();
  }

  // -- subscribing --------------------------------------------------------

  /** Called for every shipment whose position or state changed. The map's hot path. */
  onShipment(listener: ShipmentListener): () => void {
    this.shipmentListeners.add(listener);
    return () => this.shipmentListeners.delete(listener);
  }

  /** Called when a whole new snapshot has replaced what was here. */
  onSnapshot(listener: SnapshotListener): () => void {
    this.snapshotListeners.add(listener);
    return () => this.snapshotListeners.delete(listener);
  }

  // -- writing ------------------------------------------------------------

  /**
   * Replace everything with a freshly fetched snapshot.
   *
   * Trails survive, keyed by shipment, because they are the browser's own observation rather than
   * the server's — a re-fetch is not a reason to forget where a truck has just been. A shipment
   * that has disappeared from the fleet loses its trail with it.
   */
  replaceSnapshot(summaries: ShipmentSummary[]): void {
    const now = Date.now();
    const seen = new Set<string>();

    for (const summary of summaries) {
      seen.add(summary.shipmentId);
      const existing = this.shipments.get(summary.shipmentId);
      this.shipments.set(summary.shipmentId, {
        ...summary,
        // The server measured staleness at the instant it answered; this converts that into a
        // local instant so everything afterwards is elapsed browser time.
        lastSeenWall: now - (summary.staleSeconds ?? 0) * 1000,
        lastStatus: existing?.lastStatus,
      });
      this.pushTrail(summary.shipmentId, [summary.longitude, summary.latitude]);
    }

    for (const shipmentId of [...this.shipments.keys()]) {
      if (!seen.has(shipmentId)) {
        this.shipments.delete(shipmentId);
        this.trails.delete(shipmentId);
      }
    }

    for (const listener of this.snapshotListeners) {
      listener();
    }
  }

  /**
   * Apply one live update.
   *
   * An update for a shipment this store has never heard of is ignored rather than invented from,
   * because a marker built from a position update alone would have no plan, no manifest and no
   * incidents — it would appear on the map as a truck with nothing known about it and stay that
   * way until the next snapshot. The snapshot re-fetch is what picks such a shipment up, and it is
   * never more than the poll interval away.
   */
  apply(update: LiveUpdate): boolean {
    const existing = this.shipments.get(update.shipmentId);
    if (!existing) {
      return false;
    }

    const now = Date.now();
    let next: FleetShipment;

    switch (update.type) {
      case 'position': {
        const payload = update.payload;
        next = {
          ...existing,
          latitude: payload.latitude,
          longitude: payload.longitude,
          speedKph: payload.speedKph,
          headingDegrees: payload.headingDegrees,
          accuracyMeters: payload.accuracyMeters,
          source: payload.source ?? existing.source,
          vehicleId: payload.vehicleId ?? existing.vehicleId,
          occurredAt: update.at ?? existing.occurredAt,
          movement: movementAfterFix(existing.movement, payload.speedKph),
          staleSeconds: 0,
          lastSeenWall: now,
        };
        this.pushTrail(update.shipmentId, [payload.longitude, payload.latitude]);
        break;
      }

      case 'arrived': {
        // The platform announced this; it is not a guess from proximity, so the browser may act
        // on it directly. The stop's name and sequence are not on the event, so they stay as the
        // last snapshot had them until the next one fills them in.
        next = {
          ...existing,
          movement: 'AT_STOP',
          atStop: {
            stopId: update.payload.stopId,
            name: existing.nextStop?.name,
            city: existing.nextStop?.city,
            seq: existing.nextStop?.seq ?? existing.stopsCompleted,
            since: update.at,
          },
          stopsCompleted: Math.min(existing.stopsCompleted + 1, existing.stopsTotal),
          lastSeenWall: now,
        };
        break;
      }

      case 'departed': {
        next = {
          ...existing,
          movement: 'STOPPED',
          atStop: undefined,
          lastSeenWall: now,
        };
        break;
      }

      case 'estimate': {
        // Only when the estimate names the stop the marker is heading for. An estimate for any
        // other stop means this browser's idea of "next" is behind the platform's, and guessing
        // which way would put a marker's label on a stop it has already cleared. The next snapshot
        // settles it.
        if (!existing.nextStop || existing.nextStop.stopId !== update.payload.stopId) {
          return false;
        }
        next = {
          ...existing,
          nextStop: {
            ...existing.nextStop,
            estimatedArrival: update.payload.estimatedArrival,
            confidence: update.payload.confidence,
            // Fresh, unlike the stored field this comes from: the processor measured it on the
            // fix that caused the estimate, which is the instant this update describes.
            remainingKm: update.payload.remainingKm ?? existing.nextStop.remainingKm,
            estimatePending: undefined,
          },
        };
        break;
      }

      case 'exception.raised': {
        const raised: IncidentSummary = {
          exceptionId: update.payload.exceptionId,
          shipmentId: update.shipmentId,
          type: update.payload.type,
          severity: update.payload.severity,
          state: 'OPEN',
          onsetAt: update.at,
          raisedAt: update.at,
          stopId: update.payload.stopId,
          detail: update.payload.detail,
          observedValue: update.payload.observedValue,
          thresholdValue: update.payload.thresholdValue,
        };
        // Keyed on the incident id rather than appended blindly: a raise republished after a
        // restart is byte-identical by design, and a marker showing the same breach twice would
        // be this dashboard inventing a problem the platform did not report.
        const others = (existing.openExceptions ?? []).filter(
          (incident) => incident.exceptionId !== raised.exceptionId,
        );
        const openExceptions = [...others, raised];
        next = { ...existing, openExceptions, worstSeverity: worstOf(openExceptions) };
        break;
      }

      case 'exception.cleared': {
        const cleared = (existing.openExceptions ?? []).find(
          (incident) => incident.exceptionId === update.payload.exceptionId,
        );
        const openExceptions = (existing.openExceptions ?? []).filter(
          (incident) => incident.exceptionId !== update.payload.exceptionId,
        );
        // The raise is what carries the onset, the type and the severity; the clear carries the
        // resolution and little else. Merging them is what makes "cold chain breach, 4 minutes,
        // resolved" a sentence — and the incident id is what pairs the two, which is the whole
        // reason the platform derives that id from the onset rather than minting a fresh one.
        this.recordResolved({
          ...cleared,
          exceptionId: update.payload.exceptionId,
          shipmentId: update.shipmentId,
          type: update.payload.type ?? cleared?.type ?? 'UNKNOWN',
          severity: update.payload.severity ?? cleared?.severity,
          state: 'CLEARED',
          onsetAt: cleared?.onsetAt ?? update.payload.raisedAt,
          raisedAt: update.payload.raisedAt ?? cleared?.raisedAt,
          clearedAt: update.at,
          detail: cleared?.detail ?? update.payload.detail,
          resolution: update.payload.resolution,
          openForSeconds: update.payload.openForSeconds,
        });
        next = {
          ...existing,
          openExceptions: openExceptions.length > 0 ? openExceptions : undefined,
          worstSeverity: worstOf(openExceptions),
        };
        break;
      }

      case 'status': {
        next = {
          ...existing,
          lastStatus: { ...update.payload, at: update.at },
          lastSeenWall: now,
        };
        break;
      }

      default:
        return false;
    }

    this.shipments.set(update.shipmentId, next);
    this.updatesApplied += 1;
    this.lastUpdateWall = now;
    for (const listener of this.shipmentListeners) {
      listener(update.shipmentId, next);
    }
    return true;
  }

  /**
   * File a resolved incident, replacing any earlier record of the same one.
   *
   * Keyed on the incident id for the same reason a raise is: the platform republishes
   * byte-identical events after a restart, and a resolved list that grew a second copy would be
   * this dashboard inventing history the platform did not report.
   */
  private recordResolved(incident: ResolvedIncident): void {
    this.resolved = this.resolved.filter(
      (existing) => existing.exceptionId !== incident.exceptionId,
    );
    this.resolved.push(incident);
    if (this.resolved.length > RESOLVED_LIMIT) {
      this.resolved.shift();
    }
  }

  private pushTrail(shipmentId: string, point: LngLat): void {
    const trail = this.trails.get(shipmentId);
    if (!trail) {
      this.trails.set(shipmentId, [point]);
      return;
    }
    const last = trail[trail.length - 1];
    if (last && last[0] === point[0] && last[1] === point[1]) {
      // A parked truck reports the same coordinate for as long as it is parked. Keeping those
      // would push the whole trail out of the buffer and leave nothing drawn behind it when it
      // moves off.
      return;
    }
    trail.push(point);
    if (trail.length > TRAIL_LIMIT) {
      trail.shift();
    }
  }
}

/**
 * Worst first, then most recently begun.
 *
 * Severity before recency because a panel is read from the top and a critical breach that started
 * an hour ago still outranks a warning that started a minute ago. Within a severity, newest first,
 * because that is the one somebody has not yet looked at.
 */
function compareIncidents(a: IncidentSummary, b: IncidentSummary): number {
  const bySeverity =
    (b.severity ? SEVERITY_RANK[b.severity] : 0) - (a.severity ? SEVERITY_RANK[a.severity] : 0);
  if (bySeverity !== 0) {
    return bySeverity;
  }
  return (b.onsetAt ?? '').localeCompare(a.onsetAt ?? '');
}

/**
 * What a truck is doing after a fix, given what it was doing before.
 *
 * `AT_STOP` and `DELIVERED` are the platform's conclusions and survive a fix untouched: a truck
 * creeping around a yard at 6 km/h is still at the stop, and a delivered load does not start
 * moving again because one late fix arrived. Between stops, speed is all there is, and it is the
 * same threshold the server applies.
 */
export function movementAfterFix(previous: Movement | undefined, speedKph?: number): Movement {
  if (previous === 'DELIVERED' || previous === 'AT_STOP') {
    return previous;
  }
  return speedKph != null && speedKph >= MOVING_KPH ? 'MOVING' : 'STOPPED';
}
