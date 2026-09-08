/**
 * What the store does with a live update, pinned.
 *
 * This is the part of the dashboard a browser cannot check for you. A marker in the wrong place is
 * obvious on screen; a marker in the *right* place for the wrong reason is not — an arrival the
 * browser inferred from proximity rather than from the platform's announcement looks identical
 * until the day the two disagree, which is the day GPS noise puts a truck inside a fence it never
 * entered.
 *
 * So these tests are mostly about restraint: what the store declines to conclude. The map, the
 * layers and the MapLibre wiring are deliberately not tested here — that needs a real browser, and
 * the useful check on it is a person looking at trucks moving.
 */
import { describe, expect, it } from 'vitest';
import { FleetStore, movementAfterFix } from './FleetStore';
import type { LiveUpdate, ShipmentSummary } from '../api/types';

function summary(overrides: Partial<ShipmentSummary> = {}): ShipmentSummary {
  return {
    shipmentId: 'SHP-HYD-0002',
    vehicleId: 'VEH-0002',
    latitude: 17.4,
    longitude: 78.5,
    speedKph: 60,
    movement: 'MOVING',
    stopsCompleted: 1,
    stopsTotal: 4,
    ...overrides,
  };
}

function position(overrides: Record<string, unknown> = {}): LiveUpdate {
  return {
    type: 'position',
    shipmentId: 'SHP-HYD-0002',
    at: '2026-09-08T10:00:00Z',
    payload: { latitude: 17.5, longitude: 78.6, speedKph: 55, headingDegrees: 90, ...overrides },
  } as LiveUpdate;
}

describe('applying a position', () => {
  it('moves the truck and clears its staleness', () => {
    const store = new FleetStore();
    store.replaceSnapshot([summary({ staleSeconds: 300 })]);

    expect(store.apply(position())).toBe(true);

    const shipment = store.get('SHP-HYD-0002');
    expect(shipment?.latitude).toBe(17.5);
    expect(shipment?.longitude).toBe(78.6);
    expect(shipment?.staleSeconds).toBe(0);
    expect(store.updatesApplied).toBe(1);
  });

  it('ignores a shipment it has never seen in a snapshot', () => {
    // Rather than inventing a marker from a position alone, which would appear on the map with no
    // plan, no manifest and no incidents and stay that way until the next snapshot.
    const store = new FleetStore();
    expect(store.apply(position())).toBe(false);
    expect(store.size()).toBe(0);
  });

  it('leaves a truck at a stop at that stop, however fast the fix says it is going', () => {
    // The geofence state is the platform's conclusion and the browser holds none of the evidence
    // for it. A truck shunting around a yard at 20 km/h is still at the stop.
    const store = new FleetStore();
    store.replaceSnapshot([summary({ movement: 'AT_STOP' })]);

    store.apply(position({ speedKph: 20 }));

    expect(store.get('SHP-HYD-0002')?.movement).toBe('AT_STOP');
  });

  it('does not restart a delivered shipment on a straggling fix', () => {
    // The last fixes of a final leg routinely arrive after the arrival that concluded the
    // shipment -- different topics, different listener threads. The exception service had to
    // learn the same lesson about signal loss.
    const store = new FleetStore();
    store.replaceSnapshot([summary({ movement: 'DELIVERED' })]);

    store.apply(position({ speedKph: 60 }));

    expect(store.get('SHP-HYD-0002')?.movement).toBe('DELIVERED');
  });

  it('reads movement off the same threshold the server uses', () => {
    expect(movementAfterFix('MOVING', 5)).toBe('MOVING');
    expect(movementAfterFix('MOVING', 4.9)).toBe('STOPPED');
    expect(movementAfterFix('STOPPED', undefined)).toBe('STOPPED');
  });
});

describe('trails', () => {
  it('drops a repeated coordinate rather than filling the buffer with it', () => {
    // A parked truck reports the same coordinate for as long as it is parked. Keeping those would
    // push the whole trail out of the buffer, leaving nothing drawn behind it when it moves off.
    const store = new FleetStore();
    store.replaceSnapshot([summary()]);

    for (let i = 0; i < 10; i += 1) {
      store.apply(position({ latitude: 17.5, longitude: 78.6 }));
    }

    expect(store.trail('SHP-HYD-0002')).toEqual([
      [78.5, 17.4],
      [78.6, 17.5],
    ]);
  });

  it('keeps a trail across a snapshot refetch', () => {
    // The trail is this browser's own observation, not the server's. A poll is not a reason to
    // forget where a truck has just been.
    const store = new FleetStore();
    store.replaceSnapshot([summary()]);
    store.apply(position());

    store.replaceSnapshot([summary({ latitude: 17.6, longitude: 78.7 })]);

    expect(store.trail('SHP-HYD-0002')).toHaveLength(3);
  });

  it('forgets a shipment that has left the fleet', () => {
    const store = new FleetStore();
    store.replaceSnapshot([summary()]);
    store.replaceSnapshot([]);

    expect(store.size()).toBe(0);
    expect(store.trail('SHP-HYD-0002')).toEqual([]);
  });
});

describe('arrivals and departures', () => {
  it('parks the truck at the stop the platform announced', () => {
    const store = new FleetStore();
    store.replaceSnapshot([
      summary({
        nextStop: {
          stopId: 'hyd-dc',
          name: 'Hyderabad DC',
          seq: 2,
          latitude: 17.5,
          longitude: 78.6,
          radiusMeters: 400,
        },
      }),
    ]);

    store.apply({
      type: 'arrived',
      shipmentId: 'SHP-HYD-0002',
      at: '2026-09-08T10:05:00Z',
      payload: { stopId: 'hyd-dc', latitude: 17.5, longitude: 78.6 },
    });

    const shipment = store.get('SHP-HYD-0002');
    expect(shipment?.movement).toBe('AT_STOP');
    // The name is not on the event, so it comes from the stop the marker was heading for.
    expect(shipment?.atStop).toMatchObject({ stopId: 'hyd-dc', name: 'Hyderabad DC', seq: 2 });
    expect(shipment?.stopsCompleted).toBe(2);
  });

  it('never counts more stops completed than the plan has', () => {
    const store = new FleetStore();
    store.replaceSnapshot([summary({ stopsCompleted: 4, stopsTotal: 4 })]);

    store.apply({
      type: 'arrived',
      shipmentId: 'SHP-HYD-0002',
      at: '2026-09-08T10:05:00Z',
      payload: { stopId: 'hyd-dc', latitude: 17.5, longitude: 78.6 },
    });

    expect(store.get('SHP-HYD-0002')?.stopsCompleted).toBe(4);
  });

  it('releases the truck from its stop on a departure', () => {
    const store = new FleetStore();
    store.replaceSnapshot([
      summary({ movement: 'AT_STOP', atStop: { stopId: 'hyd-dc', seq: 2 } }),
    ]);

    store.apply({
      type: 'departed',
      shipmentId: 'SHP-HYD-0002',
      at: '2026-09-08T10:40:00Z',
      payload: { stopId: 'hyd-dc', latitude: 17.5, longitude: 78.6, dwellSeconds: 2100 },
    });

    const shipment = store.get('SHP-HYD-0002');
    expect(shipment?.atStop).toBeUndefined();
    // Stopped rather than moving: the departure says it left the fence, not that it is rolling.
    // The next fix settles that, and it is ten seconds away.
    expect(shipment?.movement).toBe('STOPPED');
  });
});

describe('estimates', () => {
  const withNextStop = summary({
    nextStop: {
      stopId: 'hyd-dc',
      seq: 2,
      latitude: 17.5,
      longitude: 78.6,
      radiusMeters: 400,
      estimatePending: 'NO_ESTIMATE_YET',
    },
  });

  it('takes an estimate for the stop the truck is heading for', () => {
    const store = new FleetStore();
    store.replaceSnapshot([withNextStop]);

    store.apply({
      type: 'estimate',
      shipmentId: 'SHP-HYD-0002',
      at: '2026-09-08T10:00:00Z',
      payload: {
        stopId: 'hyd-dc',
        estimatedArrival: '2026-09-08T11:30:00Z',
        remainingKm: 82.5,
        confidence: 0.9,
      },
    });

    expect(store.get('SHP-HYD-0002')?.nextStop).toMatchObject({
      estimatedArrival: '2026-09-08T11:30:00Z',
      remainingKm: 82.5,
      confidence: 0.9,
    });
    // The pending reason goes with it: there is an estimate now, so there is nothing to explain.
    expect(store.get('SHP-HYD-0002')?.nextStop?.estimatePending).toBeUndefined();
  });

  it('refuses an estimate for any other stop', () => {
    // It means this browser's idea of "next" is behind the platform's, and guessing which way
    // would put the label on a stop the truck has already cleared. The snapshot settles it.
    const store = new FleetStore();
    store.replaceSnapshot([withNextStop]);

    expect(
      store.apply({
        type: 'estimate',
        shipmentId: 'SHP-HYD-0002',
        at: '2026-09-08T10:00:00Z',
        payload: { stopId: 'blr-yard', estimatedArrival: '2026-09-08T11:30:00Z' },
      }),
    ).toBe(false);
    expect(store.get('SHP-HYD-0002')?.nextStop?.estimatedArrival).toBeUndefined();
  });
});

describe('exceptions', () => {
  const raised: LiveUpdate = {
    type: 'exception.raised',
    shipmentId: 'SHP-HYD-0002',
    at: '2026-09-08T10:00:00Z',
    payload: {
      exceptionId: 'inc-1',
      type: 'TEMPERATURE_EXCURSION',
      severity: 'CRITICAL',
      detail: '2.1 °C above the customer band for 22 minutes.',
    },
  };

  it('puts the worst open severity on the marker', () => {
    const store = new FleetStore();
    store.replaceSnapshot([summary()]);

    store.apply({
      ...raised,
      payload: { exceptionId: 'inc-0', type: 'ROUTE_DEVIATION', severity: 'WARNING' },
    } as LiveUpdate);
    store.apply(raised);

    const shipment = store.get('SHP-HYD-0002');
    expect(shipment?.openExceptions).toHaveLength(2);
    expect(shipment?.worstSeverity).toBe('CRITICAL');
  });

  it('does not show the same incident twice when its raise is republished', () => {
    // A raise republished after a restart is byte-identical by design, because the incident id is
    // derived from the condition's onset rather than minted per firing. A dashboard that appended
    // blindly would invent a second breach the platform never reported.
    const store = new FleetStore();
    store.replaceSnapshot([summary()]);

    store.apply(raised);
    store.apply(raised);

    expect(store.get('SHP-HYD-0002')?.openExceptions).toHaveLength(1);
  });

  it('strikes an incident off when it clears, by its incident id', () => {
    const store = new FleetStore();
    store.replaceSnapshot([summary()]);
    store.apply(raised);

    store.apply({
      type: 'exception.cleared',
      shipmentId: 'SHP-HYD-0002',
      at: '2026-09-08T10:40:00Z',
      payload: {
        exceptionId: 'inc-1',
        type: 'TEMPERATURE_EXCURSION',
        openForSeconds: 2400,
        resolution: 'BACK_IN_BAND',
      },
    });

    const shipment = store.get('SHP-HYD-0002');
    expect(shipment?.openExceptions).toBeUndefined();
    expect(shipment?.worstSeverity).toBeUndefined();
  });

  it('files a cleared incident with the raise and the clear merged', () => {
    // The raise carries the onset, the type and the severity; the clear carries the resolution and
    // the platform's own measure of how long the condition lasted. Only a client that saw both can
    // say "cold chain breach, forty minutes, back in band", and the incident id is what pairs them
    // — which is why the platform derives that id from the onset rather than minting one per
    // firing.
    const store = new FleetStore();
    store.replaceSnapshot([summary()]);
    store.apply(raised);

    store.apply({
      type: 'exception.cleared',
      shipmentId: 'SHP-HYD-0002',
      at: '2026-09-08T10:40:00Z',
      payload: {
        exceptionId: 'inc-1',
        type: 'TEMPERATURE_EXCURSION',
        openForSeconds: 2400,
        resolution: 'BACK_IN_BAND',
      },
    });

    const [resolved] = store.clearedIncidents();
    expect(resolved.exceptionId).toBe('inc-1');
    expect(resolved.state).toBe('CLEARED');
    expect(resolved.severity).toBe('CRITICAL');
    expect(resolved.onsetAt).toBe('2026-09-08T10:00:00Z');
    expect(resolved.clearedAt).toBe('2026-09-08T10:40:00Z');
    expect(resolved.openForSeconds).toBe(2400);
    expect(resolved.detail).toContain('above the customer band');
    // And it is no longer counted as a problem.
    expect(store.openIncidents()).toHaveLength(0);
  });

  it('does not record the same resolution twice when a clear is republished', () => {
    const store = new FleetStore();
    store.replaceSnapshot([summary()]);
    store.apply(raised);

    const cleared: LiveUpdate = {
      type: 'exception.cleared',
      shipmentId: 'SHP-HYD-0002',
      at: '2026-09-08T10:40:00Z',
      payload: { exceptionId: 'inc-1', type: 'TEMPERATURE_EXCURSION', openForSeconds: 2400 },
    };
    store.apply(cleared);
    store.apply(cleared);

    expect(store.clearedIncidents()).toHaveLength(1);
  });

  it('lists open incidents across the whole fleet, worst first', () => {
    const store = new FleetStore();
    store.replaceSnapshot([
      summary(),
      summary({ shipmentId: 'SHP-DEL-0007', vehicleId: 'VEH-0007' }),
    ]);

    store.apply({
      ...raised,
      shipmentId: 'SHP-DEL-0007',
      payload: { exceptionId: 'inc-2', type: 'UNPLANNED_STOP', severity: 'WARNING' },
    } as LiveUpdate);
    store.apply(raised);

    const open = store.openIncidents();
    expect(open.map((incident) => incident.exceptionId)).toEqual(['inc-1', 'inc-2']);
    // The shipment id travels with the incident, which is what lets a fleet-wide row be clicked.
    expect(open[1].shipmentId).toBe('SHP-DEL-0007');
  });

  it('leaves the open list alone when a clear names an incident it never saw raised', () => {
    // Reachable two ways: a breach that was raised and cleared inside one snapshot interval, and a
    // clear republished after a restart. Neither may disturb what is still open — but the clear is
    // real news from the platform, so it is still filed as a resolution rather than dropped.
    const store = new FleetStore();
    store.replaceSnapshot([summary()]);
    store.apply(raised);

    store.apply({
      type: 'exception.cleared',
      shipmentId: 'SHP-HYD-0002',
      at: '2026-09-08T10:40:00Z',
      payload: { exceptionId: 'inc-someone-else', type: 'SIGNAL_LOSS' },
    });

    expect(store.get('SHP-HYD-0002')?.openExceptions).toHaveLength(1);
    expect(store.clearedIncidents().map((incident) => incident.exceptionId)).toEqual([
      'inc-someone-else',
    ]);
  });
});

describe('subscribers', () => {
  it('tells listeners which shipment changed, and only when one did', () => {
    const store = new FleetStore();
    store.replaceSnapshot([summary()]);

    const changed: string[] = [];
    store.onShipment((shipmentId) => changed.push(shipmentId));

    store.apply(position());
    // An update for an unknown shipment must not wake the map.
    store.apply({ ...position(), shipmentId: 'SHP-NOBODY-9999' } as LiveUpdate);

    expect(changed).toEqual(['SHP-HYD-0002']);
  });

  it('replaces the shipment object rather than mutating it', () => {
    // `useSyncExternalStore` compares snapshots by identity, so a shipment updated in place would
    // look unchanged to React for ever and the card would never re-render.
    const store = new FleetStore();
    store.replaceSnapshot([summary()]);
    const before = store.get('SHP-HYD-0002');

    store.apply(position());

    expect(store.get('SHP-HYD-0002')).not.toBe(before);
  });
});
