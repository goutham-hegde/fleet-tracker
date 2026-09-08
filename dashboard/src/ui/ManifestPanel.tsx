/**
 * The manifest, on screen — what is actually inside the truck.
 *
 * This is the last part of the polymorphic-manifest argument to reach a person. The shipment
 * service validates each customer's body against that customer's own JSON Schema and stores it
 * untouched; the dashboard API passes it through untouched; and this component draws it without
 * knowing whose it is. The four seeded customers share no fields at all, and the same forty lines
 * below render every one of them.
 *
 * There is deliberately no `switch (customerId)` here and there must never be one. The moment a
 * customer needs a component, onboarding costs a release rather than an inserted document, and the
 * whole reason this platform stores manifests in MongoDB evaporates.
 *
 * The walk and the formatting live next door in `manifest.ts`, which is pure and tested. What is
 * left here is elements.
 */
import type { ManifestDetail } from '../api/types';
import { manifestNodes, type ManifestNode } from './manifest';
import { clock } from './format';

interface ManifestPanelProps {
  manifest: ManifestDetail | undefined;
  /** Null while the detail request is still in flight, so "no manifest" is not claimed too early. */
  loaded: boolean;
}

export function ManifestPanel({ manifest, loaded }: ManifestPanelProps) {
  if (!manifest) {
    return (
      <div className="card-block">
        <h3>Manifest</h3>
        <p className="card-line card-muted">
          {loaded ? 'no manifest filed for this load' : 'loading…'}
        </p>
      </div>
    );
  }

  const nodes = manifestNodes(manifest.body);

  return (
    <div className="card-block">
      <h3>Manifest</h3>
      <p className="manifest-head">
        <strong>{manifest.customerId}</strong>
        {manifest.mode ? <span className="pill pill-quiet">{manifest.mode.toLowerCase()}</span> : null}
      </p>
      <p className="card-line card-muted manifest-meta">
        {manifest.schemaVersion ? `schema ${manifest.schemaVersion}` : 'schema version not recorded'}
        {manifest.createdAt ? ` · filed ${clock(manifest.createdAt)}` : ''}
      </p>

      {nodes.length === 0 ? (
        <p className="card-line card-muted">the body is empty</p>
      ) : (
        <div className="manifest">
          {nodes.map((node) => (
            <Node key={node.key} node={node} />
          ))}
        </div>
      )}
    </div>
  );
}

/** One node of the body, drawn according to its shape. */
function Node({ node }: { node: ManifestNode }) {
  switch (node.kind) {
    case 'field':
      return (
        <div className="manifest-field">
          <span className="manifest-key">{node.label}</span>
          <span className={node.empty ? 'manifest-value is-empty' : 'manifest-value'}>
            {node.value}
          </span>
        </div>
      );

    case 'group':
      return (
        <section className={node.reserved ? 'manifest-group is-reserved' : 'manifest-group'}>
          <h4>
            {node.label}
            {node.reserved ? (
              // Worth its space: this is the difference between a number a customer sent and a
              // number the platform will raise an SLA breach over. Both look like fields.
              <span
                className="manifest-reserved"
                title="A path the platform reserves. The exception service reads this section to judge the load."
              >
                enforced
              </span>
            ) : null}
          </h4>
          <div className="manifest">
            {node.children.map((child) => (
              <Node key={child.key} node={child} />
            ))}
          </div>
        </section>
      );

    case 'table':
      return (
        <section className="manifest-group">
          <h4>
            {node.label}
            <span className="manifest-count">{node.rows.length}</span>
          </h4>
          {/* Scrolls in its own box rather than widening the card: an LTL consignment's pieces
              carry four columns and the card is a fixed width beside a map. */}
          <div className="manifest-tablewrap">
            <table className="manifest-table">
              <thead>
                <tr>
                  {node.columnLabels.map((label) => (
                    <th key={label} scope="col">
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {node.rows.map((row, index) => (
                  // The index is the key because a manifest row has no id of its own — the body is
                  // untyped, so there is nothing here that is guaranteed to be unique. Safe: rows
                  // are never reordered, inserted into or removed; the whole body is replaced.
                  // eslint-disable-next-line react/no-array-index-key
                  <tr key={index}>
                    {row.map((cell, cellIndex) => (
                      // eslint-disable-next-line react/no-array-index-key
                      <td key={cellIndex}>{cell}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      );

    case 'list':
      return (
        <div className="manifest-field">
          <span className="manifest-key">{node.label}</span>
          <span className="manifest-value">
            {node.values.length === 0 ? (
              <span className="is-empty">none</span>
            ) : (
              node.values.map((value, index) => (
                // eslint-disable-next-line react/no-array-index-key
                <span key={index} className="chip">
                  {value}
                </span>
              ))
            )}
          </span>
        </div>
      );
  }
}
