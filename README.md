# glass-cycling

A cycling navigator for Google Glass XE-C (Android 4.4 / API 19).

The phone (Android 7+) does the heavy lifting — routing via [BRouter](https://github.com/abrensch/brouter) and rendering map snippets via [Mapsforge](https://github.com/mapsforge/mapsforge) — and pushes per-turn bundles to Glass over Bluetooth Classic RFCOMM. The Glass app is a thin LiveCard that surfaces the snippet and turn instruction at each decision point.

See [`PLAN.md`](PLAN.md) for the architecture spec.

## Modules

- **`:protocol`** — wire-format codec + transport interface, plain Java 1.8 (no Android deps; consumed by both apps).
- **`:phone-app`** — Kotlin Android app, `minSdk 24`. Routing, rendering, BT client, UI.
- **`:glass-app`** — Java Android app, `minSdk 19`. LiveCard service + BT receiver. Uses the Glass GDK class tree at `gdk/` (re-jarred into `glass-app/libs/gdk-stub.jar`).

## Build

```bash
./gradlew :protocol:test
./gradlew :phone-app:assembleDebug :glass-app:assembleDebug
```

## Run on emulators

The repo expects two AVDs to exist:
- `clj-android-19` — Glass-shaped (640×360, API 19).
- `clj-android` — Pixel 3 (API 30).

See [`tools/setup-emulator-data.sh`](tools/setup-emulator-data.sh) for sideloading BRouter + pushing the Berlin Mapsforge `.map` and BRouter `.rd5` data files.

## Status (in-container progress)

Implemented and verified end-to-end with a JVM-side phone simulator pushing packets to the Glass APK on `clj-android-19`:

- `:protocol` codec, frame reader, TCP transport — **25 unit tests passing**
- BRouter AIDL client + GPX→Turn extractor — **6 unit tests passing**
- Mapsforge offscreen renderer (the Risk-1 question) — **5 Robolectric tests passing**, renders Monaco intersections to 640×360 PNGs without an Activity
- Nominatim search client — **5 unit tests passing**
- RouteMatcher (project location → next-turn distance + off-route) — **5 unit tests passing**
- RFCOMM transports (phone + Glass sides) — compile-only, hardware-only path
- Glass-app installs on the API 19 emulator; service starts; `TcpTransport` listens; `PacketDispatcher` correctly handles ROUTE_START → TURN_BUNDLE → PROGRESS → ROUTE_END (verified via logcat against a JVM client)

Hardware-only stop points (cannot validate without real Glass + real phone + Bluetooth pair):
1. Visual LiveCard on the prism (Glass emulator API 19 image lacks `TimelineManager`/Glass system UI; we wrap publish in try/catch)
2. RFCOMM SDP advertisement & device pairing
3. Touchpad gestures (tap to open MenuActivity, swipe-down to dismiss)
4. PNG legibility at glance distance + cycling-pace UX
5. Battery profile during a real ride

A fold to plain Android API 30 emulator was attempted but the bundled emulator binary (`30.0.5`) is too old for the API 30 system image and the SDK is on a read-only filesystem. The phone-app integration was substituted by `protocol/src/test/java/dev/glass/protocol/transport/PairEmulatorE2eTest.java` — a JVM-side test that connects via `adb forward tcp:8765 tcp:8765` and pushes a synthetic ride. To re-run:

```bash
./gradlew :glass-app:installDebug
adb -s emulator-5556 shell am start -n dev.glass.glass/.LauncherActivity
adb -s emulator-5556 forward tcp:8765 tcp:8765
./gradlew :protocol:test --tests "*PairEmulatorE2eTest*" -Dpair.e2e=1
adb -s emulator-5556 logcat | grep PacketDispatcher
```

To go to hardware: `./gradlew :glass-app:assembleRelease :phone-app:assembleRelease` (release builds default to `transportKind=rfcomm`), pair Glass + phone over BT, sideload both APKs.

## License

GPL-3.0. See [`LICENSE`](LICENSE).

Bundled / depended-on third-party projects:
- BRouter (MIT) — invoked via AIDL; not modified.
- Mapsforge (LGPL-3.0 with §4(d)(e) waiver) — used unmodified as a library dependency.
- Glass GDK (Apache-2.0, via [glasskit/gdk](https://github.com/glasskit/gdk)) — `compileOnly` only; not redistributed in our APK (provided by the Glass system at runtime).
