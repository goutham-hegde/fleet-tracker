/**
 * What the markers mean.
 *
 * Worth its space because a marker on this map carries four independent facts — colour for what
 * the truck is doing, a ring for the worst thing wrong with it, a fade for how long ago it was
 * heard from, and rotation for where it is pointing. Four facts on one dot is what makes a fleet
 * readable at a glance, and unreadable without a key.
 *
 * The colours are not a scale. `STOPPED` and `AT_STOP` are the same speed and completely different
 * situations, which is the distinction the platform draws with its geofence state rather than with
 * a threshold, and the legend says so in words.
 */
export function Legend() {
  return (
    <aside className="legend">
      <h2>Legend</h2>
      <ul className="legend-list">
        <li>
          <span className="swatch movement-moving" />
          <span>
            <strong>moving</strong> — above 5 km/h
          </span>
        </li>
        <li>
          <span className="swatch movement-at_stop" />
          <span>
            <strong>at a stop</strong> — inside a geofence, arrival announced
          </span>
        </li>
        <li>
          <span className="swatch movement-stopped" />
          <span>
            <strong>stopped</strong> — stationary, and not at any planned stop
          </span>
        </li>
        <li>
          <span className="swatch movement-delivered" />
          <span>
            <strong>delivered</strong> — every stop in the plan reached
          </span>
        </li>
        <li>
          <span className="swatch swatch-ring severity-critical" />
          <span>
            <strong>ring</strong> — an open SLA exception, amber for a warning and red for critical
          </span>
        </li>
        <li>
          <span className="swatch swatch-stale" />
          <span>
            <strong>faded</strong> — nothing heard for ten minutes. Old, not wrong
          </span>
        </li>
      </ul>
      <p className="legend-note">
        Click a truck for its plan: dashed line is the route as booked, filled circles are geofences,
        solid line is where it has actually been.
      </p>
    </aside>
  );
}
