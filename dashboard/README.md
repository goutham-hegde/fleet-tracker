# Dashboard

The live map: React, Vite, TypeScript and MapLibre GL against the dashboard API on port 18083.

```bash
npm install
npm run dev     # http://localhost:5173 -- start the platform first
npm test        # the store's update logic; no browser needed
npm run build && npm run preview   # built assets on http://localhost:18080
```

Nothing needs configuring; `.env.example` documents the two variables that exist. **Keep the dev
server on 5173** — the API names that origin explicitly rather than allowing any, so a dev server
that moved ports would produce a request the server answers happily and the browser then refuses to
hand to the page.

Three files carry the design:

| File | Holds |
|---|---|
| `src/api/types.ts` | The API's wire format, written down. Hand-kept against the Java records |
| `src/fleet/FleetStore.ts` | What the browser believes about the fleet, and what one live update may change |
| `src/map/FleetMap.tsx` | MapLibre, driven imperatively: markers, trails, routes, geofences |

The page loads a snapshot, follows the live stream, and re-fetches the snapshot every twenty seconds
and on every reconnection — the stream carries no history, so a client that was disconnected has
missed exactly the updates it can no longer ask for.

What the markers mean, how a click works, and why this is a separate service from everything else
are in the [project README](../README.md#dashboard).
