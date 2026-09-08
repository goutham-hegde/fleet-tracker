/**
 * The whole dashboard, which is four things stacked on one screen.
 *
 * There is very little here on purpose. The fleet lives in a store outside React, the map draws
 * itself imperatively, and the API is four fetches and a stream — so what is left at the top is
 * the one piece of state a person actually changes: which shipment is selected.
 */
import { useState } from 'react';
import { FleetMap } from './map/FleetMap';
import { useFleet } from './fleet/useFleet';
import { useLiveShipment } from './fleet/useLiveShipment';
import { useShipmentDetail } from './fleet/useShipmentDetail';
import { Legend } from './ui/Legend';
import { ShipmentCard } from './ui/ShipmentCard';
import { StatusBar } from './ui/StatusBar';

export default function App() {
  const { store, status, refresh } = useFleet();
  const [selected, setSelected] = useState<string | null>(null);

  const shipment = useLiveShipment(store, selected);
  const { detail, error: detailError } = useShipmentDetail(selected);

  return (
    <div className="app">
      <StatusBar status={status} onRefresh={refresh} />
      <main className="app-main">
        <FleetMap store={store} selected={selected} detail={detail} onSelect={setSelected} />
        {selected ? (
          <ShipmentCard
            shipment={shipment}
            detail={detail}
            detailError={detailError}
            onClose={() => setSelected(null)}
          />
        ) : null}
        <Legend />
      </main>
    </div>
  );
}
