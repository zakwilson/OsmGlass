package com.goodanser.osmglass.phone.osmand

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug screen for the OsmAnd AIDL spike (glass-nav-egz).
 *
 * Not part of the production user flow. Launch via:
 *   adb shell am start -n com.goodanser.osmglass.phone/com.goodanser.osmglass.phone.osmand.OsmAndAidlDebugActivity
 *
 * Acceptance: bind succeeds → navigate() returns true → updateNavigationInfo callbacks logged
 * at ≥1 Hz during a simulated ride (use OsmAnd's "Simulate your position" developer option).
 */
class OsmAndAidlDebugActivity : AppCompatActivity() {

    private val client by lazy { OsmAndAidlClient(applicationContext) }
    private lateinit var log: TextView
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var updateJob: Job? = null
    private var voiceJob: Job? = null
    private var progressJob: Job? = null
    private var rerouteJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        val pkgFound = OsmAndAidlClient.resolveOsmAndPackage(this)
        root.addView(TextView(this).apply {
            text = "OsmAnd installed: ${pkgFound ?: "NO"}"
        })

        val connect = Button(this).apply { text = "Bind to OsmAnd" }
        val navigate = Button(this).apply { text = "navigate() Brandenburg Gate" }
        val stop = Button(this).apply { text = "stopNavigation()" }
        val subscribe = Button(this).apply { text = "Subscribe nav updates" }
        val voice = Button(this).apply { text = "Subscribe voice msgs" }
        val hasExt = Button(this).apply { text = "hasOptionCExtensions()?" }
        val getRoute = Button(this).apply { text = "getActiveRoute()" }
        val progress = Button(this).apply { text = "Subscribe nav progress (fork)" }
        val reroute = Button(this).apply { text = "Subscribe reroute events (fork)" }
        val disconnect = Button(this).apply { text = "Unbind" }

        listOf(
            connect, navigate, stop, subscribe, voice,
            hasExt, getRoute, progress, reroute, disconnect,
        ).forEach { root.addView(it) }

        log = TextView(this).apply {
            text = ""
            gravity = Gravity.TOP
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
        }
        root.addView(log, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        lifecycleScope.launch {
            client.connectionState.collect { line("state=$it") }
        }

        connect.setOnClickListener {
            lifecycleScope.launch { line("connect()=${client.connect()}; pkg=${client.osmAndPackage}") }
        }
        navigate.setOnClickListener {
            // Brandenburg Gate → Reichstag, ~600 m walk.
            val ok = client.navigate(
                startLat = 52.5163, startLon = 13.3777, startName = "Brandenburg Gate",
                destLat = 52.5186, destLon = 13.3762, destName = "Reichstag",
                profile = "pedestrian",
            )
            line("navigate()=$ok")
        }
        stop.setOnClickListener { line("stopNavigation()=${client.stopNavigation()}") }

        subscribe.setOnClickListener {
            updateJob?.cancel()
            updateJob = client.navigationUpdates()
                .onEach { line("dir distTo=${it.distanceTo} turnType=${it.turnType} L=${it.isLeftSide}") }
                .launchIn(lifecycleScope)
            line("subscribed nav updates")
        }
        voice.setOnClickListener {
            voiceJob?.cancel()
            voiceJob = client.voiceRouterMessages()
                .onEach { line("voice cmds=${it.commands} played=${it.played}") }
                .launchIn(lifecycleScope)
            line("subscribed voice")
        }
        hasExt.setOnClickListener {
            lifecycleScope.launch { line("hasOptionCExtensions()=${client.hasOptionCExtensions()}") }
        }
        getRoute.setOnClickListener {
            val r = client.getActiveRoute()
            if (r == null) {
                line("getActiveRoute()=null (no route or stock OsmAnd)")
            } else {
                val (route, fp) = r
                line("getActiveRoute() polyline=${route.polyline.size}pts dist=${route.totalDistanceM}m " +
                    "eta=${route.totalTimeSec}s turns=${route.turns.size} fp=$fp")
                route.polyline.firstOrNull()?.let { line("  poly[0]=(${it.latitude},${it.longitude})") }
                route.polyline.lastOrNull()?.let { line("  poly[N]=(${it.latitude},${it.longitude})") }
                var prev = -1
                var monotonic = true
                route.turns.forEachIndexed { i, t ->
                    if (t.distanceFromStartM < prev) monotonic = false
                    prev = t.distanceFromStartM
                    if (i < 5 || i >= route.turns.size - 2) {
                        line("  turn[$i] type=${t.turnType} from=${t.distanceFromStartM}m " +
                            "next=${t.distanceToNextTurnM}m '${t.instructionText}' street='${t.streetName}'")
                    } else if (i == 5 && route.turns.size > 7) {
                        line("  ... (${route.turns.size - 7} more)")
                    }
                }
                line("  monotonic distanceFromStartM: ${if (monotonic) "OK" else "FAIL"}")
                line("  polyline non-empty: ${if (route.polyline.isNotEmpty()) "OK" else "FAIL"}")
                line("  totalDistanceM>0: ${if (route.totalDistanceM > 0) "OK" else "FAIL"}")
                line("  turns non-empty: ${if (route.turns.isNotEmpty()) "OK" else "FAIL"}")
            }
        }
        progress.setOnClickListener {
            progressJob?.cancel()
            progressJob = client.navigationProgress(intervalMs = 1_000L)
                .onEach {
                    line("progress lat=${it.currentLat} lon=${it.currentLon} " +
                        "spd=${it.speedKmh}km/h rem=${it.remainingDistanceM}m eta=${it.etaSec}s " +
                        "nextTurn=${it.nextTurnType}@${it.distanceToNextTurnM}m '${it.nextTurnStreetName}' " +
                        "idx=${it.currentTurnIndex} dev=${it.isDeviated}")
                }
                .launchIn(lifecycleScope)
            line("subscribed nav progress")
        }
        reroute.setOnClickListener {
            rerouteJob?.cancel()
            rerouteJob = client.rerouteEvents()
                .onEach { line("reroute old=${it.oldFingerprint} new=${it.newFingerprint} ts=${it.timestampMs}") }
                .launchIn(lifecycleScope)
            line("subscribed reroute events")
        }
        disconnect.setOnClickListener {
            updateJob?.cancel(); voiceJob?.cancel()
            progressJob?.cancel(); rerouteJob?.cancel()
            client.disconnect()
        }
    }

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
    }

    private fun line(msg: String) {
        Log.i("OsmAndAidlDebug", msg)
        runOnUiThread { log.append("[${ts.format(Date())}] $msg\n") }
    }
}
