/**
 * Tests for the manifest walk.
 *
 * The bodies below are shaped like the four committed customer schemas, because the claim being
 * tested is not "this function handles objects" — it is "one renderer handles four manifests that
 * share no fields". A test built from one invented body would pass while the panel was quietly
 * specialised to it.
 *
 * The awkward cases are the point of the rest: a ragged array whose rows disagree about columns,
 * a null in the middle of a table, an empty body, and a `temperature` nested somewhere that is not
 * the reserved path.
 */
import { describe, expect, it } from 'vitest';
import {
  formatValue,
  labelFor,
  manifestGist,
  manifestNodes,
  type ManifestGroup,
  type ManifestNode,
  type ManifestTable,
} from './manifest';

/** MEDIVAULT — pharma cold chain. Nested object, reserved path, an array of custody handovers. */
const PHARMA = {
  drugLicenceNo: 'KA-B21-0098',
  temperature: { minC: 2, maxC: 8, excursionToleranceMinutes: 30 },
  consignment: {
    batchNo: 'BN-4471',
    expiryDate: '2027-03-31',
    units: 1440,
    controlledSubstance: false,
    productName: 'Recombinant insulin',
  },
  custody: [
    { party: 'MediVault Bengaluru', handedOverAt: '2026-09-08T04:15:00Z', signature: 'R.Iyer' },
    { party: 'Southern Freight', handedOverAt: '2026-09-08T05:02:00Z' },
  ],
};

/** VISTAMART — retail replenishment. The other reserved path, plus a table of purchase orders. */
const RETAIL = {
  dcCode: 'DC-NCR-02',
  asnNumber: 'ASN-88213',
  purchaseOrders: [
    { poNumber: 'PO-99120', lineCount: 46, valueInr: 1284000, department: 'Grocery' },
    { poNumber: 'PO-99121', lineCount: 12, valueInr: 233500 },
  ],
  deliveryWindow: {
    opensAt: '2026-09-08T22:00:00Z',
    closesAt: '2026-09-09T02:00:00Z',
    dockNumber: 7,
  },
  pallets: 18,
  temperatureControlled: false,
};

/** SOUTHERN-FREIGHT — LTL. A table whose rows contain nested objects, and a scalar array. */
const LTL = {
  ewayBillNo: '381004772119',
  freightClass: 'CLASS-70',
  pieces: [
    {
      description: 'Palletised cartons',
      weightKg: 412.5,
      dimensionsCm: { length: 120, width: 80, height: 145 },
      hazmat: false,
    },
  ],
  billTo: { party: 'Southern Freight Logistics', gstin: '29AAFCS1234R1ZP' },
  accessorials: ['LIFTGATE', 'INSIDE_DELIVERY'],
};

/** QUICKSHIP — parcel. Flat, small, and with nothing the other three have. */
const PARCEL = {
  trackingNumber: 'QS8841200377',
  serviceLevel: 'NEXT_DAY',
  weightKg: 2.4,
  recipient: { name: 'A. Kulkarni', pincode: '560103', phone: '+91 98450 11223' },
  signatureRequired: true,
  declaredValueInr: 18500,
  codAmountInr: 0,
};

function byKey(nodes: ManifestNode[], key: string): ManifestNode {
  const found = nodes.find((node) => node.key === key);
  if (!found) {
    throw new Error(`no node for ${key}`);
  }
  return found;
}

describe('manifestNodes', () => {
  it('renders all four customer bodies without knowing which is which', () => {
    for (const body of [PHARMA, RETAIL, LTL, PARCEL]) {
      const nodes = manifestNodes(body);
      expect(nodes).toHaveLength(Object.keys(body).length);
      // Every top-level key survives the walk, whatever shape its value had.
      expect(nodes.map((node) => node.key).sort()).toEqual(Object.keys(body).sort());
    }
  });

  it('draws a nested object as a group and an array of objects as a table', () => {
    const nodes = manifestNodes(PHARMA);
    expect(byKey(nodes, 'consignment').kind).toBe('group');
    expect(byKey(nodes, 'custody').kind).toBe('table');
    expect(byKey(nodes, 'drugLicenceNo').kind).toBe('field');
  });

  it('draws an array of scalars as a list, not a table', () => {
    const accessorials = byKey(manifestNodes(LTL), 'accessorials');
    expect(accessorials.kind).toBe('list');
    expect((accessorials as { values: string[] }).values).toEqual([
      'LIFTGATE',
      'INSIDE_DELIVERY',
    ]);
  });

  it('marks the two reserved paths and nothing else', () => {
    const groups = (nodes: ManifestNode[]) =>
      nodes.filter((node): node is ManifestGroup => node.kind === 'group');

    expect(
      groups(manifestNodes(PHARMA))
        .filter((group) => group.reserved)
        .map((group) => group.key),
    ).toEqual(['temperature']);

    expect(
      groups(manifestNodes(RETAIL))
        .filter((group) => group.reserved)
        .map((group) => group.key),
    ).toEqual(['deliveryWindow']);

    // The parcel body reserves nothing, which is correct rather than a gap: a customer who books
    // no slot and ships nothing refrigerated simply has no such term, and neither rule applies.
    expect(groups(manifestNodes(PARCEL)).filter((group) => group.reserved)).toHaveLength(0);
  });

  it('does not treat a nested temperature as the reserved path', () => {
    const nested = manifestNodes({ consignment: { temperature: { minC: 1, maxC: 2 } } });
    const consignment = byKey(nested, 'consignment') as ManifestGroup;
    expect(consignment.reserved).toBe(false);
    const inner = consignment.children.find((child) => child.key === 'temperature') as ManifestGroup;
    expect(inner.kind).toBe('group');
    expect(inner.reserved).toBe(false);
  });

  it('gives a ragged table the union of its columns, in first-seen order', () => {
    const table = byKey(manifestNodes(RETAIL), 'purchaseOrders') as ManifestTable;
    expect(table.columns).toEqual(['poNumber', 'lineCount', 'valueInr', 'department']);
    // The second purchase order has no department; the cell is empty rather than the row short.
    expect(table.rows[1]).toHaveLength(4);
    expect(table.rows[1][3]).toBe('—');
  });

  it('flattens an object inside a table cell rather than dropping it', () => {
    const table = byKey(manifestNodes(LTL), 'pieces') as ManifestTable;
    const dimensions = table.rows[0][table.columns.indexOf('dimensionsCm')];
    expect(dimensions).toContain('120 cm');
    expect(dimensions).toContain('145 cm');
  });

  it('survives an empty, absent or non-object body', () => {
    expect(manifestNodes({})).toEqual([]);
    expect(manifestNodes(undefined)).toEqual([]);
    expect(manifestNodes(null)).toEqual([]);
  });
});

describe('labelFor', () => {
  it('splits camel case into a sentence', () => {
    expect(labelFor('drugLicenceNo')).toBe('Drug licence No.');
    expect(labelFor('signatureRequired')).toBe('Signature required');
    expect(labelFor('controlledSubstance')).toBe('Controlled substance');
  });

  it('drops a unit the value already carries', () => {
    // "Weight KG: 1.8 kg" says it twice and reads like a database column; "Min c: 2.0 °C" reads
    // like a mistake. The value is where a unit belongs.
    expect(labelFor('weightKg')).toBe('Weight');
    expect(labelFor('minC')).toBe('Min');
    expect(labelFor('valueInr')).toBe('Value');
    expect(labelFor('excursionToleranceMinutes')).toBe('Excursion tolerance');
    // But only when this file actually renders that unit. Nothing here knows a "class".
    expect(labelFor('freightClass')).toBe('Freight class');
  });

  it('keeps the short capitalised words a logistics reader would notice', () => {
    expect(labelFor('gstin')).toBe('GSTIN');
    expect(labelFor('poNumber')).toBe('PO number');
    expect(labelFor('dcCode')).toBe('DC code');
  });
});

describe('formatValue', () => {
  it('adds the unit a field name implies', () => {
    expect(formatValue('weightKg', 412.5)).toBe('412.5 kg');
    expect(formatValue('minC', 2)).toBe('2.0 °C');
    expect(formatValue('excursionToleranceMinutes', 30)).toBe('30 min');
    expect(formatValue('valueInr', 1284000)).toBe('₹12,84,000');
  });

  it('leaves a string alone even when its key implies a unit', () => {
    expect(formatValue('weightKg', 'unknown')).toBe('unknown');
  });

  it('reads booleans as words and absent values as a dash', () => {
    expect(formatValue('hazmat', true)).toBe('yes');
    expect(formatValue('hazmat', false)).toBe('no');
    expect(formatValue('hazmat', null)).toBe('—');
    expect(formatValue('hazmat', undefined)).toBe('—');
    expect(formatValue('note', '')).toBe('—');
  });

  it('recognises an ISO date and an ISO instant', () => {
    // Rendered in the viewer's own locale and zone, so the assertion is on what it is not: the
    // raw wire string, which is the thing a viewer should never be shown.
    expect(formatValue('expiryDate', '2027-03-31')).not.toBe('2027-03-31');
    expect(formatValue('expiryDate', '2027-03-31')).toContain('2027');
    expect(formatValue('handedOverAt', '2026-09-08T04:15:00Z')).not.toContain('T');
  });

  it('leaves a string that only looks like a date alone', () => {
    expect(formatValue('freightClass', 'CLASS-70')).toBe('CLASS-70');
    expect(formatValue('trackingNumber', 'QS8841200377')).toBe('QS8841200377');
  });
});

describe('manifestGist', () => {
  it('summarises from whatever scalars come first, for any customer', () => {
    expect(manifestGist(PARCEL)).toContain('QS8841200377');
    expect(manifestGist(LTL)).toContain('381004772119');
    expect(manifestGist({})).toBe('');
  });
});
