/**
 * The dashboard API's wire format, written down once.
 *
 * Every type here mirrors a Java record in `services/dashboard-api`: `ShipmentSummary`,
 * `ShipmentDetail` and the `LiveUpdate` family. They are hand-written rather than generated,
 * which is a deliberate trade — a generator would need a schema step in the build and would
 * couple the front end to the exact shape of the server's serializer, while what this file
 * actually has to track is a small and slow-moving contract.
 *
 * The point of writing it down at all: the API omits nulls, so almost everything here is
 * optional, and code that reaches through an absent `nextStop` is the single easiest mistake to
 * make against this API. Marked optional here, it is a compile error rather than a marker that
 * silently vanishes off the map.
 *
 * Keep this file honest against `read/ShipmentSummary.java`, `read/ShipmentDetail.java` and
 * `stream/LiveUpdate.java`. Nothing checks it at build time; that is the cost of the trade.
 */

/**
 * What a truck is doing, as the one word the map colours by.
 *
 * Not derived in the browser from speed alone, and that matters: a truck reporting zero on a
 * hard shoulder and a truck reporting zero inside a delivery yard are the same number and
 * completely different situations. The server tells us which, because only it holds the
 * geofence state that settles it.
 */
export type Movement = 'MOVING' | 'STOPPED' | 'AT_STOP' | 'DELIVERED';

/** How bad an incident is. The server's judgement, not a threshold applied twice. */
export type Severity = 'INFO' | 'WARNING' | 'CRITICAL';

/** Whether an incident is still open. */
export type IncidentState = 'OPEN' | 'CLEARED';

/** An SLA breach, as much of one as a marker needs. */
export interface IncidentSummary {
  exceptionId: string;
  /** Present even nested inside a summary, because `/api/exceptions` returns this record flat. */
  shipmentId: string;
  type: string;
  severity?: Severity;
  state?: IncidentState;
  onsetAt?: string;
  raisedAt?: string;
  clearedAt?: string;
  stopId?: string;
  detail?: string;
  observedValue?: number;
  thresholdValue?: number;
  resolution?: string;
}

/** The stop a truck is currently sitting at. Absent while it is on the road. */
export interface AtStop {
  stopId: string;
  name?: string;
  city?: string;
  seq: number;
  since?: string;
}

/**
 * Where a truck is going, and when the platform thinks it gets there.
 *
 * `estimatedArrival` is absent more often than a first reading suggests, and `estimatePending`
 * says why: `AT_A_STOP` while the truck is parked (the platform publishes no estimate then, by
 * design), `SUPERSEDED` when the stored estimate names a stop already cleared, `NO_ESTIMATE_YET`
 * before the first fix of a leg. A dashboard that showed a blank here would look broken; showing
 * the reason is the difference.
 */
export interface NextStop {
  stopId: string;
  name?: string;
  city?: string;
  seq: number;
  latitude: number;
  longitude: number;
  radiusMeters: number;
  /** Road kilometres, measured on this request from the live position — never the stale field. */
  remainingKm?: number;
  estimatedArrival?: string;
  confidence?: number;
  estimatePending?: 'AT_A_STOP' | 'SUPERSEDED' | 'NO_ESTIMATE_YET';
}

/** One shipment, as the map draws it: a marker, a label, and a reason to click. */
export interface ShipmentSummary {
  shipmentId: string;
  vehicleId?: string;
  deviceId?: string;
  source?: string;
  occurredAt?: string;
  receivedAt?: string;
  updatedAt?: string;
  /**
   * How long ago the truck was where this says it is, measured from `receivedAt` rather than
   * from the event's own clock — under a time-scaled run simulated time outruns the wall clock,
   * so an event-time staleness is negative for every truck on the road.
   */
  staleSeconds?: number;
  latitude: number;
  longitude: number;
  speedKph?: number;
  headingDegrees?: number;
  accuracyMeters?: number;
  movement?: Movement;
  atStop?: AtStop;
  nextStop?: NextStop;
  stopsCompleted: number;
  stopsTotal: number;
  fractionComplete?: number;
  remainingRouteKm?: number;
  openExceptions?: IncidentSummary[];
  worstSeverity?: Severity;
}

/** Where a stop has got to. `DEPARTED` is terminal — the geofencer does not reopen a stop. */
export type StopStatus = 'PENDING' | 'AT' | 'DEPARTED';

/** One stop in the plan, with what actually happened at it folded in. */
export interface PlannedStop {
  stopId: string;
  seq: number;
  name?: string;
  city?: string;
  state?: string;
  latitude: number;
  longitude: number;
  radiusMeters: number;
  kind?: string;
  status: StopStatus;
  /** The instant the vehicle crossed in, not the instant the dwell threshold confirmed it. */
  arrivedAt?: string;
  departedAt?: string;
  dwellSeconds?: number;
}

/** One stored measurement, thinned to what a line on a map needs. */
export interface TrackPoint {
  ts: string;
  latitude: number;
  longitude: number;
  speedKph?: number;
  source?: string;
}

/**
 * The manifest, passed through untouched — `body` is a raw object all the way from the customer's
 * order system to this screen. Rendering it is S16's job; S15 only needs to know it exists.
 */
export interface ManifestDetail {
  customerId: string;
  mode?: string;
  schemaVersion?: string;
  createdAt?: string;
  body?: Record<string, unknown>;
}

/** Everything about one shipment. Fetched when a marker is selected. */
export interface ShipmentDetail {
  summary: ShipmentSummary;
  manifest?: ManifestDetail;
  stops?: PlannedStop[];
  exceptions?: IncidentSummary[];
  /** Recent positions, oldest first, for the trail behind the marker. */
  track?: TrackPoint[];
}

/** What the API can currently see. The first thing to check when the map opens empty. */
export interface Meta {
  trackedShipments: number;
  openExceptions: number;
  /** `archive` from the public view's lookup function; absent from the live API. */
  source?: 'archive';
  /** How recent the archive is: when the platform received the newest line indexed. Archive only. */
  archivedThrough?: string;
}

// ---------------------------------------------------------------------------
// The live stream
// ---------------------------------------------------------------------------

/**
 * The SSE event names. These are the `event:` lines on the wire, so they are also the strings
 * passed to `addEventListener` — a typo would simply mean a listener that never fires, which is
 * why they are a type rather than string literals scattered through the subscription code.
 */
export type LiveUpdateType =
  | 'position'
  | 'status'
  | 'arrived'
  | 'departed'
  | 'estimate'
  | 'exception.raised'
  | 'exception.cleared';

/** Where a truck is, thinned to what a marker needs. */
export interface PositionPayload {
  vehicleId?: string;
  latitude: number;
  longitude: number;
  speedKph?: number;
  headingDegrees?: number;
  accuracyMeters?: number;
  source?: string;
}

/** A stop reached or left. `dwellSeconds` is present only on a departure. */
export interface StopEventPayload {
  stopId: string;
  latitude: number;
  longitude: number;
  dwellSeconds?: number;
  scheduledArrival?: string;
}

/** A revised estimate. `remainingKm` here is fresh, unlike the stored field it comes from. */
export interface EstimatePayload {
  stopId: string;
  estimatedArrival?: string;
  previousEstimate?: string;
  remainingKm?: number;
  confidence?: number;
}

/** An SLA breach raised or cleared. Both carry the incident id, which is what pairs them. */
export interface ExceptionPayload {
  exceptionId: string;
  type: string;
  severity?: Severity;
  detail?: string;
  stopId?: string;
  observedValue?: number;
  thresholdValue?: number;
  raisedAt?: string;
  openForSeconds?: number;
  resolution?: string;
}

/** A status reading — a reefer temperature, most usefully. */
export interface StatusPayload {
  vehicleId?: string;
  status?: string;
  reasonCode?: string;
  temperatureCelsius?: number;
  setpointCelsius?: number;
}

/**
 * One thing that has just happened.
 *
 * A discriminated union on `type`, so narrowing on it gives the right payload for free and a
 * handler cannot read `remainingKm` off a position by accident.
 */
export type LiveUpdate =
  | { type: 'position'; shipmentId: string; at?: string; payload: PositionPayload }
  | { type: 'status'; shipmentId: string; at?: string; payload: StatusPayload }
  | { type: 'arrived'; shipmentId: string; at?: string; payload: StopEventPayload }
  | { type: 'departed'; shipmentId: string; at?: string; payload: StopEventPayload }
  | { type: 'estimate'; shipmentId: string; at?: string; payload: EstimatePayload }
  | { type: 'exception.raised'; shipmentId: string; at?: string; payload: ExceptionPayload }
  | { type: 'exception.cleared'; shipmentId: string; at?: string; payload: ExceptionPayload };
