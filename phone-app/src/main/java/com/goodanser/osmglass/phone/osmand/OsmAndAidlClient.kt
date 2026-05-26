package com.goodanser.osmglass.phone.osmand

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.gpx.CreateGpxBitmapParams
import net.osmand.aidlapi.logcat.OnLogcatMessageParams
import net.osmand.aidlapi.map.ALocation
import net.osmand.aidlapi.map.SetLocationParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.AGetRouteParams
import net.osmand.aidlapi.navigation.ANavigationProgress
import net.osmand.aidlapi.navigation.ANavigationProgressParams
import net.osmand.aidlapi.navigation.ANavigationUpdateParams
import net.osmand.aidlapi.navigation.ANavigationVoiceRouterMessageParams
import net.osmand.aidlapi.navigation.ARerouteEvent
import net.osmand.aidlapi.navigation.ARoute
import net.osmand.aidlapi.navigation.NavigateGpxParams
import net.osmand.aidlapi.navigation.NavigateParams
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import net.osmand.aidlapi.navigation.StopNavigationParams
import net.osmand.aidlapi.search.SearchResult
import kotlin.coroutines.resume

/**
 * Facade over the OsmAnd public AIDL service (`net.osmand.aidl.OsmandAidlServiceV2`).
 *
 * Probes the four known OsmAnd flavor packages on connect and binds to the first one installed.
 * Surfaces a small coroutine/Flow-shaped API covering only the calls the glass-nav phone app
 * needs: start/stop nav, listen for direction + voice updates, render route bitmaps.
 */
class OsmAndAidlClient(private val context: Context) {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile private var service: IOsmAndAidlInterface? = null
    @Volatile private var boundPackage: String? = null
    private var connection: ServiceConnection? = null

    /** Package name we are currently bound to (or null when disconnected). */
    val osmAndPackage: String? get() = boundPackage

    /**
     * Binds to OsmAnd. Suspends until the service is connected or all known packages fail to resolve.
     * Returns true on success.
     */
    suspend fun connect(): Boolean = suspendCancellableCoroutine { cont ->
        if (_state.value == ConnectionState.CONNECTED) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }
        val target = resolveOsmAndPackage(context) ?: run {
            _state.value = ConnectionState.FAILED
            Log.w(TAG, "No OsmAnd package found on device")
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IOsmAndAidlInterface.Stub.asInterface(binder)
                boundPackage = target
                _state.value = ConnectionState.CONNECTED
                Log.i(TAG, "Bound to OsmAnd at $target")
                if (cont.isActive) cont.resume(true)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                boundPackage = null
                _state.value = ConnectionState.DISCONNECTED
                Log.w(TAG, "OsmAnd service disconnected")
            }
        }
        connection = conn
        val intent = Intent(ACTION).apply { `package` = target }
        _state.value = ConnectionState.CONNECTING
        val ok = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.e(TAG, "bindService threw SecurityException — missing <queries> in manifest?", e)
            false
        }
        if (!ok) {
            _state.value = ConnectionState.FAILED
            connection = null
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { disconnect() }
    }

    fun disconnect() {
        connection?.let {
            try { context.unbindService(it) } catch (_: IllegalArgumentException) { /* not bound */ }
        }
        connection = null
        service = null
        boundPackage = null
        _state.value = ConnectionState.DISCONNECTED
    }

    /**
     * Start turn-by-turn navigation. `profile` is an OsmAnd application mode key, e.g.
     * "bicycle", "pedestrian", "car". Returns true if OsmAnd accepted the call.
     */
    fun navigate(
        startLat: Double, startLon: Double, startName: String,
        destLat: Double, destLon: Double, destName: String,
        profile: String = "bicycle",
        force: Boolean = true,
        needLocationPermission: Boolean = true,
    ): Boolean {
        val svc = service ?: return false
        val params = NavigateParams(
            startName, startLat, startLon,
            destName, destLat, destLon,
            profile, force, needLocationPermission,
        )
        return runCatching { svc.navigate(params) }.getOrElse {
            Log.e(TAG, "navigate failed", it); false
        }
    }

    /**
     * Start navigation using a raw GPX string as the canonical route. snapToRoad is intentionally
     * left false: that way OsmAnd's off-route check is measured against the very polyline the
     * spike harness also drives via setLocation, eliminating spurious off-route warnings. The
     * trade-off is that turn instructions are derived from GPX geometry rather than a routed plan.
     */
    fun navigateGpx(
        gpxData: String,
        force: Boolean = true,
        needLocationPermission: Boolean = true,
    ): Boolean {
        val svc = service ?: return false
        val params = NavigateGpxParams(gpxData, force, needLocationPermission).apply {
            setPassWholeRoute(true)
            setSnapToRoad(false)
        }
        return runCatching { svc.navigateGpx(params) }.getOrElse {
            Log.e(TAG, "navigateGpx failed", it); false
        }
    }

    /**
     * Push a synthetic GPS fix into OsmAnd. While `timeToNotUseOtherGpsMs` has not elapsed since
     * the last call, OsmAnd ignores its real location providers and uses these fixes instead.
     * Used by the spike harness to drive simulation deterministically.
     */
    fun setLocation(
        lat: Double, lon: Double,
        bearing: Float? = null, speedMps: Float? = null,
        accuracyM: Float = 5f,
        timeToNotUseOtherGpsMs: Long = 5_000L,
    ): Boolean {
        val svc = service ?: return false
        val loc = ALocation(
            lat, lon, System.currentTimeMillis(),
            false, 0.0,
            speedMps != null, speedMps ?: 0f,
            bearing != null, bearing ?: 0f,
            true, accuracyM,
            false, 0f,
        )
        return runCatching { svc.setLocation(SetLocationParams(loc, timeToNotUseOtherGpsMs)) }
            .getOrElse { Log.e(TAG, "setLocation failed", it); false }
    }

    fun stopNavigation(): Boolean {
        val svc = service ?: return false
        return runCatching { svc.stopNavigation(StopNavigationParams()) }.getOrElse {
            Log.e(TAG, "stopNavigation failed", it); false
        }
    }

    /**
     * Cold flow of next-turn information from OsmAnd's voice router cadence (usually subsecond).
     * Collecting registers a callback; cancelling the collector unregisters it.
     */
    fun navigationUpdates(): Flow<ADirectionInfo> = callbackFlow {
        val svc = service
        if (svc == null) { close(IllegalStateException("not connected")); return@callbackFlow }
        val cb = object : NoopAidlCallback() {
            override fun updateNavigationInfo(info: ADirectionInfo) {
                trySend(info)
            }
        }
        val params = ANavigationUpdateParams().apply { setSubscribeToUpdates(true) }
        val id = svc.registerForNavigationUpdates(params, cb)
        if (id < 0) {
            close(IllegalStateException("registerForNavigationUpdates returned $id"))
            return@callbackFlow
        }
        awaitClose {
            val unsub = ANavigationUpdateParams().apply {
                setSubscribeToUpdates(false); setCallbackId(id)
            }
            runCatching { service?.registerForNavigationUpdates(unsub, cb) }
        }
    }

    /**
     * Periodic high-cadence navigation progress (≈1 Hz, debounced to [intervalMs]). Only the
     * glass-nav fork of OsmAnd implements this — gate calls behind [hasOptionCExtensions].
     */
    fun navigationProgress(intervalMs: Long = 1_000L): Flow<ANavigationProgress> = callbackFlow {
        val svc = service
        if (svc == null) { close(IllegalStateException("not connected")); return@callbackFlow }
        val cb = object : NoopAidlCallback() {
            override fun onNavigationProgress(progress: ANavigationProgress) { trySend(progress) }
        }
        val params = ANavigationProgressParams().apply {
            setSubscribeToUpdates(true); setIntervalMs(intervalMs)
        }
        val id = svc.registerForNavigationProgress(params, cb)
        if (id < 0) {
            close(IllegalStateException("registerForNavigationProgress returned $id"))
            return@callbackFlow
        }
        awaitClose {
            val unsub = ANavigationProgressParams().apply {
                setSubscribeToUpdates(false); setCallbackId(id)
            }
            runCatching { service?.registerForNavigationProgress(unsub, cb) }
        }
    }

    /**
     * Reroute events from OsmAnd's RoutingHelper. Fires on the initial route calc too, so this
     * doubles as a "route ready" trigger. Only on the glass-nav fork — gate behind
     * [hasOptionCExtensions]. On receipt, callers should re-pull [getActiveRoute].
     */
    fun rerouteEvents(): Flow<ARerouteEvent> = callbackFlow {
        val svc = service
        if (svc == null) { close(IllegalStateException("not connected")); return@callbackFlow }
        val cb = object : NoopAidlCallback() {
            override fun onReroute(event: ARerouteEvent) { trySend(event) }
        }
        val params = ANavigationUpdateParams().apply { setSubscribeToUpdates(true) }
        val id = svc.registerForRerouteEvents(params, cb)
        if (id < 0) {
            close(IllegalStateException("registerForRerouteEvents returned $id"))
            return@callbackFlow
        }
        awaitClose {
            val unsub = ANavigationUpdateParams().apply {
                setSubscribeToUpdates(false); setCallbackId(id)
            }
            runCatching { service?.registerForRerouteEvents(unsub, cb) }
        }
    }

    /**
     * Snapshot of OsmAnd's active route (polyline + structured turns) with its monotonic
     * fingerprint. Returns null when there's no active route or when bound to a stock OsmAnd that
     * doesn't implement the call. Pair with [rerouteEvents] to refresh on recompute.
     */
    fun getActiveRoute(): Pair<ARoute, Long>? {
        val svc = service ?: return null
        val params = AGetRouteParams()
        val ok = runCatching { svc.getActiveRoute(params) }.getOrElse {
            Log.e(TAG, "getActiveRoute failed", it); false
        }
        if (!ok) return null
        val route = params.route ?: return null
        return route to params.fingerprint
    }

    /**
     * Probe whether the bound OsmAnd implements the glass-nav-fork Option C extensions
     * (getActiveRoute, registerForNavigationProgress, registerForRerouteEvents). On stock
     * OsmAnd the AIDL transaction code is unknown and the call throws RemoteException; the
     * fork's getActiveRoute is a no-op safe to call without an active route. Re-probe per
     * connection — the user can uninstall the fork at any time.
     */
    fun hasOptionCExtensions(): Boolean {
        val svc = service ?: return false
        return try {
            svc.getActiveRoute(AGetRouteParams())
            true
        } catch (_: NoSuchMethodError) {
            false
        } catch (e: android.os.RemoteException) {
            Log.i(TAG, "hasOptionCExtensions: bound OsmAnd does not implement extensions ($e)")
            false
        }
    }

    /** Cold flow of TTS-equivalent route messages (raw OsmAnd voice command strings). */
    fun voiceRouterMessages(): Flow<OnVoiceNavigationParams> = callbackFlow {
        val svc = service
        if (svc == null) { close(IllegalStateException("not connected")); return@callbackFlow }
        val cb = object : NoopAidlCallback() {
            override fun onVoiceRouterNotify(params: OnVoiceNavigationParams) { trySend(params) }
        }
        val params = ANavigationVoiceRouterMessageParams().apply { setSubscribeToUpdates(true) }
        val id = svc.registerForVoiceRouterMessages(params, cb)
        if (id < 0) {
            close(IllegalStateException("registerForVoiceRouterMessages returned $id"))
            return@callbackFlow
        }
        awaitClose {
            val unsub = ANavigationVoiceRouterMessageParams().apply {
                setSubscribeToUpdates(false); setCallbackId(id)
            }
            runCatching { service?.registerForVoiceRouterMessages(unsub, cb) }
        }
    }

    /**
     * Render a map bitmap with the supplied GPX overlay. Returns null if OsmAnd couldn't render
     * (most often: no map for the area is downloaded).
     */
    suspend fun getBitmapForGpx(params: CreateGpxBitmapParams): Bitmap? {
        val svc = service ?: return null
        val deferred = CompletableDeferred<Bitmap?>()
        val cb = object : NoopAidlCallback() {
            override fun onGpxBitmapCreated(bitmap: AGpxBitmap) {
                deferred.complete(bitmap.bitmap)
            }
        }
        val ok = runCatching { svc.getBitmapForGpx(params, cb) }.getOrElse {
            Log.e(TAG, "getBitmapForGpx failed", it); false
        }
        if (!ok) return null
        return deferred.await()
    }

    companion object {
        private const val TAG = "OsmAndAidlClient"
        const val ACTION = "net.osmand.aidl.OsmandAidlServiceV2"

        /**
         * Package names OsmAnd is shipped under, in preference order. Glass-nav fork first so
         * we bind to the Option C extension methods when both fork and stock are installed.
         */
        val KNOWN_PACKAGES = listOf(
            "net.osmand.glassnav",
            "net.osmand.plus",
            "net.osmand",
            "net.osmand.dev",
            "net.osmand.huawei",
        )

        fun resolveOsmAndPackage(context: Context): String? {
            val pm = context.packageManager
            for (pkg in KNOWN_PACKAGES) {
                runCatching { pm.getPackageInfo(pkg, 0) }.onSuccess { return pkg }
            }
            return null
        }
    }
}

/**
 * AIDL callback stub with no-op defaults for every method. We override only the ones we need.
 * Generated AIDL doesn't give us a default implementation, so we have to spell them all out.
 */
private open class NoopAidlCallback : IOsmAndAidlCallback.Stub() {
    override fun onSearchComplete(resultSet: MutableList<SearchResult>?) {}
    override fun onUpdate() {}
    override fun onAppInitialized() {}
    override fun onGpxBitmapCreated(bitmap: AGpxBitmap) {}
    override fun updateNavigationInfo(directionInfo: ADirectionInfo) {}
    override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {}
    override fun onVoiceRouterNotify(params: OnVoiceNavigationParams) {}
    override fun onKeyEvent(keyEvent: android.view.KeyEvent?) {}
    override fun onLogcatMessage(params: OnLogcatMessageParams?) {}
    override fun onNavigationProgress(progress: ANavigationProgress) {}
    override fun onReroute(event: ARerouteEvent) {}
}
