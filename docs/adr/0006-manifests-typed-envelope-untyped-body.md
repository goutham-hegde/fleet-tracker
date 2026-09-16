# ADR 0006 — Manifests: a typed envelope, an untyped body, a schema per customer

**Status:** accepted · 2026-09-04 · S12 (recorded here in S24)

## Context

A shipment's manifest describes what is being carried and on what terms, and every customer
describes it differently. The four customers on this platform share **no** body fields at all:

- `MEDIVAULT`: pharmaceuticals, with a temperature band and chain-of-custody details
- `VISTAMART`: retail pallets, with store codes and a delivery window
- `SOUTHERN-FREIGHT`: less-than-truckload freight classes and handling codes
- `QUICKSHIP`: parcels, with recipient pincodes

A real platform takes on customers faster than it deploys. M4's claim is that a new customer is
data, not code. At the same time, some fields *are* the platform's business: which shipment, which
customer, which mode, and the terms the SLA rules judge against.

## Decision

**A manifest is a typed envelope around an untyped body. The body is validated against a JSON
Schema chosen by customer and freight mode, stored as a document, and never promoted into the
envelope.**

- **The rule for the envelope is "does the service read it?"** Shipment id, customer and mode are
  read, so they are typed. The body is only stored, queried and returned, so it stays untyped.
- **Schemas are keyed by customer and mode.** A pharma distributor that also sends samples by
  parcel has two different manifests. Every schema is committed under `docs/schemas/manifests/` with
  `additionalProperties: false`, and the tests read those same files.
- **Validation has two layers.** The service validates each body against its customer's schema. A
  MongoDB `$jsonSchema` validator enforces the envelope on the collection itself, so a `mongosh`
  prompt or a migration script cannot write a document without one.
- **Response codes say whose problem it is.** `201` stored. `400` when the request could not be read.
  `422` when the body breaks the schema, with the violations listed. `503` when no schema is on
  file, because that is the platform's gap and the same request will succeed once it is filled.
- **The rules read SLA terms from two reserved body paths**, `temperature` and `deliveryWindow`, the
  only places anything outside the manifest service reaches into a body. A missing term is not an
  error. A test guards both paths against every committed schema.
- **Schemas are looked up on every use, never cached**, like all reference data somebody else
  maintains.

## Consequences

- A fifth customer is one inserted schema document and no deploy. The dashboard draws any body
  generically, so it needs no change either.
- One collection holds all four shapes, and a body field is still queryable
  (`body.temperature.maxC <= 8` works).
- Nothing checks a body at compile time. The schema files and the tests that read them take that
  role. S16 found three of four seeded manifests had drifted from their own schemas with nothing
  complaining. `check-manifests.sh` now submits one per customer through the real endpoint in every
  demo.
- The reserved paths are a small, deliberate coupling between the rules and the customers' schemas,
  and adding a third one is a platform change, not a customer change.

## Alternatives rejected

- **A sealed type with one record per customer.** It would type every field, and a fifth customer
  would need a Java change and a redeploy of every service that reads manifests. That contradicts
  what M4 exists to show.
- **One schema per customer.** A customer with two modes would need a schema that is the union of
  both, and a union validates neither.
- **Validating only in the application.** A collection can carry exactly one validator, so the
  per-customer half cannot live in MongoDB. The envelope half can, and it is the only guard that
  survives a write that bypasses the service.
- **Promoting commonly used body fields into the envelope.** Each promotion makes one customer's
  vocabulary the platform's and leaves the others to fake it. The reserved paths are the bounded
  version of this, and deliberately stop at two.
- **A relational schema with a JSON column.** It would work, and it would hold the platform's other
  data (position history, geofence state) less naturally than MongoDB's time-series and document
  collections do. One database was already running for those.
- **`422` for a missing schema.** Nothing is wrong with the document, and blaming the caller would
  send them to fix a correct manifest.
