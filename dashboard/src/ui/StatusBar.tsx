/**
 * The strip along the top: whether this dashboard is actually seeing anything.
 *
 * Every consumer in this platform logs a heartbeat for the same reason this exists. A count of
 * zero is the difference between "nothing is happening" and "this is pointed at the wrong
 * database", and telling those apart has cost this project a whole session before. So the bar
 * carries three separate facts rather than one indicator light: whether the stream is connected,
 * how many trucks the API says it can see, and how long it has been since anything moved.
 *
 * A quiet fleet is not an error. A run that has finished, or a simulator that is not running, is
 * silence — and it is reported as silence rather than dressed as a fault.
 */
import type { FleetStatus } from '../fleet/useFleet';
import { ago } from './format';

const STREAM_LABEL: Record<FleetStatus['streamState'], string> = {
  connecting: 'connecting',
  open: 'live',
  reconnecting: 'reconnecting',
};

interface StatusBarProps {
  status: FleetStatus;
  showDelivered: boolean;
  onToggleDelivered: () => void;
  onRefresh: () => void;
}

export function StatusBar({ status, showDelivered, onToggleDelivered, onRefresh }: StatusBarProps) {
  const { byMovement } = status;

  return (
    <header className="statusbar">
      <div className="statusbar-title">
        <strong>Fleet tracking</strong>
        <span className="statusbar-sub">live map</span>
      </div>

      <div className={`pill pill-stream stream-${status.streamState}`}>
        <span className="pill-dot" />
        {STREAM_LABEL[status.streamState]}
      </div>

      <dl className="statusbar-stats">
        <div>
          <dt>shipments</dt>
          <dd>{status.shipments}</dd>
        </div>
        <div>
          <dt>moving</dt>
          <dd>{byMovement.MOVING}</dd>
        </div>
        <div>
          <dt>at a stop</dt>
          <dd>{byMovement.AT_STOP}</dd>
        </div>
        <div>
          <dt>stopped</dt>
          <dd>{byMovement.STOPPED}</dd>
        </div>
        <div>
          <dt>delivered</dt>
          <dd>{byMovement.DELIVERED}</dd>
        </div>
        <div className={status.openIncidents > 0 ? 'is-alerting' : ''}>
          <dt>open exceptions</dt>
          <dd>
            {status.openIncidents}
            {status.criticalIncidents > 0 ? ` (${status.criticalIncidents} critical)` : ''}
          </dd>
        </div>
        <div>
          <dt>last update</dt>
          <dd>{status.quietForSeconds == null ? 'none yet' : `${ago(status.quietForSeconds)} ago`}</dd>
        </div>
        <div>
          <dt>updates applied</dt>
          <dd>{status.updatesApplied}</dd>
        </div>
      </dl>

      <div className="statusbar-right">
        {/* The count is here whether or not the markers are, so the filter never hides the fact
            that something is being hidden. A control that quietly removes trucks from a fleet map
            is the same class of problem as a dashboard pointed at the wrong database. */}
        <button
          type="button"
          className={showDelivered ? 'button button-on' : 'button'}
          onClick={onToggleDelivered}
          aria-pressed={showDelivered}
          title="Delivered loads keep their last position for ever, so a long run stacks them on the depots they finished at"
        >
          {showDelivered ? 'Hide' : 'Show'} delivered ({byMovement.DELIVERED})
        </button>
        {status.error ? (
          <span className="statusbar-error" title={status.error}>
            API unreachable — {status.error}
          </span>
        ) : status.shipments === 0 && status.meta?.trackedShipments === 0 ? (
          // The specific empty case worth naming. The API answered, and it has nothing: no
          // simulator running, or a database with no positions in it. Not a fault, and not the
          // same thing as a failed fetch.
          <span className="statusbar-empty">
            API reachable, no shipments reporting — start the simulator
          </span>
        ) : null}
        <button type="button" className="button" onClick={onRefresh}>
          Refresh
        </button>
      </div>
    </header>
  );
}
