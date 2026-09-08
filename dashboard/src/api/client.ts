/**
 * The four `GET`s this dashboard makes, and nothing else.
 *
 * There is no write path here and there never will be: every write in this platform goes through
 * the component that owns the data — a manifest through the shipment service, a position through
 * the ingest gateway. The dashboard API answers questions and holds a stream open, so a client
 * of it is four fetches and an `EventSource`.
 *
 * The base URL is configurable because the API is a different origin in every mode this runs in:
 * `localhost:18083` from Vite's dev server on 5173, the same host under a different port when the
 * built assets are served, and a real hostname in M8. It is read from the environment at build
 * time rather than sniffed at runtime, so a misconfiguration fails visibly in one place.
 */
import type { IncidentSummary, Meta, ShipmentDetail, ShipmentSummary } from './types';

/** Where the dashboard API lives. Overridable with `VITE_API_BASE` in `.env.local`. */
export const API_BASE: string = import.meta.env.VITE_API_BASE ?? 'http://localhost:18083';

/**
 * A request that failed, carrying the status so a caller can tell the cases apart.
 *
 * The distinction that matters is a `404` on a shipment detail — "nothing has ever reported a
 * position for this load" — from everything else, which means the API is unreachable or unhappy.
 * The first is an answer; the second is an outage, and a dashboard that renders them the same way
 * tells its viewer to go and look at the wrong system.
 */
export class ApiError extends Error {
  // Declared and assigned rather than written as constructor parameter properties: the build sets
  // `erasableSyntaxOnly`, which restricts TypeScript to syntax a compiler can delete rather than
  // rewrite, and a parameter property emits an assignment. It is the one TypeScript feature this
  // file would otherwise have used.
  readonly status: number;
  readonly url: string;

  constructor(status: number, url: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.url = url;
  }
}

/**
 * One fetch, with a timeout.
 *
 * The timeout is the reason this is not three lines of inline `fetch`. A browser will wait a very
 * long time on a connection to a host that is listening but not answering, which is exactly what
 * a half-started service looks like, and a dashboard that hangs on its first load with no message
 * is indistinguishable from one that is broken.
 */
async function get<T>(path: string, signal?: AbortSignal, timeoutMs = 10_000): Promise<T> {
  const url = `${API_BASE}${path}`;
  const timeout = AbortSignal.timeout(timeoutMs);
  const response = await fetch(url, {
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) {
    throw new ApiError(response.status, url, `${response.status} from ${path}`);
  }
  return (await response.json()) as T;
}

/**
 * The state of the world: one marker per shipment with a known position.
 *
 * This is half of the API's shape and the half a client must not skip. The live stream begins at
 * the moment it is subscribed to and deliberately replays nothing, so a dashboard built on the
 * stream alone opens to an empty map and fills in over the following minutes as each truck happens
 * to report. Load this, then follow the stream.
 */
export function fetchFleet(signal?: AbortSignal): Promise<ShipmentSummary[]> {
  return get<ShipmentSummary[]>('/api/shipments', signal);
}

/** One shipment in full: plan, paperwork, incidents and recent trail. */
export function fetchShipment(shipmentId: string, signal?: AbortSignal): Promise<ShipmentDetail> {
  return get<ShipmentDetail>(`/api/shipments/${encodeURIComponent(shipmentId)}`, signal);
}

/**
 * Incidents across the fleet.
 *
 * @param open only what is currently wrong, which is what a panel meant to be acted on wants.
 *     Pass `false` for the history, including everything that has already cleared
 */
export function fetchExceptions(open = true, signal?: AbortSignal): Promise<IncidentSummary[]> {
  return get<IncidentSummary[]>(`/api/exceptions?open=${open}`, signal);
}

/**
 * What the API can see.
 *
 * Used here for the same reason the services log a heartbeat: a count of zero is the difference
 * between "nothing is happening" and "this is pointed at the wrong database", and that distinction
 * has cost this project a session before.
 */
export function fetchMeta(signal?: AbortSignal): Promise<Meta> {
  return get<Meta>('/api/meta', signal);
}
