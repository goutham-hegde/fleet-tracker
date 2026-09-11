import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

/**
 * Two ports, and both of them are already spoken for elsewhere in this project.
 *
 * The dev server keeps Vite's default 5173, because that exact origin is already in the dashboard
 * API's configured CORS list — the API deliberately does not wildcard its origins, so a browser
 * calling it from anywhere else is refused by the browser after the request has already succeeded
 * on the server, which is a confusing enough failure to be worth avoiding by convention.
 *
 * `preview` serves the built assets on 18080, the port this project's README has reserved for the
 * dashboard since M0. That is what makes `npm run build && npm run preview` a rehearsal of the
 * deployment rather than a different thing that happens to work.
 */
export default defineConfig({
  plugins: [react()],

  /*
   * MapLibre must not be pre-bundled, and this line is the difference between a map and a blank
   * panel in development.
   *
   * Vite speeds up the dev server by rewriting dependencies into `node_modules/.vite/deps/`. That
   * breaks MapLibre, which starts its own web worker with `new Worker(new URL(...,
   * import.meta.url))`: the URL resolves relative to the rewritten file, and the worker's own
   * module was never copied there. The result is a single failed request for
   * `.vite/deps/maplibre-gl-worker.mjs`, after which every map tile is cancelled -- so the basemap
   * never appears while the browser console shows nothing but aborted tile requests, which points
   * at the network rather than at the bundler. Excluding it serves the library straight from
   * `node_modules`, where the worker sits next to the code that asks for it.
   */
  optimizeDeps: {
    exclude: ['maplibre-gl'],
  },

  /*
   * MapLibre's worker is bundled as an ES module, not the default IIFE. MapLibre starts it as a
   * module worker (falling back to a classic one only if the browser refuses), and its code uses
   * module syntax that an IIFE bundle cannot carry. See src/map/worker.ts for why the worker is
   * bundled at all.
   */
  worker: {
    format: 'es',
  },
  server: {
    port: 5173,
    // Fail rather than silently moving to 5174, which would be an origin the API rejects.
    strictPort: true,
  },
  preview: {
    port: 18080,
    strictPort: true,
  },
});
