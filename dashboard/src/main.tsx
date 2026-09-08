/**
 * Where the page starts.
 *
 * `StrictMode` is kept even though it double-invokes effects in development, which for this app
 * means the stream is opened, closed and opened again on every mount. That is not a nuisance to be
 * switched off — it is the reconnection path being exercised on every reload, and this dashboard's
 * correctness depends on that path working: the stream carries no history, so a client that
 * reconnects without re-fetching the snapshot silently keeps drawing trucks where they were.
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import App from './App.tsx';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
