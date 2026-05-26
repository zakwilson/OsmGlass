# Option C — OsmAnd AIDL extension design

Issue: glass-nav-b2m. Gates glass-nav-nzt, glass-nav-ei1, glass-nav-45y, glass-nav-p9i,
glass-nav-avi, glass-nav-c7v.

## Context

Spike glass-nav-bk3 showed that the stock OsmAnd AIDL surface has no way to hand
the phone the computed route polyline + structured turn list, and ADirectionInfo
cadence (0.14 Hz, 9 s max gap) is too slow for Glass Progress (we send Progress
at ≈1 Hz). Option C is "fork OsmAnd, add the missing AIDL surface, ship the fork
alongside stock OsmAnd as a side-by-side install, and try to upstream it."

User decisions already locked in (per issue glass-nav-b2m description):

1. Strategy: upstream PR with fork as fallback. Design AIDL additions to be
   upstream-acceptable.
2. The patched OsmAnd installs *alongside* official OsmAnd on test devices (so
   we get a distinct `applicationId`).

This doc fixes the contracts so the implementation sub-tasks have unambiguous
signatures, filenames, and parcelable shapes.

## 1. AIDL surface additions

Three new entries on `IOsmAndAidlInterface`. All live in the existing
`net.osmand.aidlapi` package so the wrapper module
(`osmand-api/` — sourceSets pointing at `OsmAnd/OsmAnd-api/src`) picks them up
without project changes.

```aidl
// OsmAnd/OsmAnd-api/src/net/osmand/aidlapi/IOsmAndAidlInterface.aidl

// Existing imports …
import net.osmand.aidlapi.navigation.ARoute;
import net.osmand.aidlapi.navigation.AGetRouteParams;
import net.osmand.aidlapi.navigation.ANavigationProgressParams;
import net.osmand.aidlapi.navigation.ANavigationProgress;
import net.osmand.aidlapi.navigation.ARerouteEvent;

interface IOsmAndAidlInterface {
    // … existing 100+ methods …

    /**
     * Returns the currently-active route (polyline + structured turn list),
     * or null if no route is calculated. Synchronous so callers don't have to
     * sequence a register/wait dance just to read state that already exists.
     *
     * @return false on transport error or no active route. On success, params
     *         is mutated in-place with the route.
     */
    boolean getActiveRoute(inout AGetRouteParams params);

    /**
     * Subscribe to a periodic high-cadence navigation progress callback fed
     * by RoutingHelper's location stream.
     *
     * Cadence: one callback per location fix (1 Hz on typical GPS hardware),
     * fed by the same listener chain as registerForNavigationUpdates() but
     * carrying a richer payload (speed, remaining distance, ETA, distance to
     * next turn, next-turn type/street).
     *
     * @param params.intervalMs (long) — minimum gap between callbacks; impl
     *        coalesces faster fixes. 0 = send every fix. Default 1000.
     */
    long registerForNavigationProgress(in ANavigationProgressParams params,
                                       IOsmAndAidlCallback callback);

    /**
     * Subscribe to reroute events. Fires when RoutingHelper recomputes the
     * route after an off-route condition (or any other internal recompute).
     * Callers should re-pull getActiveRoute() on receipt.
     */
    long registerForRerouteEvents(in ANavigationUpdateParams params,
                                  IOsmAndAidlCallback callback);
}
```

`registerForRerouteEvents` reuses `ANavigationUpdateParams` (existing) because
the only fields it needs are `subscribeToUpdates` + `callbackId`. Saves a
parcelable.

Two new methods on `IOsmAndAidlCallback`:

```aidl
// OsmAnd/OsmAnd-api/src/net/osmand/aidlapi/IOsmAndAidlCallback.aidl

import net.osmand.aidlapi.navigation.ANavigationProgress;
import net.osmand.aidlapi.navigation.ARerouteEvent;

interface IOsmAndAidlCallback {
    // … existing 9 methods …

    /** Callback for registerForNavigationProgress(). */
    void onNavigationProgress(in ANavigationProgress progress);

    /** Callback for registerForRerouteEvents(). */
    void onReroute(in ARerouteEvent event);
}
```

## 2. Parcelable shapes

All new parcelables live in `net.osmand.aidlapi.navigation` and follow the
existing `AidlParams` pattern (Bundle-backed read/write — see
`ANavigationUpdateParams.java` as the canonical small example).

### `AGetRouteParams` (inout)

Client constructs empty; server fills `route` before returning.

```java
// OsmAnd/OsmAnd-api/src/net/osmand/aidlapi/navigation/AGetRouteParams.java
public class AGetRouteParams extends AidlParams {
    private ARoute route;          // null if no active route
    private long fingerprint;      // monotonically increasing route id; same
                                   // value within one navigate() session,
                                   // changes on reroute. Cheap dup-check.
    // getters/setters/writeToBundle/readFromBundle
}
```

### `ARoute`

The route export payload.

```java
// OsmAnd/OsmAnd-api/src/net/osmand/aidlapi/navigation/ARoute.java
public class ARoute extends AidlParams {
    private ArrayList<ALatLon> polyline;     // ordered, dense, full geometry
    private int totalDistanceM;
    private int totalTimeSec;                // OsmAnd's leftTime estimate at t=0
    private ArrayList<ARouteTurn> turns;     // ordered start → arrive
    // getters/setters/writeToBundle/readFromBundle
}
```

Reuses existing `net.osmand.aidlapi.map.ALatLon` for points — already AIDL-safe.

### `ARouteTurn`

Per-turn entry. Field set chosen to feed the existing
`dev.glass.protocol.Packet.TurnBundle` and `Packet.RouteStart` exactly, with
no phone-side synthesis.

```java
// OsmAnd/OsmAnd-api/src/net/osmand/aidlapi/navigation/ARouteTurn.java
public class ARouteTurn extends AidlParams {
    private int turnType;              // TurnType.getValue() — same int domain
                                       // as ADirectionInfo.turnType. Mapping to
                                       // protocol.TurnKind is done phone-side
                                       // by TurnTypeMapping.kt (glass-nav-b12).
    private String instructionText;    // e.g. "Turn left onto Reichstagufer"
                                       // — fully rendered, locale-aware,
                                       // matches voice router output.
    private String streetName;         // street/road name without verb
                                       // ("Reichstagufer"). May be empty.
    private int distanceFromStartM;    // cumulative distance from start
    private int distanceToNextTurnM;   // distance to the *next* turn (the leg
                                       // beginning at this turn). 0 for ARRIVE.
    private double lat;
    private double lon;
    private int exitNumber;            // roundabout exit, 0 if N/A
    // getters/setters/writeToBundle/readFromBundle
}
```

Note: `exitNumber` is included even though current `Packet.TurnBundle` does not
carry it — adding it costs nothing and avoids a second AIDL revision when we
eventually surface roundabout exits on Glass. The stock V2
`ADirectionInfo.turnType` strips exit info; this parcelable is the place to fix
that.

### `ANavigationProgressParams`

```java
// OsmAnd/OsmAnd-api/src/net/osmand/aidlapi/navigation/ANavigationProgressParams.java
public class ANavigationProgressParams extends AidlParams {
    private boolean subscribeToUpdates = true;
    private long callbackId = -1L;
    private long intervalMs = 1000L;   // min gap between callbacks
    // ... standard pattern
}
```

### `ANavigationProgress`

The 1 Hz payload. Replaces phone-side `RouteMatcher` + speed buffer math
(see `RideService.streamProgress`).

```java
// OsmAnd/OsmAnd-api/src/net/osmand/aidlapi/navigation/ANavigationProgress.java
public class ANavigationProgress extends AidlParams {
    private double currentLat;
    private double currentLon;
    private float bearingDeg;
    private float speedKmh;            // smoothed current speed
    private int remainingDistanceM;    // RoutingHelper.getLeftDistance()
    private int etaSec;                // RoutingHelper.getLeftTime()
    private int distanceToNextTurnM;   // next turn's distance
    private int nextTurnType;          // TurnType.getValue()
    private String nextTurnStreetName; // empty if unknown
    private int currentTurnIndex;      // index into ARoute.turns we're between
                                       // (i.e. heading toward turn N)
    private boolean isDeviated;        // true ⇒ off-route; expect onReroute()
    // ... standard pattern
}
```

### `ARerouteEvent`

```java
// OsmAnd/OsmAnd-api/src/net/osmand/aidlapi/navigation/ARerouteEvent.java
public class ARerouteEvent extends AidlParams {
    private long oldFingerprint;       // matches the AGetRouteParams.fingerprint
                                       // the client last saw
    private long newFingerprint;       // call getActiveRoute() to fetch the new
    private long timestampMs;
    // ... standard pattern
}
```

## 3. Versioning + capability detection

OsmAnd has no `getApiVersion()` AIDL method today (verified — no `VERSION`
constant on `IOsmAndAidlInterface.aidl`; `getOsmAndVersion` does not exist).
That makes a numeric version bump unenforceable across builds.

Strategy: **feature-detect via try/catch**, no version method needed.

Each new method is dispatched through the same `OsmandAidlServiceV2.Stub`
machinery as existing methods. Calling them on a stock OsmAnd build raises
either `android.os.TransactionTooLargeException` (transaction code not found)
or `UnsupportedOperationException` depending on Android version. The phone-side
client probes once per connection:

```kotlin
// phone-app/src/main/java/dev/glass/phone/osmand/OsmAndAidlClient.kt
suspend fun hasOptionCExtensions(): Boolean {
    val svc = service ?: return false
    return try {
        // No-op probe: getActiveRoute returns false when no route is active,
        // but the call survives transit if the method exists on the server.
        svc.getActiveRoute(AGetRouteParams())
        true
    } catch (_: NoSuchMethodError) { false }
      catch (_: android.os.RemoteException) {
          // Fallback: if RemoteException is thrown because the txn code is
          // unknown, treat as "no extensions". Real server errors will also
          // land here, but we re-probe on every connect anyway.
          false
      }
}
```

Default-fallback behaviour required by glass-nav-avi and glass-nav-c7v:

- `hasOptionCExtensions() == true` → use `getActiveRoute()` for route export
  and `registerForNavigationProgress()` for 1 Hz updates.
- `hasOptionCExtensions() == false` → keep the existing BRouter + RouteMatcher
  + ADirectionInfo path. Phone app must run unchanged on stock OsmAnd.

This is the only behaviourally correct strategy when both forked and stock
OsmAnd can be installed side-by-side: package name alone (see §4) tells us
which we bound to, but a fork user may still uninstall the fork and we want
the app to degrade gracefully, not crash.

Upstream PR concern: an `int getApiVersion()` could be added in the same PR
as a hardening, but the fallback path stays the same — version probes are
nice-to-have, not load-bearing.

## 4. Fork applicationId + manifest

### Fork applicationId

Add a new product flavor to `OsmAnd/OsmAnd/build.gradle` mirroring the
existing `nightlyFree` / `gplayFree` / `huawei` flavors:

```groovy
// OsmAnd/OsmAnd/build.gradle, productFlavors { … } block
glassnav {
    dimension "version"
    applicationId "net.osmand.glassnav"
    resValue "string", "app_name", "OsmAnd (Glass-Nav)"
    resValue "string", "app_edition", "glassnav"
}
```

Suffix rationale: `net.osmand.glassnav` follows the existing `net.osmand.dev`
/ `net.osmand.huawei` naming pattern, so it sorts and reads as "an OsmAnd
variant" rather than a third-party app. Keeps it obvious to anyone debugging
on the device that this is a patched OsmAnd, not a separate app pretending to
be one.

No source changes beyond the AIDL additions are required for the flavor to
build — `applicationId` is a packaging-time concern. Provider/component
authorities defined in the manifest with `${applicationId}` placeholders (the
default in modern OsmAnd) automatically pick up the new suffix.

### Phone-side wiring

Two files change on the phone:

1. `phone-app/src/main/java/dev/glass/phone/osmand/OsmAndAidlClient.kt`:
   add `"net.osmand.glassnav"` to `KNOWN_PACKAGES`. Order it first so that on
   a device with both the fork and stock OsmAnd installed we bind to the fork
   (and therefore get the extension methods) by default.

   ```kotlin
   val KNOWN_PACKAGES = listOf(
       "net.osmand.glassnav",   // patched fork (Option C); preferred when present
       "net.osmand.plus",
       "net.osmand",
       "net.osmand.dev",
       "net.osmand.huawei",
   )
   ```

2. `phone-app/src/main/AndroidManifest.xml`: add a `<package>` entry inside
   the existing `<queries>` block for Android 11+ visibility.

   ```xml
   <queries>
       <intent>
           <action android:name="net.osmand.aidl.OsmandAidlServiceV2" />
       </intent>
       <package android:name="net.osmand.glassnav" />
       <package android:name="net.osmand.plus" />
       <package android:name="net.osmand" />
       <package android:name="net.osmand.dev" />
       <package android:name="net.osmand.huawei" />
   </queries>
   ```

   The intent-action query alone is sufficient on most devices, but the
   per-package entries are belt-and-braces and match what the existing
   manifest already does for stock packages.

## 5. Build pipeline

The fork lives at `$rootDir/OsmAnd` (sibling checkout, not a submodule — see
the `osmand-api-module-points-at-osmand-checkout` memory). The patched APK is
built and installed separately from the glass-nav Gradle build.

### Build

OsmAnd uses three flavor dimensions: `version`, `coreversion`, `abi`. The
glassnav fork lives in the `version` dimension, so a full variant name is
`<version><coreversion><abi><buildType>`. For day-to-day development on an
arm device:

```bash
# from $rootDir/OsmAnd
./gradlew :OsmAnd:assembleGlassnavOpengldebugArmonlyDebug   # both arm ABIs, debug
./gradlew :OsmAnd:assembleGlassnavOpengldebugFatDebug       # all ABIs, debug
./gradlew :OsmAnd:assembleGlassnavOpenglArmonlyRelease      # release build
```

Use the `opengldebug` core variant for development — it skips the 3D OpenGL
release packaging step. `armonly` packs both 32-bit and 64-bit ARM into one
APK without adding x86, which keeps APK size manageable for Android phones
while remaining wide-device-compatible.

### Signing

`assembleGlassnavOpenglDebug` uses Android Gradle's auto-generated debug
keystore — no extra setup. Release builds require the keystore prerequisites
documented in `OsmAnd/keystores/` (separate concern; not blocking for
development).

### Install

```bash
# APK is under OsmAnd/OsmAnd/build/outputs/apk/<variantDirs>/<buildType>/
# Path segments are camelCase'd to flavorDir form, e.g. glassnav/opengldebug/armonly/debug
find OsmAnd/OsmAnd/build/outputs/apk -name '*glassnav*debug*.apk'
adb install -r <discovered.apk>

# verify
adb shell pm list packages | grep glassnav      # → package:net.osmand.glassnav
adb shell dumpsys package net.osmand.glassnav | grep -E 'versionName|firstInstallTime'
```

Side-by-side: installing this APK alongside `net.osmand` or `net.osmand.plus`
works without conflict because the applicationId is different. Both apps will
appear in the launcher.

### Map data

The fork shares OsmAnd's offline map storage layout, but data files live
under the app's private external storage (`/sdcard/Android/data/<pkg>/`) and
are *not* shared between flavor installs. After installing the fork, either:

- Trigger OsmAnd's first-run downloader from inside the fork app, or
- Copy `Android/data/net.osmand.plus/files/` →
  `Android/data/net.osmand.glassnav/files/` via `adb shell run-as` (only
  works on debug builds) or a rooted device.

For the spike phase, first-run downloader is the simpler path — a Berlin map
download is < 100 MB.

### Google Play Services

The `gplay*` flavors depend on Play Services. The new `glassnav` flavor is
modeled on `nightlyFree` (no Play Services dependency), so the fork builds
and runs on AOSP / non-GMS devices without changes. Confirm by inspecting
`OsmAnd/OsmAnd/build.gradle` for `flavorDimensions` — `glassnav` does not
need to opt into the Play Services product flavor specialization.

## 6. Upstream-PR plan

Target: **OsmAnd/OsmAnd** repo on GitHub, branch `master`. (No OsmAnd
develop branch is in active use for AIDL changes — the AIDL surface is
historically merged direct to master under the `aidl` label.)

Split the upstream change into reviewable commits:

1. New parcelables (pure additions; no behaviour change).
   - `OsmAnd-api/src/net/osmand/aidlapi/navigation/AGetRouteParams.java` + `.aidl`
   - `OsmAnd-api/src/net/osmand/aidlapi/navigation/ARoute.java` + `.aidl`
   - `OsmAnd-api/src/net/osmand/aidlapi/navigation/ARouteTurn.java` + `.aidl`
   - `OsmAnd-api/src/net/osmand/aidlapi/navigation/ANavigationProgress.java` + `.aidl`
   - `OsmAnd-api/src/net/osmand/aidlapi/navigation/ANavigationProgressParams.java` + `.aidl`
   - `OsmAnd-api/src/net/osmand/aidlapi/navigation/ARerouteEvent.java` + `.aidl`

2. AIDL interface additions (`IOsmAndAidlInterface.aidl`,
   `IOsmAndAidlCallback.aidl`) — additive only, no signature changes to
   existing methods. Existing clients keep compiling.

3. `OsmandAidlServiceV2.java` dispatcher: three new `@Override`s mirroring
   the existing `registerForNavigationUpdates` / `getOsmAndVersion`-style
   handlers (see `OsmandAidlServiceV2.java:1116` for the pattern).

4. `OsmandAidlApi.java`:
   - `getActiveRoute(AGetRouteParams)` → drain `RoutingHelper.getRoute()`
     for polyline (`getImmutableAllLocations()`) + iterate
     `routingHelper.getRoute().getRouteDirections()` for the turn list. The
     existing `ExternalApiHelper.getRouteDirectionsInfo()` is the closest
     prior art and is the template to follow.
   - `registerForNavigationProgress(long, long)` → add an
     `IRoutingDataUpdateListener` similar to the existing
     `registerForNavigationUpdates(long)` (`OsmandAidlApi.java:2045`),
     producing `ANavigationProgress` instead of `ADirectionInfo`. Honour the
     `intervalMs` debounce client-side at callback dispatch.
   - `registerForRerouteEvents(long)` → hook `RoutingHelper`'s recompute
     callback chain (`RoutingHelper.addCalculationProgressCallback` /
     `setOnRouteCalculated`).

### Code style

Match the existing OsmAnd-api conventions:
- Tabs, not spaces (see existing `*.java` files under `OsmAnd-api/`).
- Bundle-backed `AidlParams` subclasses; do *not* hand-write Parcelable
  marshalling — `AidlParams` already provides it.
- Field naming: lowerCamelCase; `set*` / `get*`; null-safe in `readFromBundle`.
- Javadoc on every new public AIDL method, written in OsmAnd's existing
  terse style (see `registerForNavigationUpdates` doc for tone).

### Fork-fallback plan

If upstream rejects or stalls, the `glassnav` flavor stays as our
permanent install target. The phone-app code path is identical either way
because the package-detection logic in §4 handles both ("fork is preferred
when present, stock is the fallback"). The only thing we lose by not
upstreaming is wider audience — there is no architectural cost.

## Acceptance checklist (per issue glass-nav-b2m)

- [x] AIDL method signatures spelled out for all three additions.
- [x] Parcelable field lists spelled out for all six new parcelables.
- [x] Versioning strategy documented (feature-detect; no version method
      needed); default-fallback behaviour specified.
- [x] Fork applicationId chosen: `net.osmand.glassnav`. Manifest `<queries>`
      additions + `KNOWN_PACKAGES` order specified.
- [x] Build pipeline: flavor name, gradle task, install command, signing
      prerequisites, map data caveat.
- [x] Upstream PR plan: target repo/branch, commit splits, file list,
      code-style notes, fork-fallback plan.

## Sub-task → contract map

| Sub-task | What it implements | Anchor in this doc |
|---|---|---|
| glass-nav-nzt | `getActiveRoute` + ARoute/ARouteTurn/AGetRouteParams parcelables + OsmandAidlApi wiring | §1, §2, §6 step 1+4 |
| glass-nav-ei1 | `registerForNavigationProgress` + ANavigationProgress(Params) + reroute event | §1, §2, §6 step 4 |
| glass-nav-45y | `glassnav` flavor in OsmAnd/build.gradle + build + install docs | §4 (applicationId), §5 |
| glass-nav-p9i | KNOWN_PACKAGES update + manifest `<queries>` + `hasOptionCExtensions()` probe | §3, §4 (phone wiring) |
| glass-nav-avi | Replace RideService.streamProgress speed-buffer with `onNavigationProgress` | §2 (ANavigationProgress shape), §3 (fallback) |
| glass-nav-c7v | Replace BRouter routing path with `navigate()` + `getActiveRoute()` | §1 (`getActiveRoute`), §3 (fallback) |
