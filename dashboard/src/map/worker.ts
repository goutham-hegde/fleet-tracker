/**
 * Where MapLibre's web worker comes from, stated rather than left for MapLibre to work out.
 *
 * MapLibre does its heavy lifting off the main thread: vector tiles are parsed in a web worker, and
 * so is every GeoJSON source, which on this map means the routes, the geofences and the trails. It
 * finds its worker by building a URL at runtime, next to its own module:
 *
 *     new URL(`./${'maplibre-gl-worker.mjs'}`, import.meta.url)
 *
 * <h2>Why that fails in a production build, silently</h2>
 *
 * Vite copies a file into the build only when it can see a static `new URL('literal',
 * import.meta.url)`. A name assembled from a template string is invisible to it, so the worker is
 * never emitted, and the built page asks for `/assets/maplibre-gl-worker.mjs` and gets a 404. Nothing
 * on screen says so. Raster tiles decode on the main thread and the trucks are DOM markers, so the
 * map looks nearly right: it simply never draws a route, a geofence, a trail or a vector basemap.
 *
 * The development server hid this from S15 onwards, because `optimizeDeps.exclude` serves MapLibre
 * straight out of `node_modules`, where the worker does sit next to it. Every production build had it
 * (`npm run preview`, the nginx image in the cluster since S17) until S22 put a vector basemap on
 * the public build and a browser showed the missing roads.
 *
 * <h2>The fix</h2>
 *
 * `?worker&url` makes Vite bundle the worker as an entry of its own, together with what it imports
 * (MapLibre's shared chunk, which the worker cannot run without), and hands back its URL. MapLibre
 * is then told to use that URL. It works in both modes: in development Vite serves the worker module
 * from `node_modules` exactly as before.
 *
 * Imported for its side effect, before any map is created. A worker already started is not moved.
 */
import { setWorkerUrl } from 'maplibre-gl';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';

setWorkerUrl(workerUrl);
