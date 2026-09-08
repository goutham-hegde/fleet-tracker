/**
 * Everything wrong with the fleet, in one place.
 *
 * Up to now a breach has been visible only on the truck that has it: a coloured ring on a marker,
 * and a list inside the card once that marker is clicked. That is the wrong shape for the question
 * somebody actually asks of a control screen, which is not "is this truck all right" but "what
 * needs attention". Sixty-four trucks and one broken reefer is a dot among dots.
 *
 * <h2>It is fed by the stream, not by a poll</h2>
 *
 * The list comes from the same store the markers do, so a breach lands here the moment the
 * exception service publishes it — one Kafka hop and one SSE frame behind the rule that judged it.
 * The `/api/exceptions` endpoint exists and is not used here on purpose: a panel that polled it
 * would show a fault seconds late, and "an injected fault appears within seconds" is the thing
 * this panel is for.
 *
 * <h2>Cleared incidents stay for a while, and that is the argument</h2>
 *
 * Every rule in this platform raises *and* clears, and an incident is one condition from onset to
 * resolution rather than one message. A panel that only ever grew would demonstrate half of that.
 * So a resolved breach moves down into its own short list with how long it lasted, which is the
 * only way to see on screen that the platform noticed the truck started moving again.
 */
import type { IncidentSummary } from '../api/types';
import type { ResolvedIncident } from '../fleet/FleetStore';
import { clock, duration, humanize } from './format';

interface ExceptionsPanelProps {
  open: IncidentSummary[];
  cleared: ResolvedIncident[];
  selected: string | null;
  onSelect: (shipmentId: string) => void;
}

/** How many resolved incidents are shown. The store keeps more; a panel beside a map has room for few. */
const CLEARED_SHOWN = 5;

export function ExceptionsPanel({ open, cleared, selected, onSelect }: ExceptionsPanelProps) {
  const critical = open.filter((incident) => incident.severity === 'CRITICAL').length;

  return (
    <section className="exceptions">
      <header className="exceptions-head">
        <h2>Exceptions</h2>
        <span className={open.length > 0 ? 'exceptions-count is-alerting' : 'exceptions-count'}>
          {open.length} open
          {critical > 0 ? ` · ${critical} critical` : ''}
        </span>
      </header>

      {open.length === 0 ? (
        // Said in words rather than left blank. An empty panel and a panel that has not loaded look
        // identical, and "no exceptions" is a result the platform worked for.
        <p className="exceptions-empty">Nothing is breaching an SLA right now.</p>
      ) : (
        <ul className="exceptions-list">
          {open.map((incident) => (
            <IncidentRow
              key={incident.exceptionId}
              incident={incident}
              selected={incident.shipmentId === selected}
              onSelect={onSelect}
            />
          ))}
        </ul>
      )}

      {cleared.length > 0 ? (
        <div className="exceptions-cleared">
          <h3>Recently cleared</h3>
          <ul className="exceptions-list">
            {cleared.slice(0, CLEARED_SHOWN).map((incident) => (
              <IncidentRow
                key={incident.exceptionId}
                incident={incident}
                selected={incident.shipmentId === selected}
                onSelect={onSelect}
              />
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}

interface IncidentRowProps {
  incident: IncidentSummary | ResolvedIncident;
  selected: boolean;
  onSelect: (shipmentId: string) => void;
}

function IncidentRow({ incident, selected, onSelect }: IncidentRowProps) {
  const resolved = incident.state === 'CLEARED';
  const severity = incident.severity?.toLowerCase() ?? 'info';

  return (
    <li className={rowClass(severity, resolved, selected)}>
      {/* A button rather than a clickable list item: this moves the map and opens a panel, which is
          an action, and a keyboard user must be able to reach it. */}
      <button type="button" className="exceptions-row" onClick={() => onSelect(incident.shipmentId)}>
        <span className="exceptions-type">{humanize(incident.type)}</span>
        <span className="exceptions-shipment">{incident.shipmentId}</span>
        <span className="exceptions-detail">{incident.detail ?? '—'}</span>
        <span className="exceptions-when">{lifespan(incident, resolved)}</span>
      </button>
    </li>
  );
}

function rowClass(severity: string, resolved: boolean, selected: boolean): string {
  const classes = ['exceptions-item', `severity-${severity}`];
  if (resolved) {
    classes.push('is-cleared');
  }
  if (selected) {
    classes.push('is-selected');
  }
  return classes.join(' ');
}

/**
 * How long this has been going on, or how long it went on for.
 *
 * Deliberately not a counter, and this is the subtle part. Every instant on an incident is *event*
 * time, and under a time-scaled run event time outruns the wall clock — so subtracting an onset
 * from `Date.now()` produces a negative number for a breach that has genuinely lasted forty
 * simulated minutes. The two clocks answer different questions and only one of them is scaled,
 * which is the same trap the exception service's own signal-loss rule is built around.
 *
 * So an open incident shows the instant it began, which the platform stated, and a cleared one
 * shows the duration the platform itself measured and put on the clear event. Neither is computed
 * here from a clock this browser holds.
 */
function lifespan(incident: IncidentSummary | ResolvedIncident, resolved: boolean): string {
  if (resolved) {
    const openFor = (incident as ResolvedIncident).openForSeconds;
    if (openFor != null) {
      return `lasted ${duration(openFor)}`;
    }
    return `cleared ${clock(incident.clearedAt)}`;
  }
  return `since ${clock(incident.onsetAt ?? incident.raisedAt)}`;
}
