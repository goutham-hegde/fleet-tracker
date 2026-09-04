package com.fleettracking.shipment.manifest;

/**
 * How a load is being carried, which is what decides the shape of its paperwork.
 *
 * <p>This is the one part of a manifest that <em>is</em> a closed set in Java, and the reason it is
 * closed is different from the reason the body is open. The body varies per customer and must be
 * able to change without a deployment. The mode is how this platform routes and reasons — the
 * dashboard groups by it, and M4's SLA rules will select by it — so a value nothing recognises is
 * not a manifest this system can act on. A fifth <em>customer</em> costs an inserted document; a
 * fifth <em>mode</em> is a real change to what the platform does, and should cost a change here.
 *
 * <p>The four differ in almost every field they carry. Cold chain is about temperature and custody;
 * replenishment is about purchase orders and delivery windows; part-load is about freight class and
 * piece counts; parcel is about one small box and a service level. Modelling them as one flat
 * record would produce a document that is mostly absent fields — which is the relational answer
 * this milestone exists to argue against.
 */
public enum FreightMode {

  /**
   * Temperature-controlled pharmaceutical freight. Carries the custody chain and the temperature
   * range the load must stay inside; a breach is a destroyed shipment, not a late one.
   */
  PHARMA_COLD_CHAIN,

  /**
   * Retail distribution-centre replenishment. Purchase orders, pallet counts and a booked delivery
   * window — arriving early is a violation here, which is true of almost nothing else.
   */
  RETAIL_REPLENISHMENT,

  /**
   * Less-than-truckload: several customers' freight sharing one trailer. Priced and handled by
   * freight class and piece count, and the only mode where one vehicle carries many manifests.
   */
  LTL,

  /**
   * A single parcel. Almost no paperwork, a service level, and a signature requirement — included
   * precisely because it is the shape that makes a shared column set look absurd.
   */
  PARCEL
}
