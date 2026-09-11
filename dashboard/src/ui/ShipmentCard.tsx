/**
 * The card that opens when a truck is clicked.
 *
 * Everything known about one shipment, in the order a person asks for it: what the truck is doing,
 * where it is going, anything wrong with it, the plan it is working through, and finally what is
 * actually inside it. The reconciliations the API performs — a withheld estimate, a next stop
 * chosen by plan order rather than by proximity — are invisible unless something puts them on
 * screen, which is most of why this card exists at all.
 *
 * The numbers here come from two places on purpose. Position, speed and open exceptions are the
 * live store, updated by the stream; the stop list and its statuses are the detail endpoint,
 * re-read on a slow timer. Anything the plan says changes every forty minutes, and anything the
 * truck says changes every ten seconds.
 */
import type { FleetShipment } from '../fleet/FleetStore';
import { useNow } from '../fleet/useLiveShipment';
import type { ShipmentDetail } from '../api/types';
import { ago, celsius, clock, duration, humanize, km, kph, pendingReason, percent, until } from './format';
import { ManifestPanel } from './ManifestPanel';
import { ARCHIVE_MODE } from '../mode';

interface ShipmentCardProps {
  shipment: FleetShipment | undefined;
  detail: ShipmentDetail | null;
  detailError: string | null;
  onClose: () => void;
}

export function ShipmentCard({ shipment, detail, detailError, onClose }: ShipmentCardProps) {
  // A clock that ticks on its own, because "heard 40s ago" has to keep climbing while the truck
  // is silent -- and a silent truck is precisely what stops causing re-renders. Read before the
  // early return, since a hook cannot be called conditionally.
  const now = useNow();

  if (!shipment) {
    return null;
  }

  const movement = shipment.movement ?? 'STOPPED';
  const next = shipment.nextStop;
  const staleSeconds = Math.round((now - shipment.lastSeenWall) / 1000);
  const stops = [...(detail?.stops ?? [])].sort((a, b) => a.seq - b.seq);

  return (
    <section className="card">
      <header className="card-head">
        <div>
          <h2>{shipment.shipmentId}</h2>
          <p className="card-sub">
            {shipment.vehicleId ?? 'vehicle unknown'}
            {shipment.source ? ` · via ${shipment.source.toLowerCase()}` : ''}
          </p>
        </div>
        <button type="button" className="button button-quiet" onClick={onClose} aria-label="Close">
          ×
        </button>
      </header>

      <div className="card-badges">
        <span className={`pill movement-${movement.toLowerCase()}`}>
          {humanize(movement)}
          {movement === 'MOVING' ? ` · ${kph(shipment.speedKph)}` : ''}
        </span>
        {shipment.worstSeverity ? (
          <span className={`pill severity-${shipment.worstSeverity.toLowerCase()}`}>
            {humanize(shipment.worstSeverity)}
          </span>
        ) : null}
        <span className="pill pill-quiet">
          {shipment.stopsCompleted}/{shipment.stopsTotal} stops
        </span>
        <span className="pill pill-quiet" title="Since this browser last had news of the truck">
          heard {ago(staleSeconds)} ago
        </span>
      </div>

      {shipment.atStop ? (
        <p className="card-line">
          Parked at <strong>{shipment.atStop.name ?? shipment.atStop.stopId}</strong>
          {shipment.atStop.city ? `, ${shipment.atStop.city}` : ''} since{' '}
          {clock(shipment.atStop.since)}.
        </p>
      ) : null}

      {next ? (
        <div className="card-block">
          <h3>Next stop</h3>
          <p className="card-line">
            <strong>{next.name ?? next.stopId}</strong>
            {next.city ? `, ${next.city}` : ''} · {km(next.remainingKm)} by road
          </p>
          {next.estimatedArrival ? (
            <p className="card-line">
              Expected <strong>{clock(next.estimatedArrival)}</strong> ({until(next.estimatedArrival)})
              {next.confidence != null ? ` · confidence ${percent(next.confidence)}` : ''}
            </p>
          ) : (
            // Naming the reason rather than showing a blank. All three reasons are the platform
            // working correctly, and a dashboard that cannot say which looks broken instead.
            <p className="card-line card-muted">{pendingReason(next.estimatePending)}</p>
          )}
          {shipment.remainingRouteKm != null ? (
            <p className="card-line card-muted">
              {km(shipment.remainingRouteKm)} left through the whole plan
            </p>
          ) : null}
        </div>
      ) : null}

      {shipment.lastStatus?.temperatureCelsius != null ? (
        <div className="card-block">
          <h3>Reefer</h3>
          <p className="card-line">
            {celsius(shipment.lastStatus.temperatureCelsius)}
            {shipment.lastStatus.setpointCelsius != null
              ? ` against a setpoint of ${celsius(shipment.lastStatus.setpointCelsius)}`
              : ''}
            {shipment.lastStatus.reasonCode ? ` · ${humanize(shipment.lastStatus.reasonCode)}` : ''}
          </p>
        </div>
      ) : null}

      {shipment.openExceptions && shipment.openExceptions.length > 0 ? (
        <div className="card-block">
          <h3>Open exceptions</h3>
          <ul className="incident-list">
            {shipment.openExceptions.map((incident) => (
              <li key={incident.exceptionId} className={`severity-${incident.severity?.toLowerCase()}`}>
                <span className="incident-type">{humanize(incident.type)}</span>
                <span className="incident-detail">{incident.detail ?? '—'}</span>
                <span className="incident-since">since {clock(incident.onsetAt)}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="card-block">
        <h3>Plan</h3>
        {detailError ? (
          <p className="card-line card-muted">{detailError}</p>
        ) : stops.length === 0 ? (
          <p className="card-line card-muted">
            {detail ? 'no itinerary on file for this shipment' : 'loading…'}
          </p>
        ) : (
          <ol className="stop-list">
            {stops.map((stop) => (
              <li key={stop.stopId} className={`stop-${stop.status.toLowerCase()}`}>
                <span className="stop-seq">{stop.seq}</span>
                <span className="stop-name">
                  {stop.name ?? stop.stopId}
                  {stop.city ? <span className="stop-city">{stop.city}</span> : null}
                </span>
                <span className="stop-when">
                  {stop.status === 'DEPARTED'
                    ? `${clock(stop.arrivedAt)} → ${clock(stop.departedAt)} (${duration(stop.dwellSeconds)})`
                    : stop.status === 'AT'
                      ? `arrived ${clock(stop.arrivedAt)}`
                      : 'pending'}
                </span>
              </li>
            ))}
          </ol>
        )}
      </div>

      {ARCHIVE_MODE ? (
        // Not "no manifest filed": every load has one. Manifests are a customer's paperwork, held by
        // the shipment service on the laptop, and they are not part of what reaches the archive.
        <div className="card-block">
          <h3>Manifest</h3>
          <p className="card-line card-muted">not part of the public archive view</p>
        </div>
      ) : (
        <ManifestPanel manifest={detail?.manifest} loaded={detail != null || detailError != null} />
      )}
    </section>
  );
}
