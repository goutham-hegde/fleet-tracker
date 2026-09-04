# Source format samples

Captured output from `tools/fleet-simulator`, committed as **contract fixtures**. The ingest
gateway's normalizers are tested against these files rather than against payloads invented
alongside the parser — otherwise a normalizer only proves it agrees with itself.

Regenerate with:

```bash
./mvnw -pl tools/fleet-simulator -am package
java -jar tools/fleet-simulator/target/fleet-simulator-0.1.0-SNAPSHOT.jar \
  --fleet.simulator.time-scale=2000 --fleet.simulator.tick-interval=10ms \
  --fleet.simulator.trucks=8 --fleet.simulator.repeat-routes=false \
  --fleet.simulator.seed=20260831 \
  --fleet.simulator.emit.capture-dir=docs/samples \
  --fleet.simulator.emit.capture-max-per-feed=150 \
  --fleet.simulator.emit.capture-max-interchanges=10
```

The seed is fixed, so the same command reproduces the same fixtures. EDI gets its own, lower cap
because it writes a whole file per interchange while the other three append a line.

The `faults/` set described at the end was captured with the same seed plus:

```bash
  --spring.profiles.active=chaos \
  --fleet.simulator.faults.malformed-probability=0.30 \
  --fleet.simulator.emit.capture-dir=docs/samples/faults \
  --fleet.simulator.emit.capture-max-per-feed=40 \
  --fleet.simulator.emit.capture-max-interchanges=8
```

## The four feeds

| File | Feed | Knows | Reports position | Units |
|---|---|---|---|---|
| `telematics.jsonl` | In-cab unit | vehicle only | yes, continuously | **imperial** — mph, miles, °F |
| `mobile-app.jsonl` | Driver's phone | shipment only | yes, when it has signal | **m/s**, epoch millis |
| `edi-214/*.edi` | Carrier back office | shipment | **never** — city and state | n/a |
| `reefer-sensor.jsonl` | Trailer probe | device only | **never** | Celsius |

The four are dissimilar on purpose. Each breaks a different assumption a consumer might make, and
code that treats them uniformly is wrong.

### `telematics.jsonl`

Line-delimited JSON, one object per report, roughly every thirty simulated seconds per truck.
Nested the way a device vendor nests things rather than the way a logistics platform would.

```json
{"deviceId":"TLM-0002","vehicle":{"id":"VEH-0002","unitNumber":"0002","make":"Tata"},
 "gps":{"lat":17.610009,"lon":78.580027,"speedMph":0.0,"headingDeg":196.3,
        "satellites":10,"hdop":1.09,"fixTime":"2026-09-03T18:48:29.910992300Z"},
 "odometer":{"value":51665.8,"unit":"mi"},
 "engine":{"rpm":650,"coolantTempF":185,"fuelLevelPct":3.3,"ignition":"ON"},
 "sentAt":"2026-09-03T18:48:29.910992300Z","schemaVersion":"2.3"}
```

Traps: every number is imperial; there is **no shipment id**, because the box is bolted to a
tractor; the unit calls itself `TLM-0002` while the reefer probe on the same truck is `DEV-0002`,
so the two feeds share no identifier; and accuracy arrives as `hdop`, a unitless figure derived
from satellite geometry, not a radius in metres.

### `mobile-app.jsonl`

Abbreviated keys, because the payload was designed to be cheap over a mobile connection.

```json
{"sid":"SHP-HYD-0006","ts":1788461309910,"lat":17.61001,"lng":78.57991,"acc":21.0,
 "spd":0.0,"hdg":196,"bat":100,"evt":"ping","seq":1,"app":"3.4.1"}
```

The inverse of telematics: it knows the **shipment** and not the vehicle, because a driver signs in
against a load. Time is epoch milliseconds and speed is metres per second, so a normalizer that
handles telematics correctly is still wrong here.

It is also the unreliable feed. Trucks lose signal, the app buffers, and the backlog goes out in a
burst on reconnect — **out of order, and with repeats**. Two shipments in this capture show it;
`SHP-HYD-0002` arrives in sequence order:

```
1, 2, 7, 10, 11, 6, 8, 4, 5, 3, 5, 9, 12, 13, 14, 15, 16, 17
        └─────── reconnect burst ───────┘  └ back in order ┘
```

Note `5` twice — a message whose acknowledgement was lost, resent. A consumer that treats arrival
order as chronological order concludes the truck drove backwards, and one that does not dedupe on
`seq` counts the same position twice. The other six shipments in the capture never lose signal, so
a normalizer cannot assume every stream is disordered either.

`evt` is `ping`, `arrive`, `depart` or `delivered`. Only the last three carry a `stop`, so the
field set varies between messages on the same feed.

### `edi-214/interchange-NNNN.edi`

X12 EDI 214 Transportation Carrier Shipment Status, one **interchange** per file. Flat text:
segments terminated by `~`, elements separated by `*`.

```
ISA*00*          *00*          *02*CARRIER01      *ZZ*FLEETTRACK     *260903*2118*U*00401*000000102*0*P*>~
GS*QM*CARRIER01*FLEETTRACK*20260903*2118*102*X*004010~
ST*214*0001~
B10*3238738*SHP-HYD-0002*FLTX~
LX*1~
AT7*AF*NS***20260903*2027*UT~
MS1*HYDERABAD*TG*IN~
SE*6*0001~
ST*214*0002~
B10*3238734*SHP-HYD-0006*FLTX~
LX*1~
AT7*AF*NS***20260903*2027*UT~
MS1*HYDERABAD*TG*IN~
SE*6*0002~
GE*2*102~
IEA*1*000000102~
```

Four things a parser has to get right:

- **The line breaks are cosmetic.** The terminator is `~`. Real interchanges are often one enormous
  line, so split on `~`, never on `\n`. These samples contain newlines specifically so that a
  parser tested only against them can still be wrong in production.
- **Empty elements are meaningful.** `AT7*AF*NS***20260903*2027*UT` has two empty appointment
  elements holding the position of everything after them. Collapse them and the date is read as an
  appointment code.
- **No coordinates, ever.** `MS1*HYDERABAD*TG*IN` is the entire location, and the carrier does not
  know this platform's stop identifiers either. Matching it to a stop requires geocoding.
- **One interchange covers many shipments**, so it has no single shipment id and therefore no
  partition key until it is split.

Timestamps are `HHMM` — no seconds — and `AT7-07` is `UT`, universal time.

Status codes are the carrier's vocabulary: `X3` arrived at pickup, `AF` departed pickup with the
shipment, `X1` arrived at delivery, `CD` departed delivery, `X4` completed unloading. Translating
them is the EDI normalizer's job and nobody else's. This capture contains `AF`, `X1` and `CD`;
it starts with trucks already at their pickup and is cut short of any route completing, so `X3`
and `X4` are exercised by the unit tests rather than appearing here.

Nine of the ten interchanges carry more than one shipment.

Note also what is **absent**: fuel stops and other waypoints are never filed, because a carrier
reports freight events rather than every time a truck stops moving. Geofencing will observe
arrivals that EDI has no opinion about.

### `reefer-sensor.jsonl`

A thermometer with a radio.

```json
{"probe":"DEV-0002","model":"ThermoKing-CX7","readingUtc":"2026-09-03T18:48:49.910992300Z",
 "tempC":4.18,"setpointC":4.0,"returnAirC":4.93,"supplyAirC":3.02,
 "door":"OPEN","batteryV":12.64}
```

No position, no vehicle, no shipment — a device id is the only identity, and resolving it to a load
takes two hops through reference data. Only refrigerated lanes carry a probe, so most trucks in the
capture emit nothing here at all — only `DEV-0002` and `DEV-0006`, the two trucks on the
Hyderabad to Bengaluru pharma lane, appear.

The setpoint travels with the measurement because neither means anything alone: 4 °C is healthy for
pharma and ruinous for frozen freight. `alarm` is **absent** rather than null when the unit is
happy, and `door` is `OPEN` while the truck is on a dock — so warm readings cluster around dwells,
which is what distinguishes ordinary loading from a refrigeration failure.

## `faults/`

The same four feeds captured with every fault switched on (`--spring.profiles.active=chaos`) and
corruption raised to 30% so that each feed has several bad samples. These are the **dead-letter
fixtures**: messages that must be rejected and routed to a DLQ, and must never appear on a normal
topic.

They contain truncated JSON, JSON with a quote missing, payloads replaced by an upstream `502` HTML
error page, and EDI interchanges cut off mid-segment. Positions are also far noisier (25 m, with
occasional fixes over a kilometre out) and messages are dropped and duplicated in transit.

The corruption rate is far above anything a real feed produces. That is deliberate — this is a
coverage set, not a forecast.
