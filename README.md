# OsmGlass

A cycling navigator for Google Glass XE-C (Android 4.4 / API 19).

A modern Android phone runs a **fork of [OsmAnd](https://github.com/osmandapp/OsmAnd)** — it does
routing, search, offline map data, and turn-by-turn voice. A small in-app plugin (`GlassNav`)
renders a map snippet for each upcoming turn and pushes per-turn bundles plus a live position/ETA
feed to Glass over Bluetooth. The Glass app is a thin GDK LiveCard that surfaces the snippet,
the turn instruction, and configurable status corners at each decision point.

> **Architecture note:** this replaces the original custom phone app (BRouter + Mapsforge +
> Nominatim, package `dev.glass`). Rather than reimplement routing and rendering, OsmGlass now
> reuses OsmAnd's engine in-process via a plugin. See [`PLAN.md`](PLAN.md) for the original MVP
> spec and [`docs/option-c-aidl-design.md`](docs/option-c-aidl-design.md) for the earlier
> AIDL-based design that the in-app plugin superseded — both are kept for historical context.

## Components

The system spans **two git repositories**:

### This repo (`osmglass`)

- **`:protocol`** — the wire-format codec + transport interface. Plain Java 1.8, no Android deps,
  package `com.goodanser.osmglass.protocol`. Shared by *both* the OsmAnd fork and the Glass app
  (the fork includes it as `:glass-protocol`).
- **`:glass-app`** — the Glass GDK app. Java, `applicationId com.goodanser.osmglass.glass`,
  `minSdk 19` / `targetSdk 19`. A LiveCard service (`NavLiveCardService`) + BT receiver
  (`PacketDispatcher`) + on-Glass TTS. Compiles against the Glass GDK class tree at `gdk/`
  (re-jarred into `glass-app/libs/gdk-stub.jar` via the `buildGdkStub` task), which is
  `compileOnly` — the GDK is provided by the Glass system at runtime.

### The OsmAnd fork (sibling checkout at `OsmAnd/`)

A fork of OsmAnd (`origin git@github.com:zakwilson/OsmAnd.git`, branch `master`). It is **not** a
submodule of this repo — it is a separate working copy that sits beside the `osmglass` modules and
references `../protocol` from its own `settings.gradle`.

The integration lives entirely in one plugin package,
`OsmAnd/OsmAnd/src/net/osmand/plus/plugins/glassnav/`:

- **`GlassNavPlugin`** — registers the plugin (`osmand.glassnav`, enabled by default), wires the
  routing listener into `RoutingHelper`, and exposes the settings screen.
- **`GlassNavController`** — orchestrates streaming. On route start it builds the transport, sends
  `RouteStart`, then one `TurnBundle` per maneuver (with a rendered snippet PNG); on each routing
  tick it emits a `Progress` packet (latest-wins) and a `TurnAlert` on turn-index change; on
  arrival / cancel / off-route it sends `RouteEnd`. All transport writes funnel through a single
  writer coroutine so ordering is guaranteed and stale positions are shed under load.
- **`render/OsmAndSnippetRenderer`** — renders an OsmAnd map bitmap per turn (north-up or
  travel-direction-up), sized so the route window survives rotation, and computes the projection
  (`SnippetBounds`) used to place the live position marker.
- **`GlassStreamingService`** — a `connectedDevice` foreground service that keeps the OsmAnd
  process alive while streaming in the background. It holds no state; transport ownership stays in
  the controller.
- **`transport/`** (`RfcommTransport`, `TransportFactory`, `GlassDeviceFinder`) — Bluetooth
  Classic RFCOMM client and device pairing/discovery.
- **`GlassNavSettings` / `fragments/`** — settings screen + a device-pairing dialog.

A dedicated product flavor packages the fork:

| | |
|---|---|
| Flavor | `glassnav` (modeled on `nightlyFree`; no Play Services dependency) |
| `applicationId` | `net.osmand.glassnav` |
| App name | **OsmGlass** |

The distinct `applicationId` lets the fork install **side-by-side** with stock OsmAnd.

## Wire protocol

Length-prefixed binary packets, big-endian, over RFCOMM (TCP on emulators). Codec in
`:protocol` (`Codec` / `FrameReader` / `FrameWriter`). Packet types
([`PacketType`](protocol/src/main/java/com/goodanser/osmglass/protocol/PacketType.java)):

| Code | Type | Direction | Purpose |
|------|------|-----------|---------|
| `0x10` | `HELLO` | both | handshake / negotiated protocol version |
| `0x01` | `ROUTE_START` | phone→glass | route id, total turns, destination + departure labels |
| `0x02` | `TURN_BUNDLE` | phone→glass | per-turn: kind, distance-from-start, instruction, snippet PNG |
| `0x03` | `PROGRESS` | phone→glass | ~1 Hz: distance-to-turn, speed, remaining dist, ETA, marker px/bearing |
| `0x06` | `TURN_ALERT` | phone→glass | wake the LiveCard when OsmAnd issues a voice prompt for a turn |
| `0x05` | `DISPLAY_CONFIG` | phone→glass | which field each of the four Glass corners shows; mute-TTS flag |
| `0x04` | `ROUTE_END` | phone→glass | reason: `ARRIVED` / `CANCELLED` / `OFFROUTE` |
| `0x7E` / `0x7F` | `PING` / `PONG` | both | keep-alive |

`TurnBundle`s are pushed up front so Glass has every snippet cached; `Progress` then updates the
countdown, position marker, and status corners without re-sending images.

## Build

### Protocol + Glass app (this repo)

```bash
./gradlew :protocol:test                 # wire-codec unit tests
./gradlew :glass-app:assembleDebug        # build the Glass APK
```

### OsmAnd fork

Build from the `OsmAnd/` checkout. OsmAnd uses three flavor dimensions
(`version`, `coreversion`, `abi`), so a full variant name is
`<version><coreversion><abi><buildType>`. For day-to-day development on an ARM device:

```bash
cd OsmAnd
./gradlew :OsmAnd:assembleGlassnavOpengldebugArmonlyDebug   # both ARM ABIs, debug
```

Use the `opengldebug` core variant and the `armonly` ABI slice for development — `armonly` stays
well under AGP's 2 GiB packager limit that the universal `fat` APK can trip with unstripped debug
native libs. See [`docs/option-c-aidl-design.md` §5](docs/option-c-aidl-design.md) for the build,
signing, and side-by-side install details (still accurate for the fork packaging).

> **Build requires a UTF-8 locale.** The OsmAnd build copies test resources with non-ASCII
> filenames; under a POSIX/C locale `:OsmAnd-java:collectTestResources` fails. `OsmAnd/gradlew`
> self-exports `LC_ALL=C.UTF-8` on Linux/macOS/CI. On Windows, enable
> *Settings → Time & Language → "Beta: Use Unicode UTF-8 for worldwide language support"*.
> See [`CLAUDE.md`](CLAUDE.md) for the full explanation.

## Transport selection

The Glass app's `TransportFactory` resolves the transport as:
**`setprop` override (`com.goodanser.osmglass.transport`) → auto-detect** (emulator → TCP,
real device → RFCOMM). On real Glass it always uses RFCOMM unless overridden. The OsmAnd-side
controller pairs with the headset over RFCOMM.

## Run / test on emulators

For a hardware-free smoke test you can stand the Glass app up on a Glass-shaped API 19 AVD and
drive it over TCP from a JVM client:

```bash
./gradlew :glass-app:installDebug
adb -s emulator-5556 shell am start -n com.goodanser.osmglass.glass/.LauncherActivity
adb -s emulator-5556 forward tcp:8765 tcp:8765
./gradlew :protocol:test --tests "*PairEmulatorE2eTest*" -Dpair.e2e=1
adb -s emulator-5556 logcat | grep PacketDispatcher
```

(The Glass emulator's API 19 image lacks `TimelineManager`/Glass system UI, so the LiveCard
publish is wrapped in try/catch; the prism visuals, RFCOMM SDP/pairing, touchpad gestures, glance
legibility, and battery profile are all real-hardware-only validation steps.)

[`tools/setup-emulator-data.sh`](tools/setup-emulator-data.sh) predates the OsmAnd switch — it
sideloads BRouter + Mapsforge data for the retired phone-app and is kept only for reference. With
the OsmAnd fork, map data comes from OsmAnd's own first-run downloader.

## License

GPL-3.0. See [`LICENSE`](LICENSE).

Third-party projects:
- **OsmAnd** (GPL-3.0) — forked; the GlassNav plugin is added in-tree. Routing, search, offline
  map data, rendering, and voice all come from OsmAnd.
- **Glass GDK** (Apache-2.0, via [glasskit/gdk](https://github.com/glasskit/gdk)) — `compileOnly`
  only; not redistributed in our APK (provided by the Glass system at runtime).
</content>
