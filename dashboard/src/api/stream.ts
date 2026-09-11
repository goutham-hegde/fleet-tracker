/**
 * The live half of the API: one long-lived connection carrying what has changed.
 *
 * The server sends named events — `event: position`, `event: exception.raised` — with the whole
 * update as the `data:` line. Named events do *not* fire `onmessage`, so a listener is registered
 * per name; a name registered here but not sent by the server is simply a listener that never
 * fires, and a name sent but not registered is an update silently dropped. That is why the list of
 * names is a type in `types.ts` rather than string literals written out here.
 *
 * Nothing in this file retries, and that is not an omission. `EventSource` reconnects on its own
 * with its own backoff, which is one of the two reasons the platform chose server-sent events over
 * WebSockets in the first place. What this file adds is telling the caller *when* it reconnected,
 * because the stream carries no history: a client that has been disconnected for thirty seconds
 * has missed thirty seconds of movement and must re-fetch the snapshot rather than carry on from
 * markers it now knows to be wrong.
 */
import { API_BASE } from './client';
import type { LiveUpdate, LiveUpdateType } from './types';

/** Every event name the server sends. Registered one by one, for the reason in the file note. */
const EVENT_NAMES: LiveUpdateType[] = [
  'position',
  'status',
  'arrived',
  'departed',
  'estimate',
  'exception.raised',
  'exception.cleared',
];

/**
 * Where the connection has got to.
 *
 * `reconnecting` rather than `error`, because a dropped stream is the ordinary case — a service
 * restart, a laptop lid, a network hiccup — and `EventSource` is already dealing with it. The
 * distinction a viewer needs is between "the markers you are looking at are live" and "they are
 * as of some time ago", which is exactly these three values.
 */
/**
 * Where the stream is. `archive` is not a stream state at all: it is the public build, which has no
 * stream to be in any state of, and saying so is better than a pill reading "connecting" for ever.
 */
export type StreamState = 'connecting' | 'open' | 'reconnecting' | 'archive';

export interface StreamHandlers {
  /** One update, already parsed and narrowed by `type`. */
  onUpdate: (update: LiveUpdate) => void;

  /**
   * Called every time the connection becomes usable, including on every reconnection.
   *
   * The caller is expected to re-fetch the snapshot here. It fires on the first open too, which
   * costs one redundant fetch at startup and keeps the reconnect path from being a special case
   * that only runs when something has already gone wrong — the path least likely to be exercised
   * and most likely to be broken.
   */
  onOpen: () => void;

  /** The connection dropped and is being retried. */
  onState: (state: StreamState) => void;
}

/**
 * Subscribe to everything happening on the platform.
 *
 * @returns a function that closes the connection. Calling it stops the automatic reconnection too,
 *     which is what makes this safe to use from an effect that re-runs
 */
export function subscribeLive(handlers: StreamHandlers): () => void {
  const source = new EventSource(`${API_BASE}/api/stream`);
  let opened = false;

  handlers.onState('connecting');

  source.onopen = () => {
    opened = true;
    handlers.onState('open');
    handlers.onOpen();
  };

  source.onerror = () => {
    // `EventSource` reports an error both for a connection that dropped and for one that was
    // never established, and it keeps retrying either way unless the readyState says CLOSED.
    // Reporting the same state for both is deliberate: to a viewer, "not connected yet" and
    // "connected and then lost it" mean the same thing about the markers on the screen.
    handlers.onState(opened ? 'reconnecting' : 'connecting');
  };

  for (const name of EVENT_NAMES) {
    source.addEventListener(name, (event: MessageEvent<string>) => {
      let update: LiveUpdate;
      try {
        update = JSON.parse(event.data) as LiveUpdate;
      } catch {
        // One unreadable frame is one marker that does not move until its next update, which is
        // at most half a second away. The server takes the same view of a record it cannot parse:
        // count it, drop it, and do not let it stop the stream.
        return;
      }
      handlers.onUpdate(update);
    });
  }

  return () => {
    source.close();
  };
}
