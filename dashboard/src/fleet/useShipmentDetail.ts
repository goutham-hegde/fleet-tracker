/**
 * The detail behind a selected marker: its plan, its stops, its trail and its paperwork.
 *
 * Fetched when a shipment is picked and re-fetched slowly while it stays picked, because the
 * things on it change at very different rates. The stored trail and the stop statuses move as the
 * truck does; the manifest never changes at all. A fast poll would spend a query a second on the
 * one endpoint in this API that reads five collections, to keep a stop list fresh that changes
 * every forty minutes — so the marker moves on the stream and the plan behind it catches up on a
 * timer.
 *
 * A `404` here is an answer rather than a failure: it means nothing has ever reported a position
 * for that shipment. It cannot happen from a click on the map, since a marker only exists because
 * a position does, but it can happen from a stale link, and it must not read as an outage.
 */
import { useEffect, useState } from 'react';
import { ApiError, fetchShipment } from '../api/client';
import type { ShipmentDetail } from '../api/types';

/** How often the selected shipment's plan is re-read while it stays open. */
const DETAIL_POLL_MS = 10_000;

/** What has been loaded, and which shipment it belongs to. */
interface DetailState {
  shipmentId: string | null;
  detail: ShipmentDetail | null;
  error: string | null;
}

export function useShipmentDetail(shipmentId: string | null): {
  detail: ShipmentDetail | null;
  error: string | null;
} {
  const [state, setState] = useState<DetailState>({ shipmentId, detail: null, error: null });

  // Adjusted during render rather than in an effect, which is React's own recommendation for
  // state that has to be reset when a prop changes. The alternative — clearing it inside the
  // fetching effect — renders one frame with the *previous* shipment's plan under the newly
  // clicked one's heading, which is worse than a moment of nothing.
  if (state.shipmentId !== shipmentId) {
    setState({ shipmentId, detail: null, error: null });
  }

  useEffect(() => {
    if (!shipmentId) {
      return;
    }

    const controller = new AbortController();
    const settle = (next: Omit<DetailState, 'shipmentId'>) =>
      // Guarded on the shipment id: a response for a shipment that is no longer selected must not
      // overwrite the one that is. The abort covers the common case, but a request already in
      // flight can still resolve first.
      setState((current) =>
        current.shipmentId === shipmentId ? { shipmentId, ...next } : current,
      );

    const load = () => {
      fetchShipment(shipmentId, controller.signal)
        .then((fetched) => settle({ detail: fetched, error: null }))
        .catch((cause: unknown) => {
          if (controller.signal.aborted) {
            return;
          }
          if (cause instanceof ApiError && cause.status === 404) {
            settle({
              detail: null,
              error: 'the platform has never had a position for this shipment',
            });
            return;
          }
          settle({
            detail: null,
            error: cause instanceof Error ? cause.message : 'could not load this shipment',
          });
        });
    };

    load();
    const poll = window.setInterval(load, DETAIL_POLL_MS);

    return () => {
      controller.abort();
      window.clearInterval(poll);
    };
  }, [shipmentId]);

  return { detail: state.detail, error: state.error };
}
