package com.fleettracking.gateway.identity;

/**
 * Who a message is actually about, once the gateway has worked it out.
 *
 * <p>Every feed knows a different fragment of the truth. Telematics names a vehicle, the mobile app
 * names a shipment, a reefer probe names only itself. The canonical envelopes need both a shipment
 * and a vehicle on every event, so whichever fragment arrives has to be completed from reference
 * data before an envelope can be built at all.
 *
 * <p>There is no device id here on purpose. The reporting device is whatever the payload said it
 * was — {@code TLM-0004} from a telematics unit, {@code DEV-0004} from the probe on the same
 * trailer — and that is a property of the message, not something to be looked up. Reference data
 * answers "which load is this truck pulling", not "which box sent this".
 *
 * @param shipmentId the load, and therefore the Kafka partition key
 * @param vehicleId the tractor pulling it
 */
public record Identity(String shipmentId, String vehicleId) {}
