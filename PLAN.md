# Glass Cycling Navigation — MVP Plan

## Context

Google Glass XE-C (XE24, Android 4.4 / API 19) no longer has working turn-by-turn cycling navigation. Native Maps support was dropped; surviving notification-based directions lack any map context, which makes street/path identification on a moving bike unreliable. Goal: a working end-to-end OSS (GPLv3) cycling navigator that shows a map snippet on Glass at each decision point (~100 m before a turn). Greenfield project in `/home/zak/code/glass`. MVP = one architecture, end-to-end. Voice deferred to v2.

## Architecture options considered

### Option A — Companion phone app + thin Glass GDK app  ★ RECOMMENDED ★
Phone (Android 16, modern toolchain) does routing + per-turn snippet rendering and pushes `(turn_text, distance, bitmap)` bundles to Glass over Bluetooth RFCOMM. Glass GDK app is a small LiveCard that renders the bundle when proximity to the next turn drops below a threshold.

- **Pros:** modern toolchain for the hard parts (routing, rendering, OSM data); Glass code stays small (LiveCard + BT receiver + countdown text); battery friendly on Glass (no GPS, no map render); supports both online and offline (Mapsforge offline tiles + BRouter local profile, or live BRouter HTTP).
- **Cons:** phone must be present and paired; need to handle BT reconnect; GDK build toolchain still requires effort.

### Option B — Standalone on Glass
Port BRouter (Java) + Mapsforge to API 19 and run everything on Glass.

- **Pros:** no phone dependency.
- **Cons:** Glass GPS is the dominant battery cost (~1 hr of nav at most); routing-graph memory footprint on Glass is borderline; Mapsforge tile storage competes with Glass's tiny user partition; debugging on API 19 is painful. High effort, fragile.

### Option C — Hybrid (phone routes, Glass renders from local tiles)
Phone sends only turn instructions + GPS-relative position; Glass holds Mapsforge tiles and renders snippets locally.

- **Pros:** less BT bandwidth than A.
- **Cons:** since the chosen UX is *snippet at decision points only*, A already sends ~1 image per turn (tiny). Hybrid keeps all the painful Glass-side rendering work for no real benefit. Not worth it.

**Decision:** Option A. The "snippet at decision points" UX collapses the value of B and C; A puts the heavy work on the modern OS and the easy work on the legacy one.

## Stack (Option A)

| Layer | Choice | Notes |
|---|---|---|
| Phone routing | **BRouter** (Java, MIT) via its Android `IBRouterService` AIDL interface | Cycling profiles built-in, elevation-aware, can be invoked headless from a sibling app. Ships data segments per region. |
| Phone rendering | **Mapsforge** (LGPL, pure Java) | Renders offline vector tiles to a `Bitmap` on a background thread. Lighter than MapLibre Native and license-compatible with GPLv3 distribution. |
| Phone↔Glass transport | **Bluetooth Classic RFCOMM (SPP)** | Most reliable channel on XE24 per community experience; BLE on XE24 is flaky. Custom length-prefixed binary protocol. |
| Glass app | **Glass GDK** LiveCard (low-frequency rendering mode) | Receives bundle, draws bitmap + turn text + distance countdown. Tap = dismiss/next; swipe down = stop nav. |
| License | **GPLv3** for both apps | Compatible with BRouter (MIT), Mapsforge (LGPL), and any future BRouter/Mapsforge upgrades. |

> ⚠️ Verification needed during implementation: research turned up a "GlassCompanion" reference repo of unknown provenance — do **not** depend on it without auditing. Treat the BT layer as bespoke; reference only Android's `BluetoothSocket` / `BluetoothServerSocket` docs and the Glass GDK samples directly.

## Project layout

```
/home/zak/code/glass
├── phone-app/                 # Android Studio module, minSdk 24, Kotlin
│   ├── routing/               # BRouter AIDL client + GPX→turn-list extractor
│   ├── render/                # Mapsforge offscreen snippet generator
│   ├── transport/             # BT RFCOMM connection manager + protocol codec
│   └── ui/                    # Destination picker, route preview, ride controls
├── glass-app/                 # GDK module, minSdk 19, Java (Kotlin viable but adds ProGuard pain)
│   ├── transport/             # BT receiver mirror of phone codec
│   └── livecard/              # LiveCard service, RemoteViews for snippet+text
├── protocol/                  # Shared Java/Kotlin module: packet structs, codec
├── LICENSE                    # GPL-3.0
└── README.md
```

## Wire protocol (v0)

Length-prefixed packets over RFCOMM. Big-endian.

```
uint16 length | uint8 type | payload[length-1]
```

Types:
- `0x01 ROUTE_START`     — `route_id(uint32)` + `total_turns(uint16)`
- `0x02 TURN_BUNDLE`     — `route_id` + `turn_index(uint16)` + `instruction(utf8 pstring)` + `distance_m(uint16)` + `png_bytes(...)`
- `0x03 PROGRESS`        — `route_id` + `turn_index(uint16)` + `distance_to_turn_m(uint16)` (sent ~1 Hz from phone while close)
- `0x04 ROUTE_END`       — `route_id`
- `0x7F PING/PONG`       — keep-alive

Phone pre-sends all `TURN_BUNDLE`s at route start so Glass has them cached; `PROGRESS` updates the countdown text without re-sending images.

## MVP flow

1. User opens phone app → searches destination (Nominatim online, or pre-cached POIs offline) → confirms route.
2. Phone calls BRouter service → gets GPX + waypoints + turn instructions.
3. Phone post-processes GPX into a turn list (heading-delta threshold + named-road changes), and renders one Mapsforge snippet per turn (zoomed to ~50 m around the intersection, with an arrow overlay).
4. Phone connects to Glass over RFCOMM (Glass app advertises a known UUID).
5. Phone streams `ROUTE_START` + all `TURN_BUNDLE`s.
6. As the rider moves, phone tracks GPS, and when distance-to-next-turn drops below ~150 m it streams `PROGRESS` packets at 1 Hz; Glass surfaces the LiveCard with the snippet and live countdown. After the turn is passed, LiveCard dims until the next decision point.
7. On route end / off-route, phone sends `ROUTE_END` (off-route handling = recompute and start a new route).

## Critical files to create

- `phone-app/routing/BRouterClient.kt` — bind to `btools.routingapp/.BRouterService`, request `cycle` profile route between two `LatLng`s, parse returned GPX `<extensions>` into `Turn(at, instruction, headingDelta)`.
- `phone-app/render/SnippetRenderer.kt` — `MapView` (offscreen) → `Bitmap` at zoom 18 centered on each turn point; overlay arrow drawable; PNG-encode at ~640×360 to match Glass prism.
- `phone-app/transport/GlassLink.kt` — manages `BluetoothSocket`, reconnect loop, packet codec.
- `glass-app/transport/PhoneLink.java` — `BluetoothServerSocket` listening on the shared UUID; pushes packets to a `LocalBroadcastManager`.
- `glass-app/livecard/NavLiveCardService.java` — low-frequency LiveCard; `RemoteViews` with `ImageView` + 2 `TextView`s; `MenuActivity` for "Stop navigation".
- `protocol/Packet.kt` (or `.java`) — shared codec; build twice (jvm target for phone, Android library for Glass).

## Verification

End-to-end MVP is "done" when, indoors with a mock GPS source on the phone:

1. `./gradlew :phone-app:installDebug :glass-app:installDebug` builds both modules cleanly against their respective SDKs.
2. With Glass paired and the GDK app's BT service running, launching the phone app, picking a 2 km test route in your neighborhood, and tapping "Start ride" causes a LiveCard to appear on Glass within ~3 s with the first turn's snippet and instruction text.
3. Feeding the phone a fake GPS track along the route (Android `LocationManager` test provider) causes the LiveCard to update its distance countdown at ≥1 Hz and to swap to the next snippet at each turn.
4. Disabling phone BT mid-route → Glass shows "Disconnected" and resumes when re-enabled (using the cached bundles).
5. Real-bike test: one short cycling trip end-to-end on a known route; verify snippets are legible at glance distance and arrive early enough to react.

Manual unit tests for the packet codec (round-trip random `TURN_BUNDLE`s with embedded PNGs) and the GPX→turn-list extractor (golden-file comparison on 2–3 saved GPX routes) are sufficient; no broader test infrastructure for MVP.

## Out of scope for MVP (capture for v2)

- Voice trigger on Glass ("OK Glass, bike to…") — needs GDK voice-trigger XML + on-phone geocoding round-trip.
- Off-route reroute UX (MVP just recomputes silently).
- Elevation/effort-aware profile tuning.
- WiFi-direct fallback transport.
- Battery telemetry display.
