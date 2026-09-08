/**
 * What is drawn under the trucks.
 *
 * <h2>Raster OpenStreetMap tiles, defined here rather than fetched as a style</h2>
 *
 * MapLibre is a renderer, not a map: it needs somewhere to get tiles from, and every good-looking
 * vector basemap is a hosted service behind an API key. Requiring a signup and a secret before the
 * dashboard would draw anything is a poor trade for a project that runs on one laptop, and the
 * alternative with no key at all — MapLibre's own demo tiles — is country outlines and nothing
 * else. A truck between Nagpur and Mumbai would sit on blank beige, which undercuts the whole
 * point of watching it move.
 *
 * So: standard OpenStreetMap raster tiles, with the style written out here as an object rather
 * than loaded from a URL. That means the map has exactly one external dependency at runtime, the
 * tile server itself, and no style fetch that can fail separately.
 *
 * <h2>The escape hatch</h2>
 *
 * `VITE_BASEMAP_STYLE` replaces all of this with a style URL, which is how a keyed vector style
 * gets used later without touching this file. M8 deploys this somewhere public, and OpenStreetMap
 * asks that its tile servers not be used for heavy or commercial traffic — that is the point at
 * which the variable earns its place.
 */
import type { StyleSpecification } from 'maplibre-gl';

/** Attribution is not decoration. OpenStreetMap's licence requires it to be visible. */
const OSM_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

const OSM_RASTER: StyleSpecification = {
  version: 8,
  sources: {
    osm: {
      type: 'raster',
      tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
      tileSize: 256,
      maxzoom: 19,
      attribution: OSM_ATTRIBUTION,
    },
  },
  layers: [
    {
      // A background under the tiles, so a tile that has not arrived yet is a neutral panel
      // rather than a transparent hole showing the page behind it.
      id: 'background',
      type: 'background',
      paint: { 'background-color': '#e9e5dd' },
    },
    {
      id: 'osm',
      type: 'raster',
      source: 'osm',
      paint: {
        // Slightly muted, because everything this dashboard has to say is drawn on top of it.
        // A full-strength street map competes with its own markers for attention.
        'raster-saturation': -0.35,
        'raster-brightness-min': 0.08,
      },
    },
  ],
};

/** The style MapLibre is handed: an override URL when one is configured, otherwise the tiles. */
export const BASEMAP: StyleSpecification | string =
  import.meta.env.VITE_BASEMAP_STYLE ?? OSM_RASTER;

/**
 * Where the map opens before anything is known.
 *
 * Central India at a zoom that holds all four seeded lanes — Delhi to Mumbai, Hyderabad to
 * Chennai, and the two that cross them. Only ever seen for the moment before the first snapshot
 * arrives, after which the view is fitted to the trucks that actually exist.
 */
export const INITIAL_VIEW = { center: [78.9, 21.5] as [number, number], zoom: 4.2 };
