package com.goodanser.osmglass.phone.osmand.spike

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.goodanser.osmglass.phone.osmand.OsmAndAidlClient
import com.goodanser.osmglass.phone.routing.LatLng
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-pass spike harness covering glass-nav-hva (cadence), glass-nav-0ha (snippet quality), and
 * glass-nav-51h (voice messages). All outputs go to a single timestamped session directory under
 *   /sdcard/Android/data/com.goodanser.osmglass.phone/files/spikes/<yyyyMMdd-HHmmss>/
 * which can be retrieved with:
 *   adb pull /sdcard/Android/data/com.goodanser.osmglass.phone/files/spikes/
 *
 * Launch:
 *   adb shell am start -n com.goodanser.osmglass.phone/com.goodanser.osmglass.phone.osmand.spike.SpikeSessionActivity
 *
 * Operator flow (single device session):
 *   1. Tap "Setup".  (binds AIDL, creates session dir)
 *   2. In OsmAnd: pre-download the test region's offline map. Optionally drop a Mapsforge .map
 *      file at /sdcard/glass-cycling/maps/ for the side-by-side render.
 *   3. Tap "Start capture".  (calls navigate(), subscribes to nav + voice, AND auto-drives the
 *      simulation by pushing positions via setLocation from assets/spike-route.gpx — replaces
 *      the unreliable in-OsmAnd "Simulate your position" UI step).
 *   4. Wait until the bundled route finishes (~2 minutes at default speed).
 *   5. Tap "Capture snippets".  (renders 5 turn snippets via both renderers)
 *   6. Tap "Stop".  (writes summary files)
 *   7. adb pull the spikes directory and review the *-summary.txt files.
 */
class SpikeSessionActivity : AppCompatActivity() {

    private val client by lazy { OsmAndAidlClient(applicationContext) }
    private lateinit var log: TextView
    private lateinit var counters: TextView
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dirStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private var recorder: SpikeRecorder? = null
    private var navJob: Job? = null
    private var voiceJob: Job? = null
    private var sessionDir: File? = null
    private var driver: GpxRouteDriver? = null

    /** Bundled fixture used to drive simulation via setLocation. See assets/spike-route.gpx. */
    private val simRouteAsset = "spike-route.gpx"
    private val simSpeedMps = 4f

    /**
     * The route fed to OsmAnd's navigate(). Brandenburg Gate → Reichstag is a tidy ~600 m walk
     * inside a small enough area that one Berlin .map covers it. Override by editing here.
     */
    private val routeStart = LatLng(52.5163, 13.3777)
    private val routeStartName = "Brandenburg Gate"
    private val routeDest = LatLng(52.5186, 13.3762)
    private val routeDestName = "Reichstag"
    private val routeProfile = "pedestrian"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        val pkgFound = OsmAndAidlClient.resolveOsmAndPackage(this)
        root.addView(TextView(this).apply {
            text = "OsmAnd installed: ${pkgFound ?: "NO — install OsmAnd first"}"
        })

        counters = TextView(this).apply {
            text = "Session: <not started>"
        }
        root.addView(counters)

        val btnSetup = Button(this).apply { text = "1. Setup (bind + make session dir)" }
        val btnStart = Button(this).apply { text = "2. Start capture (navigate + subscribe)" }
        val btnStop  = Button(this).apply { text = "3. Stop & write summaries" }
        val btnRouteOnly = Button(this).apply { text = "Trigger initial-direction TTS only" }

        listOf(btnSetup, btnStart, btnStop, btnRouteOnly).forEach { root.addView(it) }

        log = TextView(this).apply {
            text = ""
            gravity = Gravity.TOP
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
        }
        root.addView(log, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        setContentView(root)

        btnSetup.setOnClickListener { setup() }
        btnStart.setOnClickListener { startCapture() }
        btnStop.setOnClickListener { stopCapture() }
        btnRouteOnly.setOnClickListener { triggerNavigateOnly() }
    }

    override fun onDestroy() {
        navJob?.cancel(); voiceJob?.cancel()
        driver?.stop()
        recorder?.finish()
        client.disconnect()
        super.onDestroy()
    }

    private fun setup() {
        lifecycleScope.launch {
            val ok = client.connect()
            line("connect()=$ok, pkg=${client.osmAndPackage}")
            if (!ok) return@launch
            val base = getExternalFilesDir(null) ?: filesDir
            val dir = File(base, "spikes/${dirStamp.format(Date())}").apply { mkdirs() }
            sessionDir = dir
            writeReadme(dir)
            line("session dir: ${dir.absolutePath}")
            updateCounters()
        }
    }

    private fun startCapture() {
        val dir = sessionDir ?: run { line("call Setup first"); return }
        if (recorder != null) { line("already capturing"); return }
        recorder = SpikeRecorder(dir)

        navJob = client.navigationUpdates()
            .onEach {
                recorder?.onNavUpdate(it)
                updateCounters()
            }
            .launchIn(lifecycleScope)
        voiceJob = client.voiceRouterMessages()
            .onEach {
                recorder?.onVoice(it)
                updateCounters()
            }
            .launchIn(lifecycleScope)

        val (gpx, track) = runCatching { loadSimRoute() }
            .onFailure { line("startCapture aborted: $simRouteAsset (${it.message})") }
            .getOrNull() ?: return
        // Drop OsmAnd into GPX-navigation mode against the same polyline we will drive. With
        // snapToRoad=false (set in OsmAndAidlClient.navigateGpx), OsmAnd's off-route check is
        // measured against this exact track — eliminating the spurious "you have been off the
        // route" alerts we saw when navigate(start,dest) computed its own road-snapped path.
        val ok = client.navigateGpx(gpx)
        line("navigateGpx()=$ok (${track.size} pts, snap-to-road off)")
        startAutoSimulation(track)
    }

    private fun startAutoSimulation(track: List<LatLng>) {
        if (track.size < 2) {
            line("sim disabled: $simRouteAsset has ${track.size} points")
            return
        }
        val d = GpxRouteDriver(client, track, speedMps = simSpeedMps, tickHz = 1)
        driver = d
        line("auto-sim: ${track.size} pts from $simRouteAsset @ ${simSpeedMps} m/s — pushing via setLocation")
        d.start(lifecycleScope)
    }

    private fun loadSimRoute(): Pair<String, List<LatLng>> {
        val gpx = assets.open(simRouteAsset).bufferedReader().use { it.readText() }
        val track = GpxRouteDriver.parseGpx(gpx.byteInputStream())
        return gpx to track
    }

    private fun triggerNavigateOnly() {
        val ok = client.navigate(
            startLat = routeStart.lat, startLon = routeStart.lon, startName = routeStartName,
            destLat = routeDest.lat, destLon = routeDest.lon, destName = routeDestName,
            profile = routeProfile,
        )
        line("navigate()=$ok (route only, no recording)")
    }

    private fun stopCapture() {
        lifecycleScope.launch {
            navJob?.cancel(); voiceJob?.cancel()
            navJob = null; voiceJob = null
            driver?.stop(); driver = null
            val ok = client.stopNavigation()
            line("stopNavigation()=$ok")
            recorder?.finish()
            recorder = null
            line("summaries written to ${sessionDir?.absolutePath}")
            line("pull with: adb pull ${sessionDir?.absolutePath?.replace("/storage/emulated/0", "/sdcard")}")
        }
    }

    private fun updateCounters() {
        val r = recorder
        runOnUiThread {
            counters.text = if (r == null) {
                "Session: not capturing"
            } else {
                "Session: nav=${r.navEvents} voice=${r.voiceEvents}"
            }
        }
    }

    private fun line(msg: String) {
        runOnUiThread { log.append("[${ts.format(Date())}] $msg\n") }
    }

    private fun writeReadme(dir: File) {
        File(dir, "README.md").writeText("""
            |# Spike capture session — ${dirStamp.format(Date())}
            |
            |Covers: glass-nav-hva (cadence), glass-nav-0ha (snippet quality), glass-nav-51h (voice).
            |
            |## Files
            |
            |- `hva-cadence.csv` — every updateNavigationInfo event, with gap, distanceTo, turnType,
            |  TurnKind mapping, the unused isLeftSide field, and an off-route flag.
            |- `hva-summary.txt` — derived stats + decision criteria.
            |- `51h-voice.jsonl` — every onVoiceRouterNotify event with the cmds/played arrays plus a
            |  pre-joined `composed` string suitable for direct TTS.
            |- `51h-summary.txt` — derived stats + decision criteria.
            |
            |## Operator checklist
            |
            |1. In OsmAnd:
            |   - Download the offline map covering the test route.
            |   - Enable Settings → Plugins → OsmAnd development.
            |   - (Optional for side-by-side) Drop a Mapsforge .map for the same region at
            |     /sdcard/glass-cycling/maps/ — same dir convention as the live phone-app.
            |2. In this app:
            |   - Tap **Setup**. Confirm "OsmAnd installed: …" shows a non-null package.
            |   - Tap **Start capture**. The harness calls navigateGpx() against
            |     assets/spike-route.gpx (snap-to-road off, so OsmAnd's off-route check is
            |     measured against the same polyline we drive) and pushes positions via
            |     setLocation. No need to touch OsmAnd's "Simulate your position" UI.
            |3. Wait until the route finishes (~2 minutes at 4 m/s for the bundled Berlin route).
            |4. In this app:
            |   - Tap **Capture turn snippets** (any time after Start; runs against OsmAnd's
            |     currently-loaded map).
            |   - Tap **Stop & write summaries**.
            |5. Pull results:
            |   ```
            |   adb pull /sdcard/Android/data/com.goodanser.osmglass.phone/files/spikes/
            |   ```
            |6. Review the three `*-summary.txt` files first — they contain the decision criteria.
            |
        """.trimMargin())
    }
}
