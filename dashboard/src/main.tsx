/**
 * Where the page starts.
 *
 * `StrictMode` is kept even though it double-invokes effects in development, which for this app
 * means the stream is opened, closed and opened again on every mount. That is not a nuisance to be
 * switched off — it is the reconnection path being exercised on every reload, and this dashboard's
 * correctness depends on that path working: the stream carries no history, so a client that
 * reconnects without re-fetching the snapshot silently keeps drawing trucks where they were. It is
 * also why the API reports two viewers for one open tab in development.
 *
 * <h2>The stylesheet order below is load-bearing</h2>
 *
 * MapLibre's stylesheet must be imported *before* ours, because MapLibre puts a
 * `.maplibregl-map` class on whatever element it is given and that class sets
 * `position: relative`. Our own rule for the same element sets `position: absolute; inset: 0` to
 * fill the page. Both are single-class selectors, so they have identical specificity and the
 * later one simply wins — with the imports the other way round, the map's own rule won, `inset`
 * was ignored, and the map collapsed to a third of the window height with the rest of the page
 * left blank. Nothing warns about this: it is two valid rules, applied in the order they arrived.
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import 'maplibre-gl/dist/maplibre-gl.css';
import './index.css';
import App from './App.tsx';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
