/**
 * The map itself: markers that move, trails behind them, and the selected shipment's plan.
 *
 * <h2>React does not draw anything here</h2>
 *
 * MapLibre owns its canvas and puts its markers in the DOM itself, so this component renders one
 * empty `div` and then never re-renders for anything the trucks do. Movement arrives through the
 * store's subscriptions and is applied imperatively — `marker.setLngLat`, `source.setData` — which
 * is both what MapLibre is built for and the only way a fleet reporting twice a second per truck
 * stays smooth. The React props that do matter are the ones a person changes: which shipment is
 * selected, and the detail that was fetched for it.
 *
 * <h2>Two kinds of drawing, for two different reasons</h2>
 *
 * Trucks and stops are DOM markers, because they carry labels and a label needs text — and text in
 * a MapLibre layer needs a glyph server, which a raster basemap does not have. Trails, routes and
 * geofences are GeoJSON layers, because there is one of each per shipment and a DOM element per
 * vertex would be thousands of nodes.
 */
import { useEffect, useRef } from 'react';
import type { FeatureCollection, LineString, Polygon } from 'geojson';
import {
  LngLatBounds,
  Map as MapLibreMap,
  Marker,
  NavigationControl,
  ScaleControl,
  type GeoJSONSource,
} from 'maplibre-gl';
// MapLibre's own stylesheet is imported in main.tsx, deliberately before ours: it styles the
// element we hand it, and whichever stylesheet arrives last wins. See the note there.
import type { ShipmentDetail } from '../api/types';
import type { FleetShipment, FleetStore, LngLat } from '../fleet/FleetStore';
import { BASEMAP, INITIAL_VIEW } from './basemap';
// Before any map exists: tells MapLibre where its worker is. See worker.ts for why this is needed.
import './worker';

/** Below this zoom, every truck's label is hidden — sixty-four of them overlap into a smear. */
const LABEL_ZOOM = 5.6;

interface FleetMapProps {
  store: FleetStore;
  selected: string | null;
  detail: ShipmentDetail | null;
  /**
   * Whether finished loads are drawn.
   *
   * A delivered shipment keeps its last position for ever, so a run that repeats routes stacks
   * completed markers on the depots it finished at — twenty minutes of one left twenty-one of them
   * against three moving trucks. Every one is a real shipment with a real last position, so this
   * is a question about what a *live* view is for rather than a defect, and it is answered by
   * letting the viewer decide rather than by dropping data.
   */
  showDelivered: boolean;
  onSelect: (shipmentId: string | null) => void;
}

export function FleetMap({ store, selected, detail, showDelivered, onSelect }: FleetMapProps) {
  const container = useRef<HTMLDivElement | null>(null);
  const map = useRef<MapLibreMap | null>(null);
  const ready = useRef(false);

  const truckMarkers = useRef(new Map<string, Marker>());
  const stopMarkers = useRef<Marker[]>([]);

  // Selection is read inside callbacks that are registered once, so it is mirrored into a ref.
  // Reading the prop directly would close over its value at the moment the subscription was made
  // and highlight whatever was selected then, for ever.
  const selectedRef = useRef<string | null>(selected);
  const fitted = useRef(false);
  const trailFrame = useRef<number | null>(null);

  // Mirrored for the same reason selection is: the marker styling runs inside subscriptions that
  // are registered once and would otherwise close over the value this prop had at that moment.
  const showDeliveredRef = useRef(showDelivered);

  // -- the map, created once ------------------------------------------------

  useEffect(() => {
    if (!container.current) {
      return;
    }

    const instance = new MapLibreMap({
      container: container.current,
      style: BASEMAP,
      center: INITIAL_VIEW.center,
      zoom: INITIAL_VIEW.zoom,
      attributionControl: { compact: true },
    });
    map.current = instance;

    instance.addControl(new NavigationControl({ showCompass: false }), 'top-right');
    instance.addControl(new ScaleControl({ unit: 'metric' }), 'bottom-left');

    instance.on('load', () => {
      addLayers(instance);
      ready.current = true;
      syncAll();
      drawTrails();
      drawSelection();
    });

    // Clicking the basemap rather than a truck clears the selection. Without this the only way
    // out of a selected shipment is to find and click the same marker again, which on a moving
    // map is a game rather than a control.
    instance.on('click', () => onSelect(null));

    // Captured into locals so the cleanup below does not read `.current` off a ref that may have
    // been repointed by the time React runs it. Both are stable for the life of this component,
    // but reaching through a ref in a cleanup is the pattern that is wrong often enough to be
    // worth not writing at all.
    const element = container.current;
    const markers = truckMarkers.current;

    const applyLabelVisibility = () => {
      element.classList.toggle('labels-hidden', instance.getZoom() < LABEL_ZOOM);
    };
    instance.on('zoom', applyLabelVisibility);
    applyLabelVisibility();

    return () => {
      ready.current = false;
      instance.remove();
      map.current = null;
      markers.clear();
      stopMarkers.current = [];
    };
    // Created once for the life of the page. `onSelect` is stable in the one caller there is, and
    // re-running this effect would tear down and rebuild the whole map on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // -- the fleet, subscribed once -------------------------------------------

  useEffect(() => {
    const offShipment = store.onShipment((shipmentId) => {
      const shipment = store.get(shipmentId);
      if (shipment) {
        upsertMarker(shipmentId, shipment);
      }
      scheduleTrails();
    });

    const offSnapshot = store.onSnapshot(() => {
      syncAll();
      scheduleTrails();
      fitToFleetOnce();
    });

    return () => {
      offShipment();
      offSnapshot();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [store]);

  // -- selection ------------------------------------------------------------

  useEffect(() => {
    selectedRef.current = selected;
    // Re-styled in full rather than only toggling the selected class: a delivered load picked from
    // the exceptions panel is hidden by the filter, and selecting it has to bring it back.
    syncAll();
    drawTrails();

    // Ease to the truck once, when it is picked — not on every update it then sends. A map that
    // re-centres on each fix cannot be panned away from, which makes looking at anything else
    // impossible while a shipment is open.
    const shipment = selected ? store.get(selected) : undefined;
    if (shipment && map.current) {
      map.current.easeTo({
        center: [shipment.longitude, shipment.latitude],
        zoom: Math.max(map.current.getZoom(), 6.5),
        duration: 800,
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected]);

  useEffect(() => {
    drawSelection();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail, selected]);

  useEffect(() => {
    showDeliveredRef.current = showDelivered;
    syncAll();
    drawTrails();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showDelivered]);

  // -- drawing --------------------------------------------------------------

  function syncAll(): void {
    const live = new Set<string>();
    for (const shipment of store.all()) {
      live.add(shipment.shipmentId);
      upsertMarker(shipment.shipmentId, shipment);
    }
    for (const [shipmentId, marker] of truckMarkers.current) {
      if (!live.has(shipmentId)) {
        marker.remove();
        truckMarkers.current.delete(shipmentId);
      }
    }
  }

  function upsertMarker(shipmentId: string, shipment: FleetShipment): void {
    if (!map.current) {
      return;
    }
    let marker = truckMarkers.current.get(shipmentId);
    if (!marker) {
      const element = truckElement(shipmentId);
      element.addEventListener('click', (event) => {
        // Without this the map's own click handler fires straight afterwards and clears the
        // selection this click just made.
        event.stopPropagation();
        onSelect(shipmentId);
      });
      marker = new Marker({ element, anchor: 'center' })
        .setLngLat([shipment.longitude, shipment.latitude])
        .addTo(map.current);
      truckMarkers.current.set(shipmentId, marker);
    } else {
      marker.setLngLat([shipment.longitude, shipment.latitude]);
    }
    styleTruck(
      marker.getElement(),
      shipmentId,
      shipment,
      selectedRef.current === shipmentId,
      isDrawn(shipment, shipmentId),
    );
  }

  /**
   * Coalesce trail redraws to one per animation frame.
   *
   * Sixty-four trucks reporting twice a second would otherwise rebuild and re-upload the whole
   * trail collection well over a hundred times a second, to produce at most sixty frames.
   */
  function scheduleTrails(): void {
    if (trailFrame.current != null) {
      return;
    }
    trailFrame.current = requestAnimationFrame(() => {
      trailFrame.current = null;
      drawTrails();
    });
  }

  function drawTrails(): void {
    const source = sourceOf(map.current, ready.current, 'trails');
    if (!source) {
      return;
    }
    const features: FeatureCollection<LineString>['features'] = [];
    for (const shipment of store.all()) {
      const trail: LngLat[] = store.trail(shipment.shipmentId);
      if (trail.length < 2 || !isDrawn(shipment, shipment.shipmentId)) {
        continue;
      }
      features.push({
        type: 'Feature',
        properties: {
          shipmentId: shipment.shipmentId,
          selected: shipment.shipmentId === selectedRef.current ? 1 : 0,
        },
        geometry: { type: 'LineString', coordinates: trail },
      });
    }
    source.setData({ type: 'FeatureCollection', features });
  }

  /** The selected shipment's plan: its route, its stops, its geofences and where it has been. */
  function drawSelection(): void {
    const route = sourceOf(map.current, ready.current, 'route');
    const fences = sourceOf(map.current, ready.current, 'fences');
    const history = sourceOf(map.current, ready.current, 'history');
    if (!route || !fences || !history || !map.current) {
      return;
    }

    for (const marker of stopMarkers.current) {
      marker.remove();
    }
    stopMarkers.current = [];

    if (!detail || detail.summary.shipmentId !== selected) {
      route.setData(empty<LineString>());
      fences.setData(empty<Polygon>());
      history.setData(empty<LineString>());
      return;
    }

    const stops = [...(detail.stops ?? [])].sort((a, b) => a.seq - b.seq);

    route.setData({
      type: 'FeatureCollection',
      features:
        stops.length < 2
          ? []
          : [
              {
                type: 'Feature',
                properties: {},
                geometry: {
                  type: 'LineString',
                  coordinates: stops.map((stop) => [stop.longitude, stop.latitude]),
                },
              },
            ],
    });

    fences.setData({
      type: 'FeatureCollection',
      features: stops.map((stop) => ({
        type: 'Feature' as const,
        properties: { status: stop.status },
        geometry: circle(stop.longitude, stop.latitude, stop.radiusMeters),
      })),
    });

    const track = detail.track ?? [];
    history.setData({
      type: 'FeatureCollection',
      features:
        track.length < 2
          ? []
          : [
              {
                type: 'Feature',
                properties: {},
                geometry: {
                  type: 'LineString',
                  coordinates: track.map((point) => [point.longitude, point.latitude]),
                },
              },
            ],
    });

    for (const stop of stops) {
      const element = document.createElement('div');
      element.className = `stop stop-${stop.status.toLowerCase()}`;
      element.innerHTML =
        `<span class="stop-dot">${stop.seq}</span>` +
        `<span class="stop-label">${escapeHtml(stop.name ?? stop.stopId)}</span>`;
      element.title = `${stop.stopId} · ${stop.status.toLowerCase()}`;
      stopMarkers.current.push(
        new Marker({ element, anchor: 'center' })
          .setLngLat([stop.longitude, stop.latitude])
          .addTo(map.current),
      );
    }
  }

  /**
   * Fit the view to the fleet, once.
   *
   * Only the first time trucks appear. Re-fitting on every snapshot would pull the view back out
   * every twenty seconds, undoing whatever the person watching had just zoomed into.
   */
  /**
   * Whether this shipment appears on the map at all.
   *
   * A selected shipment is always drawn, whatever the filter says. Hiding the truck somebody just
   * clicked — from the exceptions panel, say, which can name a load that has since been delivered
   * — would open a card describing a marker that is not there.
   */
  function isDrawn(shipment: FleetShipment, shipmentId: string): boolean {
    if (showDeliveredRef.current || shipment.movement !== 'DELIVERED') {
      return true;
    }
    return selectedRef.current === shipmentId;
  }

  function fitToFleetOnce(): void {
    if (fitted.current || !map.current || store.size() === 0) {
      return;
    }
    const bounds = new LngLatBounds();
    for (const shipment of store.all()) {
      bounds.extend([shipment.longitude, shipment.latitude]);
    }
    map.current.fitBounds(bounds, { padding: 80, maxZoom: 9, duration: 600 });
    fitted.current = true;
  }

  return <div className="fleet-map" ref={container} />;
}

// ---------------------------------------------------------------------------
// Layers and markers
// ---------------------------------------------------------------------------

/**
 * Everything drawn over the basemap, added once when the style has loaded.
 *
 * Order is the draw order: geofences under routes, routes under history, history under trails.
 * Sources are added empty and filled later, because a layer referring to a source that does not
 * exist yet is an error, and adding both together on every selection change would mean removing
 * and re-adding layers for a data update.
 */
function addLayers(instance: MapLibreMap): void {
  instance.addSource('fences', { type: 'geojson', data: empty<Polygon>() });
  instance.addSource('route', { type: 'geojson', data: empty<LineString>() });
  instance.addSource('history', { type: 'geojson', data: empty<LineString>() });
  instance.addSource('trails', { type: 'geojson', data: empty<LineString>() });

  instance.addLayer({
    id: 'fences-fill',
    type: 'fill',
    source: 'fences',
    paint: {
      'fill-color': [
        'match',
        ['get', 'status'],
        'DEPARTED',
        '#94a3b8',
        'AT',
        '#22c55e',
        '#2563eb',
      ],
      'fill-opacity': 0.15,
    },
  });

  instance.addLayer({
    id: 'route-line',
    type: 'line',
    source: 'route',
    paint: {
      'line-color': '#1d4ed8',
      'line-width': 2,
      'line-opacity': 0.55,
      // Dashed, because this is the plan rather than the road. A solid line here would read as
      // "the truck drove this", which is exactly what the platform does not know: the planned
      // path is straight lines between stops and a real highway is not.
      'line-dasharray': [2, 2],
    },
  });

  instance.addLayer({
    id: 'history-line',
    type: 'line',
    source: 'history',
    paint: { 'line-color': '#0f172a', 'line-width': 2, 'line-opacity': 0.45 },
  });

  instance.addLayer({
    id: 'trail-line',
    type: 'line',
    source: 'trails',
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
      'line-color': ['case', ['==', ['get', 'selected'], 1], '#f97316', '#0ea5e9'],
      'line-width': ['case', ['==', ['get', 'selected'], 1], 3.5, 2],
      'line-opacity': 0.8,
    },
  });
}

/** The DOM element behind one truck marker: an arrow that points where it is going, and a label. */
function truckElement(shipmentId: string): HTMLDivElement {
  const element = document.createElement('div');
  // Added, not assigned — the same rule as styleTruck. Nothing is lost here today because this
  // runs before the marker is attached, but the two must not disagree about the rule.
  element.classList.add('truck');
  element.innerHTML =
    '<span class="truck-arrow"></span>' +
    `<span class="truck-label">${escapeHtml(shortId(shipmentId))}</span>`;
  return element;
}

/**
 * Apply everything the marker's appearance says about the truck.
 *
 * Movement is the colour, the worst open incident is the ring, staleness is the fade, and heading
 * is the rotation — four independent facts on one marker, which is what makes a fleet legible at a
 * glance rather than a field of identical dots.
 */
function styleTruck(
  element: HTMLElement,
  shipmentId: string,
  shipment: FleetShipment,
  isSelected: boolean,
  isDrawn: boolean,
): void {
  const movement = shipment.movement ?? 'STOPPED';
  const severity = shipment.worstSeverity;

  // `classList`, never `className`. MapLibre adds `maplibregl-marker` to the element it was
  // handed, and that class is what positions a marker absolutely. Assigning `className` wholesale
  // removed it, so every marker dropped back into normal document flow — while MapLibre carried on
  // writing an inline transform for the coordinate, which then offset it from wherever it had
  // landed in that flow. The result was a plausible map that was wrong: markers appeared at
  // roughly the right longitude, stacked in a tidy sixteen-pixel column, so five trucks parked at
  // one depot read as five trucks strung out down a road. Nothing errored, and the lie was regular
  // enough to look deliberate.
  for (const name of MOVEMENT_CLASSES) {
    element.classList.toggle(name, name === `movement-${movement.toLowerCase()}`);
  }
  for (const name of SEVERITY_CLASSES) {
    element.classList.toggle(
      name,
      severity != null && name === `severity-${severity.toLowerCase()}`,
    );
  }
  element.classList.toggle('is-selected', isSelected);
  // Hidden with a class rather than by removing the marker: the shipment is still in the store,
  // still receiving positions, and still counted in the status bar. Only the drawing is suppressed,
  // so switching the filter back on costs a class toggle rather than sixty-four marker rebuilds.
  element.classList.toggle('is-filtered', !isDrawn);

  // Ten minutes of the browser's own elapsed time with no news. Faded rather than hidden: a
  // position is not wrong because it is old, it is just old, and a marker that disappeared would
  // leave a viewer wondering where the truck went.
  element.classList.toggle('is-stale', Date.now() - shipment.lastSeenWall > 600_000);

  const arrow = element.querySelector<HTMLElement>('.truck-arrow');
  if (arrow) {
    // Rotating the inner element rather than the marker: MapLibre owns the outer element's
    // transform for positioning, and writing a rotation into it is overwritten on the next move.
    arrow.style.transform = `rotate(${shipment.headingDegrees ?? 0}deg)`;
  }

  element.title =
    `${shipmentId} · ${movement.toLowerCase().replace('_', ' ')}` +
    (shipment.speedKph != null ? ` · ${Math.round(shipment.speedKph)} km/h` : '') +
    (severity ? ` · ${severity.toLowerCase()}` : '');
}

/** Every movement class, so the one that applies is set by toggling rather than by replacing. */
const MOVEMENT_CLASSES = [
  'movement-moving',
  'movement-stopped',
  'movement-at_stop',
  'movement-delivered',
] as const;

/** The same for severity. A marker has at most one of these and usually none. */
const SEVERITY_CLASSES = ['severity-info', 'severity-warning', 'severity-critical'] as const;

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

/** An empty collection, for a source that has nothing to show yet. */
function empty<T extends LineString | Polygon>(): FeatureCollection<T> {
  return { type: 'FeatureCollection', features: [] };
}

/** The source by that name, or nothing if the style has not finished loading. */
function sourceOf(
  instance: MapLibreMap | null,
  isReady: boolean,
  id: string,
): GeoJSONSource | undefined {
  if (!instance || !isReady) {
    return undefined;
  }
  return instance.getSource(id) as GeoJSONSource | undefined;
}

/**
 * A geofence, as a polygon.
 *
 * MapLibre has no circle-in-metres primitive — its circle layer is sized in screen pixels, which
 * would draw a four-hundred-metre yard and a hundred-and-twenty-metre kerbside dock at exactly the
 * same size. Since the difference between those two radii is the whole reason the platform stores
 * a radius per stop, the circle is built as geometry instead. Thirty-two points is smooth enough
 * at any zoom where a geofence is visible at all.
 */
function circle(longitude: number, latitude: number, radiusMeters: number): Polygon {
  const points = 32;
  const latitudeDelta = radiusMeters / 111_320;
  const longitudeDelta = latitudeDelta / Math.cos((latitude * Math.PI) / 180);
  const ring: [number, number][] = [];
  for (let index = 0; index <= points; index += 1) {
    const angle = (index / points) * 2 * Math.PI;
    ring.push([
      longitude + longitudeDelta * Math.cos(angle),
      latitude + latitudeDelta * Math.sin(angle),
    ]);
  }
  return { type: 'Polygon', coordinates: [ring] };
}

/** `SHP-HYD-0002` becomes `HYD-0002`: enough to tell trucks apart without labelling the map twice. */
function shortId(shipmentId: string): string {
  return shipmentId.replace(/^SHP-/, '');
}

/** These strings come from reference data, but they are still going into `innerHTML`. */
function escapeHtml(value: string): string {
  return value.replace(
    /[&<>"']/g,
    (character) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character] ?? character,
  );
}
