/**
 * One shipment out of the store, as React state.
 *
 * The store deliberately lives outside React so that sixty-four trucks reporting twice a second
 * cost no renders. That is right for the map, which moves markers imperatively, and wrong for a
 * card of text — a person reading a speed and an estimate wants them to change.
 *
 * So exactly one shipment is bridged into React at a time, through the hook React provides for
 * precisely this: `useSyncExternalStore` takes a way to subscribe and a way to read, and handles
 * the tearing and timing problems that a hand-rolled subscribe-and-`setState` gets subtly wrong.
 * It works here only because the store replaces a shipment object rather than mutating it — the
 * snapshot's identity is what tells React whether anything changed, and an object updated in place
 * would look identical for ever.
 *
 * The subscription is throttled rather than the render. A card re-rendering on every fix would be
 * up to thirty renders a second under a time-scaled run, to change a number no eye can follow at
 * that rate; twice a second is faster than a person reads and cheap enough to ignore.
 */
import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import type { FleetShipment, FleetStore } from './FleetStore';

/** The shortest gap between two re-renders of the card. */
const THROTTLE_MS = 500;

export function useLiveShipment(
  store: FleetStore,
  shipmentId: string | null,
): FleetShipment | undefined {
  const subscribe = useCallback(
    (notify: () => void) => {
      if (!shipmentId) {
        return () => {};
      }

      let lastNotified = 0;
      let pending: number | null = null;

      const fire = () => {
        lastNotified = Date.now();
        notify();
      };

      const offShipment = store.onShipment((changedId) => {
        if (changedId !== shipmentId) {
          return;
        }
        const since = Date.now() - lastNotified;
        if (since >= THROTTLE_MS) {
          fire();
          return;
        }
        // A trailing notification, so the last update in a burst is not the one left undisplayed —
        // which is the failure mode of a plain rate limit: the card stops on whatever value
        // happened to arrive at the start of the quiet period.
        if (pending == null) {
          pending = window.setTimeout(() => {
            pending = null;
            fire();
          }, THROTTLE_MS - since);
        }
      });

      const offSnapshot = store.onSnapshot(fire);

      return () => {
        offShipment();
        offSnapshot();
        if (pending != null) {
          window.clearTimeout(pending);
        }
      };
    },
    [store, shipmentId],
  );

  const read = useCallback(
    () => (shipmentId ? store.get(shipmentId) : undefined),
    [store, shipmentId],
  );

  return useSyncExternalStore(subscribe, read);
}

/**
 * The current time, re-read on an interval.
 *
 * Needed because "heard 40s ago" is a number that must keep climbing when *nothing* is happening,
 * and a component that reads the clock during render only updates it when something else caused a
 * render. A truck that goes silent stops causing renders — so without this the staleness counter
 * freezes at the exact moment it becomes the most interesting number on the card.
 */
export function useNow(intervalMs = 1_000): number {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);

  return now;
}
