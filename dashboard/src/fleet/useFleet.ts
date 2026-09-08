/**
 * Snapshot, then stream — the shape this API is built around, as one hook.
 *
 * Three things run here for as long as the page is open:
 *
 *   1. A snapshot fetch, which is what puts markers on the map at all.
 *   2. The live stream, which moves them. Every re-open of the stream triggers a fresh snapshot,
 *      because the stream deliberately carries no history and a client that was disconnected has
 *      missed exactly the updates it can no longer ask for.
 *   3. A slow poll, which re-fetches the snapshot on a timer. This is the reconciliation the store
 *      leans on: several fields on a summary are the server's conclusions, and the browser amends
 *      only the ones an update settles outright. Anything that has drifted is corrected here, and
 *      a shipment that started reporting after the page loaded appears here too.
 *
 * The fleet itself is not React state — see `FleetStore`. What this hook exposes to React is the
 * handful of aggregates a person reads rather than watches, recomputed on a slow timer, so a
 * hundred position updates a second cost no renders at all.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { fetchFleet, fetchMeta } from '../api/client';
import { subscribeLive, type StreamState } from '../api/stream';
import type { Meta, Movement } from '../api/types';
import { FleetStore } from './FleetStore';

/** How often the snapshot is re-fetched. Slow, because the stream is what carries movement. */
const POLL_INTERVAL_MS = 20_000;

/** How often the numbers on screen are recomputed from the store. */
const TICK_INTERVAL_MS = 1_000;

/** The aggregates React is allowed to re-render for. */
export interface FleetStatus {
  streamState: StreamState;
  /** Shipments with a known position. */
  shipments: number;
  /** How many are doing each thing, for the legend's counts. */
  byMovement: Record<Movement, number>;
  openIncidents: number;
  criticalIncidents: number;
  updatesApplied: number;
  /** Seconds since any update arrived, or null before the first one. */
  quietForSeconds: number | null;
  /** When the snapshot was last successfully fetched. */
  snapshotAt: number | null;
  /** What went wrong with the last snapshot fetch, if anything. */
  error: string | null;
  /** What the API says it can see, which is the check to make when the map opens empty. */
  meta: Meta | null;
}

const EMPTY_MOVEMENT: Record<Movement, number> = {
  MOVING: 0,
  STOPPED: 0,
  AT_STOP: 0,
  DELIVERED: 0,
};

export function useFleet(): { store: FleetStore; status: FleetStatus; refresh: () => void } {
  // One store for the lifetime of the page. `useMemo` with no dependencies rather than `useRef`
  // so it is never constructed twice on a re-render.
  const store = useMemo(() => new FleetStore(), []);

  const [status, setStatus] = useState<FleetStatus>({
    streamState: 'connecting',
    shipments: 0,
    byMovement: { ...EMPTY_MOVEMENT },
    openIncidents: 0,
    criticalIncidents: 0,
    updatesApplied: 0,
    quietForSeconds: null,
    snapshotAt: null,
    error: null,
    meta: null,
  });

  // Held in a ref so the recompute timer can read it without being re-created, and so a snapshot
  // fetch that lands after a stream state change does not overwrite it with a stale value.
  const streamState = useRef<StreamState>('connecting');
  const snapshotAt = useRef<number | null>(null);
  const error = useRef<string | null>(null);
  const meta = useRef<Meta | null>(null);

  const refresh = useCallback(() => {
    // Not aborted on unmount deliberately: the only cost of a snapshot arriving after the page
    // has gone is a store nobody reads being updated once. Aborting would mean threading a signal
    // through every caller of this, including the stream's reconnect path.
    fetchFleet()
      .then((fleet) => {
        store.replaceSnapshot(fleet);
        snapshotAt.current = Date.now();
        error.current = null;
      })
      .catch((cause: unknown) => {
        error.current = cause instanceof Error ? cause.message : 'the API could not be reached';
      });

    fetchMeta()
      .then((seen) => {
        meta.current = seen;
      })
      .catch(() => {
        // Already covered by the fleet fetch's error; a second message saying the same thing
        // would push the useful one off the status bar.
      });
  }, [store]);

  useEffect(() => {
    refresh();

    const close = subscribeLive({
      onUpdate: (update) => store.apply(update),
      onOpen: () => refresh(),
      onState: (state) => {
        streamState.current = state;
      },
    });

    const poll = window.setInterval(refresh, POLL_INTERVAL_MS);

    const tick = window.setInterval(() => {
      const byMovement = { ...EMPTY_MOVEMENT };
      let openIncidents = 0;
      let criticalIncidents = 0;

      for (const shipment of store.all()) {
        if (shipment.movement) {
          byMovement[shipment.movement] += 1;
        }
        for (const incident of shipment.openExceptions ?? []) {
          openIncidents += 1;
          if (incident.severity === 'CRITICAL') {
            criticalIncidents += 1;
          }
        }
      }

      setStatus({
        streamState: streamState.current,
        shipments: store.size(),
        byMovement,
        openIncidents,
        criticalIncidents,
        updatesApplied: store.updatesApplied,
        quietForSeconds:
          store.lastUpdateWall === 0
            ? null
            : Math.round((Date.now() - store.lastUpdateWall) / 1000),
        snapshotAt: snapshotAt.current,
        error: error.current,
        meta: meta.current,
      });
    }, TICK_INTERVAL_MS);

    return () => {
      close();
      window.clearInterval(poll);
      window.clearInterval(tick);
    };
  }, [refresh, store]);

  return { store, status, refresh };
}
