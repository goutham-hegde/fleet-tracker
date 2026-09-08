/**
 * The whole dashboard, which is a map with things arranged around it.
 *
 * There is very little here on purpose. The fleet lives in a store outside React, the map draws
 * itself imperatively, and the API is four fetches and a stream — so what is left at the top is
 * the state a person actually changes: which shipment is selected, and whether finished loads are
 * still on the map.
 *
 * The layout is a card on the left for the one shipment being looked at, and a rail on the right
 * for the two things that are about the whole fleet: what is currently wrong with it, and what the
 * markers mean. The rail is a column rather than two independently positioned panels, because a
 * growing exceptions list and a fixed legend placed separately overlap on a short window — and the
 * overlap only appears once something has gone wrong, which is the worst possible time.
 */
import { useState } from 'react';
import { FleetMap } from './map/FleetMap';
import { useFleet } from './fleet/useFleet';
import { useLiveShipment } from './fleet/useLiveShipment';
import { useShipmentDetail } from './fleet/useShipmentDetail';
import { ExceptionsPanel } from './ui/ExceptionsPanel';
import { Legend } from './ui/Legend';
import { ShipmentCard } from './ui/ShipmentCard';
import { StatusBar } from './ui/StatusBar';

export default function App() {
  const { store, status, refresh } = useFleet();
  const [selected, setSelected] = useState<string | null>(null);

  // Off by default. A live map is for what is happening, and a repeating run leaves every load it
  // has ever finished parked on the depot it finished at — twenty-one delivered markers against
  // three moving trucks, after twenty minutes. Nothing is discarded: the shipments stay in the
  // store, stay counted in the status bar, and come back with one click.
  const [showDelivered, setShowDelivered] = useState(false);

  const shipment = useLiveShipment(store, selected);
  const { detail, error: detailError } = useShipmentDetail(selected);

  return (
    <div className="app">
      <StatusBar
        status={status}
        showDelivered={showDelivered}
        onToggleDelivered={() => setShowDelivered((shown) => !shown)}
        onRefresh={refresh}
      />
      <main className="app-main">
        <FleetMap
          store={store}
          selected={selected}
          detail={detail}
          showDelivered={showDelivered}
          onSelect={setSelected}
        />
        {selected ? (
          <ShipmentCard
            shipment={shipment}
            detail={detail}
            detailError={detailError}
            onClose={() => setSelected(null)}
          />
        ) : null}
        <div className="rail">
          <ExceptionsPanel
            open={status.incidents}
            cleared={status.clearedIncidents}
            selected={selected}
            onSelect={setSelected}
          />
          <Legend />
        </div>
      </main>
    </div>
  );
}
