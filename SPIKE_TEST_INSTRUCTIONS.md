# OsmAnd AIDL spike — single-session device test

Covers three open spike issues:

| Beads | Question | Output files |
|---|---|---|
| `glass-nav-hva` | Is `updateNavigationInfo` cadence + content sufficient to drive `Packet.Progress`? | `hva-cadence.csv`, `hva-summary.txt` |
| `glass-nav-0ha` | Are OsmAnd's `getBitmapForGpx` snippets usable in place of our `SnippetRenderer` output? | `0ha-snippets/turn-NN-{osmand,mapsforge}.png`, `0ha-summary.txt` |
| `glass-nav-51h` | Can `onVoiceRouterNotify` strings replace our Glass-side initial-direction composer? | `51h-voice.jsonl`, `51h-summary.txt` |

All three are captured in one device session via `SpikeSessionActivity`.

## One-time setup (before the session)

### 1. Install the build

```bash
./gradlew :phone-app:installDebug
```

### 2. Configure OsmAnd

- Open OsmAnd and **download the offline map** covering the test route.
  Default test route is Brandenburg Gate → Reichstag — Berlin map covers it.
  To test elsewhere, edit `routeStart`, `routeDest`, and `snippetTurns` in
  `phone-app/src/main/java/dev/glass/phone/osmand/spike/SpikeSessionActivity.kt`,
  and replace `phone-app/src/main/assets/spike-route.gpx` with a track covering
  the new region, then re-install.

OsmAnd's in-app "Simulate your position" feature is no longer needed — the spike
harness drives simulation programmatically via the `setLocation` AIDL using the
bundled GPX fixture. The "OsmAnd development" plugin no longer has to be enabled
for the spike to run.

### 3. (Optional) Mapsforge `.map` for side-by-side snippet comparison

`SpikeSnippetCapture` renders each turn twice — once via OsmAnd, once via our existing
`SnippetRenderer`. The second renderer needs a Mapsforge `.map` file.

```bash
adb shell mkdir -p /sdcard/glass-cycling/maps
adb push berlin.map /sdcard/glass-cycling/maps/
```

If the file is missing, OsmAnd-only PNGs are still captured and the summary notes
which side was skipped. The spike conclusion is weaker (no side-by-side) but still useful.

## The session (~5 minutes)

### 4. Launch the spike activity

```bash
adb shell am start -n dev.glass.phone/dev.glass.phone.osmand.spike.SpikeSessionActivity
```

You should see "OsmAnd installed: net.osmand.plus" (or whichever flavor you have)
in the header. If it says "NO", install OsmAnd or check the `<queries>` block in
`phone-app/src/main/AndroidManifest.xml`.

### 5. Run the capture

In order, in the spike activity:

1. **Tap "1. Setup (bind + make session dir)".**
   Log shows: `connect()=true, pkg=net.osmand.plus` (or similar) and the session
   dir path under `/sdcard/Android/data/dev.glass.phone/files/spikes/<timestamp>/`.

2. **Tap "2. Start capture (navigate + subscribe)".**
   Log shows: `navigateGpx()=true (<N> pts, snap-to-road off)` followed by
   `auto-sim: <N> pts from spike-route.gpx @ 4.0 m/s — pushing via setLocation`.
   The harness feeds the same GPX as both the canonical route (via `navigateGpx`
   with snap-to-road disabled) and the position trajectory (via `setLocation`),
   so OsmAnd never reports an off-route condition.

3. Watch the counters update in the spike activity:
   `Session: nav=<N> voice=<N>`. The bundled Berlin route is ~450 m, so at 4 m/s
   the sim runs ~2 minutes. Wait until it finishes (or longer if you increased
   `simSpeedMps`).

4. **Tap "3. Capture turn snippets".**
   Renders 5 OsmAnd PNGs (and 5 Mapsforge PNGs if the `.map` is available).
   Log shows: `snippets done: attempted=5 osm=<N> mf=<N>`.
   `osm=0` usually means OsmAnd's offline map isn't downloaded for that area —
   fix step 2 and retry.

5. **Tap "4. Stop & write summaries".**
   Stops simulation, unsubscribes, writes the three `*-summary.txt` files.

### 6. Pull results

```bash
adb pull /sdcard/Android/data/dev.glass.phone/files/spikes/
```

The session dir will land in `./spikes/<timestamp>/` on your workstation.

## Reviewing results

Read the three `*-summary.txt` files first — each contains explicit go/no-go
decision criteria.

### `hva-summary.txt` (cadence)

Look at:
- **`avg_hz`** — should be ≥ 1.0. The current phone-side `streamProgress`
  emits at 1 Hz; anything slower would degrade Glass updates.
- **`max_gap_ms`** — should stay under ~2000 ms. Large gaps would starve the display.
- **`monotonicity_anomalies`** — anything more than a handful suggests we'd need to
  add our own distance-to-turn smoothing.

If any of these fail, the spike conclusion is: ADirectionInfo alone is insufficient;
we'd need to also register `registerForUpdates` and reconstruct progress ourselves
(or escalate to Option C).

### `0ha-summary.txt` (snippet quality)

Open the PNGs in `0ha-snippets/` side-by-side. For each turn:
- Which image makes the next-intersection layout legible at Glass prism size?
- Is OsmAnd's road styling readable, or is it tuned for a phone screen and too busy?
- Are labels (street names) legible after downscaling?

Also check `osm_ms` per turn — IPC latency over a few hundred ms per snippet means
pre-rendering would stall the route-start moment. Bytes column should be roughly
comparable; >5× larger PNGs will hurt BT throughput when streaming TurnBundles.

If OsmAnd's snippets are visibly worse → escalate to **Option C** (patch OsmAnd to
add a `getMapSnapshot(lat, lon, zoom, w, h)` AIDL method).

### `51h-summary.txt` (voice)

Open `51h-voice.jsonl`. Each line has a `composed` field — OsmAnd's full sentence
joined with spaces. Compare with what our current Glass-side composer produces
(documented in the persistent memory `initial-direction-tts-on-glass-fires-from-packetdispatcher`):

> `'<start.instructionText> for <first.distanceFromStartM> meters, then <maneuver phrase>'`

If OsmAnd's phrasing reads naturally enough → repurpose the protocol to carry
pre-composed strings (option A); drop the Glass-side composer.

If OsmAnd's phrasing is awkward, partial, or fires at the wrong moment → keep our
composer and just use `ADirectionInfo` data (option B).

## Known fidelity gaps (worth verifying empirically)

These come from reading OsmAnd's AIDL impl, not from running the spike:

1. **`ADirectionInfo.isLeftSide` is never set by V2 AIDL** — always `false`. The
   `hva-cadence.csv` column `is_left_side_RAW` confirms it on real data; expect
   100% `false`.
2. **Roundabout exit number is dropped** — `setTurnType(getValue())` strips the
   `exitOut` field. The `turn_type_int` column will show `13` (RNDB) or `14` (RNLB)
   without a way to know which exit. `TurnTypeMapping` degrades these to `KL`/`KR`.

## Closing the issues

After review, close any spike whose answer is settled:

```bash
bd close glass-nav-hva --reason="…"
bd close glass-nav-0ha --reason="…"
bd close glass-nav-51h --reason="…"
```

Closing all three unblocks the epic `glass-nav-u1y` (the full migration of
`RideService` to consume OsmAnd events instead of the in-tree BRouter + Mapsforge
stack).
