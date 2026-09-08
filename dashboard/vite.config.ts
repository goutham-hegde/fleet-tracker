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
