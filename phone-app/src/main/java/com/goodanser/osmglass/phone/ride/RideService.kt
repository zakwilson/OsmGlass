package com.goodanser.osmglass.phone.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.goodanser.osmglass.phone.R
import com.goodanser.osmglass.phone.gps.GpsSource
import com.goodanser.osmglass.phone.gps.MockGpsSource
import com.goodanser.osmglass.phone.gps.RealGpsSource
import com.goodanser.osmglass.phone.osmand.OsmAndAidlClient
import com.goodanser.osmglass.phone.osmand.OsmAndSnippetRenderer
import com.goodanser.osmglass.phone.osmand.SnippetBounds
import com.goodanser.osmglass.phone.routing.LatLng
import com.goodanser.osmglass.phone.routing.RoutingException
import com.goodanser.osmglass.phone.routing.Turn
import com.goodanser.osmglass.phone.routing.computeOsmAndRoute
import com.goodanser.osmglass.phone.routing.glyph
import com.goodanser.osmglass.phone.routing.NavigationMode
import com.goodanser.osmglass.phone.transport.TransportFactory
import com.goodanser.osmglass.phone.ui.DisplayPrefs
import com.goodanser.osmglass.phone.ui.RideViewModel
import com.goodanser.osmglass.protocol.Packet
import com.goodanser.osmglass.protocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.osmand.aidlapi.navigation.ANavigationProgress

/**
 * Foreground service. Owns: route state, transport, GPS source.
 *
 * Pipeline: gpsSource → routeMatcher → progressEmitter → transport.send.
 * On startup: pre-renders all turn snippets, sends ROUTE_START + N×TURN_BUNDLE,
 * then begins streaming PROGRESS at 1 Hz while moving.
 */
class RideService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transport: Transport? = null
    private var pipelineJob: Job? = null
    @Volatile private var currentRoute: RideViewModel.RouteState.Ready? = null
    @Volatile private var currentRouteId: Long = 0L
    @Volatile private var connected: Boolean = false
    private val speedSamples = ArrayDeque<Int>(SPEED_BUFFER_CAPACITY)
    /** Snippet bounds keyed by turn index, captured at pushRoute. Used per Progress to project
     *  the live position arrow into the cached TurnBundle's bitmap. */
    @Volatile private var snippetBoundsByTurn: List<SnippetBounds?> = emptyList()

    interface UiObserver {
        fun onConnectionStateChange(connected: Boolean, status: String)
        fun onLocationUpdate(location: LatLng, bearingDeg: Float?) {}
        /** Non-null message while reroute is in flight or just completed; null clears it. */
        fun onRerouteStateChange(message: String?) {}
        /** Called after a successful reroute so the phone UI can swap its on-screen polyline. */
        fun onRouteReplaced(route: RideViewModel.RouteState.Ready) {}
        /** Full progress snapshot — phone UI uses this to render any of the configurable fields. */
        fun onProgressUpdate(progress: Progress) {}
    }

    /** Snapshot of derived progress fields, kept in sync with what we send to Glass. */
    data class Progress(
        val turnInstruction: String,
        val distanceToTurnM: Int,
        val remainingDistanceM: Int,
        val etaSec: Int,
        val speedKmh: Int,
    )

    private sealed class StreamOutcome {
        object Arrived : StreamOutcome()
        object SourceEnded : StreamOutcome()
        data class OffRoute(val from: LatLng) : StreamOutcome()
    }

    private class StopCollection(val outcome: StreamOutcome) : RuntimeException()

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat(buildNotification())
        when (val r = TransportFactory.create(applicationContext)) {
            is TransportFactory.CreateResult.Failed -> {
                Log.w(TAG, "transport unavailable: ${r.reason}")
                uiObserver?.onConnectionStateChange(false, r.reason)
            }
            is TransportFactory.CreateResult.Ok -> {
                Log.i(TAG, "transport: ${r.description}")
                uiObserver?.onConnectionStateChange(false, "Connecting via ${r.description}…")
                val t = r.transport
                transport = t
                t.setListener(object : Transport.Listener {
                    override fun onConnected() {
                        Log.i(TAG, "transport connected")
                        connected = true
                        uiObserver?.onConnectionStateChange(true, "Connected: ${r.description}")
                        pushDisplayConfig(t)
                        val route = pendingRoute
                        if (route != null) startPipeline(t, route)
                    }
                    override fun onPacket(p: Packet) {
                        // Phone is the receiver; Glass currently sends no packets. Ping/Pong is
                        // handled inside the Transport (see Keepalive) and is not surfaced here.
                    }
                    override fun onDisconnected(cause: Throwable?) {
                        val msg = cause?.message ?: "clean EOF"
                        Log.w(TAG, "transport disconnected: $msg")
                        connected = false
                        uiObserver?.onConnectionStateChange(false, "Disconnected: $msg")
                        pipelineJob?.cancel()
                        pipelineJob = null
                    }
                })
                try { t.start() } catch (e: Exception) {
                    Log.w(TAG, "transport.start failed", e)
                    uiObserver?.onConnectionStateChange(false, "Start failed: ${e.message ?: "?"}")
                }
            }
        }
    }

    private fun startPipeline(t: Transport, initialRoute: RideViewModel.RouteState.Ready) {
        pipelineJob?.cancel()
        currentRoute = initialRoute
        speedSamples.clear()
        pipelineJob = scope.launch {
            var route = initialRoute
            var routeId = freshRouteId().also { currentRouteId = it }
            try {
                pushDisplayConfig(t)
                pushRoute(t, routeId, route)
                while (true) {
                    when (val outcome = streamProgress(t, routeId, route)) {
                        is StreamOutcome.Arrived,
                        is StreamOutcome.SourceEnded -> {
                            t.send(Packet.RouteEnd(routeId, Packet.RouteEnd.Reason.ARRIVED))
                            return@launch
                        }
                        is StreamOutcome.OffRoute -> {
                            uiObserver?.onRerouteStateChange("Off route — recomputing…")
                            val newRoute = try {
                                computeReroute(route, outcome.from)
                            } catch (e: RoutingException) {
                                Log.w(TAG, "reroute failed: ${e.message}")
                                null
                            } catch (e: Exception) {
                                Log.w(TAG, "reroute failed", e)
                                null
                            }
                            if (newRoute == null) {
                                uiObserver?.onRerouteStateChange("Reroute failed — retrying in ${REROUTE_COOLDOWN_MS / 1000}s")
                                delay(REROUTE_COOLDOWN_MS)
                                uiObserver?.onRerouteStateChange(null)
                                // Re-enter streamProgress with the same (stale) route; a fresh
                                // RouteMatcher is constructed inside, so the off-route counter
                                // restarts and we won't immediately re-fire on the same fix.
                                continue
                            }
                            t.send(Packet.RouteEnd(routeId, Packet.RouteEnd.Reason.OFFROUTE))
                            route = newRoute
                            routeId = freshRouteId().also { currentRouteId = it }
                            currentRoute = route
                            pushRoute(t, routeId, route)
                            uiObserver?.onRouteReplaced(route)
                            uiObserver?.onRerouteStateChange(null)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pipeline failed", e)
            }
        }
    }

    private fun freshRouteId(): Long = System.currentTimeMillis() and 0xffffffffL

    /** Push the current phone-side DisplayPrefs to Glass. Safe to call any time the transport is up. */
    private fun pushDisplayConfig(t: Transport) {
        try {
            val slots = DisplayPrefs.get(applicationContext)
            t.send(Packet.DisplayConfig(slots.glassTop, slots.glassBottom, slots.glassMuteTts))
        } catch (e: Exception) {
            Log.w(TAG, "send DisplayConfig failed", e)
        }
    }

    /**
     * Append a speed sample to the rolling buffer. Keeps the most recent
     * [SPEED_BUFFER_CAPACITY] samples (~30 s at 1 Hz). Ignored when speed is zero so a paused
     * rider doesn't drag the average toward an unrealistic infinity-ETA.
     */
    private fun recordSpeedSample(speedKmh: Int) {
        if (speedKmh <= 0) return
        speedSamples.addLast(speedKmh)
        while (speedSamples.size > SPEED_BUFFER_CAPACITY) speedSamples.removeFirst()
    }

    /**
     * Best estimate of cruising speed in km/h. Uses the rolling average once we have at least
     * [SPEED_BUFFER_MIN_SAMPLES] non-zero samples; otherwise falls back to the mode default.
     */
    private fun estimatedSpeedKmh(mode: NavigationMode): Float {
        return if (speedSamples.size >= SPEED_BUFFER_MIN_SAMPLES) {
            speedSamples.average().toFloat()
        } else modeDefaultSpeedKmh(mode)
    }

    private suspend fun computeReroute(
        current: RideViewModel.RouteState.Ready,
        from: LatLng,
    ): RideViewModel.RouteState.Ready {
        val computed = computeOsmAndRoute(
            context = applicationContext,
            start = from,
            end = current.destination.location,
            mode = current.mode,
            destName = current.destination.displayName,
        )
        Log.i(TAG, "rerouted via OsmAnd: ${computed.track.size} pts, ${computed.turns.size} turns")
        return current.copy(origin = from, track = computed.track, turns = computed.turns)
    }

    private suspend fun pushRoute(t: Transport, routeId: Long, route: RideViewModel.RouteState.Ready) {
        t.send(Packet.RouteStart(routeId, route.turns.size, route.destination.displayName))
        Log.i(TAG, "sent ROUTE_START id=$routeId turns=${route.turns.size}")
        val snippets = OsmAndSnippetRenderer(applicationContext).render(route.turns, route.track)
        snippetBoundsByTurn = route.turns.indices.map { snippets.getOrNull(it)?.bounds }
        for ((idx, turn) in route.turns.withIndex()) {
            val snippet = snippets.getOrNull(idx)
            val png = snippet?.pngBytes ?: EMPTY_BYTES
            t.send(
                Packet.TurnBundle(
                    routeId, idx, turn.kind,
                    turn.distanceFromStartM,
                    turn.instruction,
                    png,
                ),
            )
            Log.d(TAG, "sent TURN_BUNDLE #$idx (${turn.kind}, ${png.size}B)")
        }
    }

    /**
     * Project the rider's current geographic position onto the snippet bitmap for [turnIndex].
     * Returns null marker fields if no bounds are known for this turn (e.g. the snippet window
     * was empty). When the rider falls outside the bitmap, falls back to the polyline's entry
     * point (the start of the route line within the snippet), as requested by the marker spec.
     */
    private fun computeMarker(
        turnIndex: Int,
        lat: Double,
        lon: Double,
        bearingDeg: Float?,
    ): Triple<Int, Int, Int> {
        val bounds = snippetBoundsByTurn.getOrNull(turnIndex)
            ?: return Triple(Packet.Progress.MARKER_NONE, Packet.Progress.MARKER_NONE, Packet.Progress.MARKER_NONE)
        val live = bounds.project(lat, lon)
        return if (live.inBounds) {
            val bearing = bearingDeg?.let { wrap360(it.toDouble()) } ?: bounds.startBearingDeg
            Triple(
                live.x.toInt().coerceIn(0, bounds.widthPx - 1),
                live.y.toInt().coerceIn(0, bounds.heightPx - 1),
                (wrap360(bearing) * 100.0).toInt().coerceIn(0, 35_999),
            )
        } else {
            val fallback = bounds.project(bounds.startLat, bounds.startLon)
            Triple(
                fallback.x.toInt().coerceIn(0, bounds.widthPx - 1),
                fallback.y.toInt().coerceIn(0, bounds.heightPx - 1),
                (wrap360(bounds.startBearingDeg) * 100.0).toInt().coerceIn(0, 35_999),
            )
        }
    }

    private fun wrap360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /**
     * Try to bind to OsmAnd and run the fork-driven progress path; on stock OsmAnd, OsmAnd not
     * installed, or any bind failure, fall back to the phone-GPS + RouteMatcher path. The fork
     * path sources speed/remaining/ETA/turn fields directly from OsmAnd; the fallback retains
     * today's behaviour.
     */
    private suspend fun streamProgress(
        t: Transport,
        routeId: Long,
        route: RideViewModel.RouteState.Ready,
    ): StreamOutcome {
        val osmClient = OsmAndAidlClient(applicationContext)
        val useFork = try {
            osmClient.connect() && osmClient.hasOptionCExtensions()
        } catch (e: Throwable) {
            Log.w(TAG, "OsmAnd fork probe failed; falling back to GPS pipeline", e)
            false
        }
        if (!useFork) {
            try { osmClient.disconnect() } catch (_: Throwable) {}
            return streamProgressFromGps(t, routeId, route)
        }
        return try {
            streamProgressFromFork(t, routeId, route, osmClient)
        } finally {
            try { osmClient.disconnect() } catch (_: Throwable) {}
        }
    }

    private suspend fun streamProgressFromGps(
        t: Transport,
        routeId: Long,
        route: RideViewModel.RouteState.Ready,
    ): StreamOutcome {
        val source = currentGpsSource(route)
        val matcher = RouteMatcher(route.track, route.turns)
        val totalDistanceM = route.turns.last().distanceFromStartM
        var lastTurnIdx = -1
        try {
            source.fixes().collect { fix ->
                val match = matcher.match(fix.location)
                uiObserver?.onLocationUpdate(fix.location, fix.bearingDeg)
                if (match.offRoute) {
                    Log.i(TAG, "off-route by ${match.perpendicularDistanceM.toInt()}m — reroute")
                    throw StopCollection(StreamOutcome.OffRoute(fix.location))
                }
                val speedKmh = ((fix.speedMps ?: 0f) * 3.6f).toInt().coerceIn(0, 0xffff)
                recordSpeedSample(speedKmh)
                val remainingM = (totalDistanceM - match.distanceFromStartM)
                    .coerceIn(0, 0xffff)
                val estSpeed = estimatedSpeedKmh(route.mode).coerceAtLeast(0.1f)
                val etaSec = ((remainingM.toFloat() / 1000f) / estSpeed * 3600f)
                    .toInt().coerceIn(0, 0xffff)
                val (markerX, markerY, markerBearing) = computeMarker(
                    match.nextTurnIndex, fix.location.lat, fix.location.lon, fix.bearingDeg)
                t.send(
                    Packet.Progress(
                        routeId,
                        match.nextTurnIndex,
                        match.distanceToTurnM.coerceAtMost(0xffff),
                        bearingDelta(fix, route.turns.getOrNull(match.nextTurnIndex)),
                        speedKmh,
                        remainingM,
                        etaSec,
                        markerX, markerY, markerBearing,
                    ),
                )
                if (match.nextTurnIndex != lastTurnIdx) lastTurnIdx = match.nextTurnIndex
                val turn = route.turns[match.nextTurnIndex]
                uiObserver?.onProgressUpdate(
                    Progress(
                        turnInstruction = turn.instruction.ifBlank { turn.kind.glyph() },
                        distanceToTurnM = match.distanceToTurnM,
                        remainingDistanceM = remainingM,
                        etaSec = etaSec,
                        speedKmh = speedKmh,
                    ),
                )
                if (match.distanceToTurnM == 0 && match.nextTurnIndex == route.turns.lastIndex) {
                    Log.i(TAG, "arrived")
                    throw StopCollection(StreamOutcome.Arrived)
                }
            }
        } catch (stop: StopCollection) {
            return stop.outcome
        }
        return StreamOutcome.SourceEnded
    }

    /**
     * Fork path: Packet.Progress fields come from OsmAnd's [OsmAndAidlClient.navigationProgress]
     * callback (speedKmh, remainingDistanceM, etaSec, distanceToNextTurnM, currentTurnIndex,
     * isDeviated). Phone GPS keeps powering the UI map dot via [UiObserver.onLocationUpdate];
     * the phone-side RouteMatcher + speed buffer are bypassed.
     */
    private suspend fun streamProgressFromFork(
        t: Transport,
        routeId: Long,
        route: RideViewModel.RouteState.Ready,
        osmClient: OsmAndAidlClient,
    ): StreamOutcome = coroutineScope {
        val gpsJob = launch {
            try {
                currentGpsSource(route).fixes().collect { fix ->
                    uiObserver?.onLocationUpdate(fix.location, fix.bearingDeg)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "phone GPS (UI dot) source ended: ${e.message}")
            }
        }
        // Latest turn index seen on the OsmAnd progress flow; used to label TurnAlerts that
        // fire from OsmAnd's voice router (which itself doesn't carry an index).
        var latestTurnIndex = 0
        val voiceJob = launch {
            try {
                osmClient.voiceRouterMessages().collect { params ->
                    // Any voice prompt during navigation is a reason to wake the Glass.
                    // OsmAnd's prompts include "in X meters, turn …" cues that fire well before
                    // the rider crosses the dispatcher's distance threshold.
                    val cmds = try { params.commands } catch (_: Throwable) { null }
                    Log.i(TAG, "OsmAnd voice prompt — sending TurnAlert(#$latestTurnIndex) cmds=$cmds")
                    try {
                        t.send(Packet.TurnAlert(routeId, latestTurnIndex))
                    } catch (e: Exception) {
                        Log.w(TAG, "send TurnAlert failed", e)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "voice router subscription ended: ${e.message}")
            }
        }
        val outcome: StreamOutcome = try {
            var finalOutcome: StreamOutcome = StreamOutcome.SourceEnded
            try {
                osmClient.navigationProgress(intervalMs = 1_000L).collect { p ->
                    if (p.isDeviated) {
                        Log.i(TAG, "off-route (OsmAnd) — reroute")
                        throw StopCollection(StreamOutcome.OffRoute(LatLng(p.currentLat, p.currentLon)))
                    }
                    val turnIdx = if (p.currentTurnIndex in route.turns.indices) p.currentTurnIndex
                                  else route.turns.lastIndex.coerceAtLeast(0)
                    latestTurnIndex = turnIdx
                    val nextTurn = route.turns.getOrNull(turnIdx)
                    val speedKmh = p.speedKmh.toInt().coerceIn(0, 0xffff)
                    val remainingM = p.remainingDistanceM.coerceIn(0, 0xffff)
                    val etaSec = p.etaSec.coerceIn(0, 0xffff)
                    val distToTurnM = p.distanceToNextTurnM.coerceIn(0, 0xffff)
                    val (markerX, markerY, markerBearing) = computeMarker(
                        turnIdx, p.currentLat, p.currentLon, p.bearingDeg)
                    t.send(
                        Packet.Progress(
                            routeId,
                            turnIdx,
                            distToTurnM,
                            forkBearingDelta(p, nextTurn),
                            speedKmh,
                            remainingM,
                            etaSec,
                            markerX, markerY, markerBearing,
                        ),
                    )
                    uiObserver?.onProgressUpdate(
                        Progress(
                            turnInstruction = nextTurn?.let { it.instruction.ifBlank { it.kind.glyph() } } ?: "",
                            distanceToTurnM = distToTurnM,
                            remainingDistanceM = remainingM,
                            etaSec = etaSec,
                            speedKmh = speedKmh,
                        ),
                    )
                    if (turnIdx == route.turns.lastIndex && distToTurnM == 0) {
                        Log.i(TAG, "arrived (OsmAnd)")
                        throw StopCollection(StreamOutcome.Arrived)
                    }
                }
            } catch (stop: StopCollection) {
                finalOutcome = stop.outcome
            }
            finalOutcome
        } finally {
            gpsJob.cancel()
            voiceJob.cancel()
        }
        outcome
    }

    /**
     * Bearing delta in centidegrees between OsmAnd's reported heading and the bearing from the
     * current position to [nextTurn]. Returns 0 if any input is missing.
     */
    private fun forkBearingDelta(p: ANavigationProgress, nextTurn: Turn?): Short {
        if (nextTurn == null) return 0
        val to = bearingFrom(LatLng(p.currentLat, p.currentLon), LatLng(nextTurn.lat, nextTurn.lon))
        var diff = to - p.bearingDeg
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        return (diff * 100).toInt().coerceIn(-32_000, 32_000).toShort()
    }

    private fun currentGpsSource(route: RideViewModel.RouteState.Ready): GpsSource {
        // Prefer MockGpsSource for the demo / emulator (real GPS requires permission grant + a real signal).
        // Override by installing a mock as MOCK_OVERRIDE before starting.
        return MOCK_OVERRIDE ?: try {
            RealGpsSource(applicationContext)
        } catch (t: Throwable) {
            MockGpsSource(route.track)
        }
    }

    private fun bearingDelta(fix: GpsSource.Fix, nextTurn: Turn?): Short {
        if (nextTurn == null || fix.bearingDeg == null) return 0
        val to = bearingFrom(LatLng(fix.location.lat, fix.location.lon), LatLng(nextTurn.lat, nextTurn.lon))
        var diff = to - fix.bearingDeg
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        return (diff * 100).toInt().coerceIn(-32_000, 32_000).toShort()
    }

    private fun bearingFrom(a: LatLng, b: LatLng): Float {
        val φ1 = Math.toRadians(a.lat)
        val φ2 = Math.toRadians(b.lat)
        val Δλ = Math.toRadians(b.lon - a.lon)
        val y = Math.sin(Δλ) * Math.cos(φ2)
        val x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ)
        var θ = Math.toDegrees(Math.atan2(y, x))
        if (θ < 0) θ += 360.0
        return θ.toFloat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        scope.cancel()
        try { transport?.stop() } catch (_: Throwable) {}
        transport = null
        pendingRoute = null
        currentRoute = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ride_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(ch)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.ride_notification_title))
            .setContentText(getString(R.string.ride_notification_text))
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val TAG = "RideService"
        private const val NOTIFICATION_ID = 0xCAFE
        private const val CHANNEL_ID = "ride"
        private const val REROUTE_COOLDOWN_MS = 5_000L
        private val EMPTY_BYTES = ByteArray(0)
        /** Roughly 30 seconds at 1 Hz fixes — long enough to smooth traffic-light pauses without
         *  lagging too far behind a real speed change. */
        private const val SPEED_BUFFER_CAPACITY = 30
        /** Below this we don't trust the rolling average and use the mode default instead. */
        private const val SPEED_BUFFER_MIN_SAMPLES = 5

        private fun modeDefaultSpeedKmh(mode: NavigationMode): Float = when (mode) {
            NavigationMode.WALKING -> 5f
            NavigationMode.CYCLING -> 15f
            NavigationMode.DRIVING -> 50f
        }

        @Volatile var pendingRoute: RideViewModel.RouteState.Ready? = null
        @Volatile var uiObserver: UiObserver? = null
        @Volatile private var instance: RideService? = null

        /** Test-only override to inject a synthetic GpsSource without setting up Real or Mock from scratch. */
        @Volatile var MOCK_OVERRIDE: GpsSource? = null

        /**
         * Push the current DisplayPrefs to Glass. Called when the user changes display settings
         * mid-ride. No-op if there is no live transport.
         */
        fun pushDisplayConfig() {
            val svc = instance ?: return
            val t = svc.transport ?: return
            if (!svc.connected) return
            svc.pushDisplayConfig(t)
        }

        /**
         * Start (or replace) the active route using the already-running service + transport.
         * Returns true if a service instance was present and the request was handled; false if
         * the caller should fall back to startService(). Lets the UI swap routes without paying
         * the cost of a Bluetooth reconnect between rides.
         */
        fun startRoute(route: RideViewModel.RouteState.Ready): Boolean {
            val svc = instance ?: return false
            pendingRoute = route
            val t = svc.transport
            if (t != null && svc.connected) svc.startPipeline(t, route)
            return true
        }

        /**
         * End the active route but keep the service + transport alive so the next route can be
         * pushed immediately without a Bluetooth reconnect.
         */
        fun stopRide() {
            val svc = instance ?: return
            svc.pipelineJob?.cancel()
            svc.pipelineJob = null
            val t = svc.transport
            val route = svc.currentRoute
            if (t != null && svc.connected && route != null && svc.currentRouteId != 0L) {
                try {
                    t.send(Packet.RouteEnd(svc.currentRouteId, Packet.RouteEnd.Reason.CANCELLED))
                } catch (e: Exception) {
                    Log.w(TAG, "stopRide: send RouteEnd failed", e)
                }
            }
            svc.currentRoute = null
            svc.currentRouteId = 0L
            pendingRoute = null
        }
    }
}
