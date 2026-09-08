/**
 * Turning a manifest body into something renderable, without knowing whose manifest it is.
 *
 * <h2>Why this file exists at all</h2>
 *
 * The shipment service stores a manifest as a typed envelope wrapped around an *untyped* body: the
 * shipment, the customer, the freight mode and a version are a record, and everything a person
 * would actually call "the manifest" is an open map validated against that customer's own JSON
 * Schema. The four seeded customers share **no fields whatsoever** — a pharma cold chain load
 * carries a drug licence and a batch number, a parcel carries a tracking number and a recipient's
 * pincode — and that is the entire point of the design. Onboarding a fifth customer is meant to
 * cost one inserted schema document and no redeploy.
 *
 * A dashboard with a component per customer would quietly undo that. The fifth customer's
 * manifest would arrive, validate perfectly, store perfectly, and render as a blank panel until
 * somebody wrote and shipped a fifth component. So this file has no opinion about what is inside a
 * body: it walks whatever it is handed and decides how to draw each node from its *shape*, and it
 * makes any field readable from the shape of its value plus a hint from its name.
 *
 * <h2>The two exceptions, which are platform contract rather than customer knowledge</h2>
 *
 * `temperature` and `deliveryWindow` are reserved paths. The platform states that a customer who
 * wants a temperature band or a delivery slot enforced puts it *there*, and the exception service
 * reads exactly those two places to raise cold-chain and late-arrival breaches. That is a contract
 * between the platform and every customer, not a fact about any one of them, so marking those two
 * sections on screen is honest — and it answers the question a viewer will otherwise ask, which is
 * why one number in a manifest has consequences and the rest do not.
 *
 * <h2>Rendering is separated from the walk</h2>
 *
 * This module is pure and produces a tree of nodes; the component turns nodes into elements. That
 * is the same split the exception service makes between its rules and the one class that touches
 * the world, and for the same reason: every awkward body — a deeply nested object, an array of
 * arrays, a null in the middle of a table — can be tested by calling a function.
 */

/** The two body paths the platform reserves, and reads, across every customer. */
export const RESERVED_PATHS = ['temperature', 'deliveryWindow'] as const;

/** A single value: one label, one rendered string. */
export interface ManifestField {
  kind: 'field';
  /** The raw key, unique among its siblings. Used as a React key. */
  key: string;
  label: string;
  value: string;
  /** True when the value is absent, so the component can grey it rather than print "null". */
  empty: boolean;
}

/** A nested object: a heading and whatever was inside it. */
export interface ManifestGroup {
  kind: 'group';
  key: string;
  label: string;
  children: ManifestNode[];
  /** True for a section the platform reserves and the exception service reads. */
  reserved: boolean;
}

/**
 * An array of objects, drawn as a table.
 *
 * The columns are the union of the keys across every row, in the order they are first seen rather
 * than sorted — a customer wrote them in an order that means something, and alphabetising an LTL
 * consignment's pieces into `description, dimensions, hazmat, weight` reads worse than leaving
 * them as sent. A row missing a column gets an empty cell, because an array of objects is not
 * guaranteed to be uniform and a body that is ragged must still render.
 */
export interface ManifestTable {
  kind: 'table';
  key: string;
  label: string;
  columns: string[];
  columnLabels: string[];
  rows: string[][];
}

/** An array of scalars, drawn as a row of chips. */
export interface ManifestList {
  kind: 'list';
  key: string;
  label: string;
  values: string[];
}

export type ManifestNode = ManifestField | ManifestGroup | ManifestTable | ManifestList;

/**
 * Walk a manifest body into a tree of nodes.
 *
 * An empty or absent body produces an empty list rather than throwing. That case is real: the
 * shipment service will happily store an envelope whose customer schema permits an empty object,
 * and a panel that crashed on one would take the whole dashboard with it.
 */
export function manifestNodes(body: Record<string, unknown> | undefined | null): ManifestNode[] {
  if (!body || typeof body !== 'object') {
    return [];
  }
  return Object.entries(body).map(([key, value]) => nodeFor(key, value, true));
}

/**
 * One key and its value, as a node.
 *
 * The dispatch is on the value's shape and nothing else. `topLevel` exists only so a reserved path
 * is marked where the platform actually reserves it — a `temperature` nested three levels inside
 * a customer's own structure is not the reserved path and must not be labelled as one.
 */
function nodeFor(key: string, value: unknown, topLevel: boolean): ManifestNode {
  if (Array.isArray(value)) {
    const objects = value.filter(isPlainObject);
    if (objects.length > 0 && objects.length === value.length) {
      return tableFor(key, objects);
    }
    return {
      kind: 'list',
      key,
      label: labelFor(key),
      values: value.map((entry) => formatValue(key, entry)),
    };
  }

  if (isPlainObject(value)) {
    return {
      kind: 'group',
      key,
      label: labelFor(key),
      reserved: topLevel && (RESERVED_PATHS as readonly string[]).includes(key),
      children: Object.entries(value).map(([childKey, childValue]) =>
        nodeFor(childKey, childValue, false),
      ),
    };
  }

  return {
    kind: 'field',
    key,
    label: labelFor(key),
    value: formatValue(key, value),
    empty: value == null || value === '',
  };
}

function tableFor(key: string, rows: Record<string, unknown>[]): ManifestTable {
  const columns: string[] = [];
  for (const row of rows) {
    for (const column of Object.keys(row)) {
      if (!columns.includes(column)) {
        columns.push(column);
      }
    }
  }
  return {
    kind: 'table',
    key,
    label: labelFor(key),
    columns,
    columnLabels: columns.map(labelFor),
    rows: rows.map((row) => columns.map((column) => formatValue(column, row[column]))),
  };
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * `drugLicenceNo` becomes `Drug licence no`, `codAmountInr` becomes `Cod amount INR`.
 *
 * Camel case is split on the boundary and the result sentence-cased, which is a guess — but it is
 * a guess about presentation rather than about meaning, and the failure mode is a slightly odd
 * heading rather than a wrong number. The short all-capital words are kept as they are because a
 * split would otherwise produce "Gstin" and "Po number", and those are the ones a logistics reader
 * would notice immediately.
 */
const ACRONYMS = new Set(['inr', 'gstin', 'po', 'dc', 'asn', 'cod', 'id', 'eta', 'ltl', 'hsn']);

/** Words that are neither an acronym nor an ordinary word. Only one so far, and it is a common one. */
const SPELLINGS: Record<string, string> = { no: 'No.' };

export function labelFor(key: string): string {
  const words = withoutUnitSuffix(key)
    // A boundary is a lower-then-upper pair, or a run of capitals followed by a capitalised word.
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
    .split(/\s+/);

  return words
    .map((word, index) => {
      const lower = word.toLowerCase();
      if (SPELLINGS[lower]) {
        return SPELLINGS[lower];
      }
      if (ACRONYMS.has(lower)) {
        return lower.toUpperCase();
      }
      return index === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
    })
    .join(' ');
}

/**
 * Drop the unit off the end of a field name, because the value already carries it.
 *
 * `weightKg` labelled "Weight KG" beside a value of "1.8 kg" says the unit twice and reads like a
 * database column; `minC` labelled "Min c" beside "2.0 °C" reads like a mistake. Only stripped
 * when the same suffix is one the formatter actually renders — so a field this file has no unit
 * for keeps every letter of its name — and never when stripping would leave nothing behind.
 */
function withoutUnitSuffix(key: string): string {
  if (unitFor(key) === null) {
    return key;
  }
  const trimmed = key.replace(/(Inr|Kg|Cm|C|Minutes|Hours)$/, '');
  return trimmed.length > 0 ? trimmed : key;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const ISO_DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/;

/**
 * One value, as a person reads it.
 *
 * Everything here keys off the *value's* type first and only consults the key's name to add a
 * unit. That order matters: a key ending in `Kg` whose value is a string must render as the string
 * it is, because the alternative is a panel that prints "kg" after a word.
 *
 * The units it knows are the ones a logistics manifest actually uses — kilograms, rupees, degrees,
 * minutes — and each is recognised from a suffix rather than from a field a particular customer
 * happens to have. A customer who invents `netWeightKg` gets the same treatment as one who sends
 * `weightKg`, with nothing added here.
 */
export function formatValue(key: string, value: unknown, parentKey?: string): string {
  if (value == null || value === '') {
    return '—';
  }

  if (typeof value === 'boolean') {
    return value ? 'yes' : 'no';
  }

  if (typeof value === 'number') {
    return withUnit(key, value, parentKey);
  }

  if (typeof value === 'string') {
    if (ISO_DATE_TIME.test(value)) {
      const at = new Date(value);
      if (!Number.isNaN(at.getTime())) {
        return at.toLocaleString([], {
          day: '2-digit',
          month: 'short',
          hour: '2-digit',
          minute: '2-digit',
        });
      }
    }
    if (ISO_DATE.test(value)) {
      const at = new Date(`${value}T00:00:00Z`);
      if (!Number.isNaN(at.getTime())) {
        return at.toLocaleDateString([], {
          day: '2-digit',
          month: 'short',
          year: 'numeric',
          timeZone: 'UTC',
        });
      }
    }
    return value;
  }

  if (Array.isArray(value)) {
    // Only reachable from a table cell holding a nested array. Flattened rather than dropped: the
    // point of an untyped body is that a customer may send a shape nobody anticipated.
    return value.map((entry) => formatValue(key, entry)).join(', ');
  }

  if (isPlainObject(value)) {
    // Same case, one level in: a nested object inside a table row. Rendered inline so the row
    // stays a row — `120 × 80 × 90` is more useful in a cell than a nested table would be.
    return Object.entries(value)
      .map(
        // The parent's key is passed down as a unit hint. A customer who writes `dimensionsCm`
        // around `length`, `width` and `height` has stated the unit once, on the container, and a
        // cell reading "Length 120 · Width 80" has silently thrown it away.
        ([childKey, childValue]) => `${labelFor(childKey)} ${formatValue(childKey, childValue, key)}`,
      )
      .join(' · ');
  }

  return String(value);
}

/** How a number of a given kind is written. Null when the name implies no unit at all. */
type Unit = (value: number) => string;

/**
 * The units a logistics manifest actually uses, recognised from a suffix.
 *
 * A suffix rather than a field name, so a customer who invents `netWeightKg` gets the same
 * treatment as one who sends `weightKg` with nothing added here. The temperature rule matches a
 * bare capital `C` after a lowercase letter or digit — `minC`, `maxC`, `setpointC` — which is
 * narrow on purpose: a case-insensitive "ends with c" also matches `freightClass` abbreviated,
 * `basic`, and every other word in English that happens to end in one.
 */
function unitFor(key: string): Unit | null {
  const lower = key.toLowerCase();

  if (lower.endsWith('inr')) {
    // Rupees, grouped the way the currency is actually written: 12,34,567 rather than 1,234,567.
    return (value) => `₹${value.toLocaleString('en-IN')}`;
  }
  if (lower.endsWith('kg')) {
    return (value) => `${trim(value)} kg`;
  }
  if (lower.endsWith('cm')) {
    return (value) => `${trim(value)} cm`;
  }
  if (/[a-z0-9]C$/.test(key)) {
    // A cold chain is judged in tenths, so this one keeps its decimal whatever the value is.
    return (value) => `${value.toFixed(1)} °C`;
  }
  if (lower.endsWith('minutes')) {
    return (value) => (value < 60 ? `${trim(value)} min` : `${Math.floor(value / 60)}h ${value % 60}m`);
  }
  if (lower.endsWith('hours')) {
    return (value) => `${trim(value)} h`;
  }

  return null;
}

/** A number with whatever unit its own name implies, or failing that its container's. */
function withUnit(key: string, value: number, parentKey?: string): string {
  const unit = unitFor(key) ?? (parentKey ? unitFor(parentKey) : null);
  return unit ? unit(value) : value.toLocaleString();
}

/** `12.0` becomes `12`, `12.5` stays `12.5`. Trailing zeroes on a weight read as false precision. */
function trim(value: number): string {
  return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(2)));
}

/**
 * A one-line summary of a body, for a collapsed panel or a marker's tooltip.
 *
 * Deliberately built from whichever *scalar* fields come first rather than from named ones. A
 * summary that looked for `trackingNumber` would be empty for three of the four customers.
 */
export function manifestGist(body: Record<string, unknown> | undefined | null, limit = 3): string {
  const nodes = manifestNodes(body);
  const fields = nodes.filter((node): node is ManifestField => node.kind === 'field' && !node.empty);
  if (fields.length === 0) {
    return '';
  }
  return fields
    .slice(0, limit)
    .map((field) => `${field.label}: ${field.value}`)
    .join(' · ');
}
