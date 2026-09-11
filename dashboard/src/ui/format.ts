/**
 * Turning the API's numbers and instants into something a person reads at a glance.
 *
 * All of it is deliberately relative or short. A live map is read in seconds — "in 42m", "3s ago",
 * "180 km" — and a full ISO timestamp on a marker's card is a string a viewer has to do arithmetic
 * on before it means anything.
 */

/** `2026-09-08T14:03:11Z` becomes `14:03`, in the viewer's own zone. */
export function clock(instant?: string): string {
  if (!instant) {
    return '—';
  }
  const at = new Date(instant);
  if (Number.isNaN(at.getTime())) {
    return '—';
  }
  return at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/**
 * How long until an instant, as "in 42m" or "12m late".
 *
 * Note what this compares: an estimate produced by the platform against the *viewer's* clock. Under
 * a time-scaled simulation run those are different clocks — simulated time outruns the wall clock,
 * so an estimate an hour ahead in event time arrives in twelve real seconds. So this reads oddly
 * during a demonstration run and correctly against a real fleet, and the honest fix is to say what
 * it is: a countdown in event time is meaningless to a person watching a clock on the wall.
 */
export function until(instant?: string): string {
  if (!instant) {
    return '—';
  }
  const at = new Date(instant).getTime();
  if (Number.isNaN(at)) {
    return '—';
  }
  const minutes = Math.round((at - Date.now()) / 60_000);
  if (minutes >= 0) {
    return minutes < 60 ? `in ${minutes}m` : `in ${Math.floor(minutes / 60)}h ${minutes % 60}m`;
  }
  const late = -minutes;
  return late < 60 ? `${late}m ago` : `${Math.floor(late / 60)}h ${late % 60}m ago`;
}

/** Seconds since something, as "3s", "4m", "2h". */
export function ago(seconds: number | null | undefined): string {
  if (seconds == null) {
    return '—';
  }
  if (seconds < 60) {
    return `${Math.max(0, Math.round(seconds))}s`;
  }
  if (seconds < 3_600) {
    return `${Math.floor(seconds / 60)}m`;
  }
  if (seconds < 86_400) {
    return `${Math.floor(seconds / 3_600)}h ${Math.floor((seconds % 3_600) / 60)}m`;
  }
  // The public archive view routinely shows fixes from days ago; "50h 3m" makes a person divide.
  return `${Math.floor(seconds / 86_400)}d ${Math.floor((seconds % 86_400) / 3_600)}h`;
}

/**
 * `2026-09-11T12:59:00Z` becomes `11 Sep, 18:29`, in the viewer's own zone. For instants that may
 * not be today, which on the live map is nothing and on the public archive view is most things.
 */
export function dateTime(instant?: string): string {
  if (!instant) {
    return '—';
  }
  const at = new Date(instant);
  if (Number.isNaN(at.getTime())) {
    return '—';
  }
  return at.toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

/** A duration in seconds, as "35m" or "1h 10m". Used for dwell times. */
export function duration(seconds?: number): string {
  if (seconds == null) {
    return '—';
  }
  if (seconds < 60) {
    return `${Math.round(seconds)}s`;
  }
  const minutes = Math.round(seconds / 60);
  return minutes < 60 ? `${minutes}m` : `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

/** Kilometres, with the precision the number deserves rather than what the double carries. */
export function km(value?: number): string {
  if (value == null) {
    return '—';
  }
  return value < 10 ? `${value.toFixed(1)} km` : `${Math.round(value)} km`;
}

/** Speed, rounded. A tenth of a km/h is noise on a moving truck. */
export function kph(value?: number): string {
  return value == null ? '—' : `${Math.round(value)} km/h`;
}

/** A temperature, to one decimal — a cold chain is judged in tenths. */
export function celsius(value?: number): string {
  return value == null ? '—' : `${value.toFixed(1)} °C`;
}

/** `TEMPERATURE_EXCURSION` becomes `temperature excursion`. */
export function humanize(value?: string): string {
  return value ? value.toLowerCase().replace(/_/g, ' ') : '—';
}

/** A confidence of 0.82 becomes `82%`. */
export function percent(value?: number): string {
  return value == null ? '—' : `${Math.round(value * 100)}%`;
}

/**
 * Why there is no estimate, in words.
 *
 * The three reasons are genuinely different and a viewer needs to tell them apart: a truck parked
 * at a dock has no estimate because the platform suppresses one there by design, a superseded
 * estimate names a stop already cleared, and a truck that has just pulled out has not been
 * measured yet. All three are correct behaviour, and a blank would make all three look broken.
 */
export function pendingReason(reason?: string): string {
  switch (reason) {
    case 'AT_A_STOP':
      return 'no estimate while parked';
    case 'SUPERSEDED':
      return 'estimate is for a stop already cleared';
    case 'NO_ESTIMATE_YET':
      return 'not measured yet on this leg';
    default:
      return 'no estimate';
  }
}
