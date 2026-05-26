package dev.glass.phone.routing

import android.content.Context
import android.util.Log
import dev.glass.phone.osmand.OsmAndAidlClient
import dev.glass.phone.osmand.TurnTypeMapping
import dev.glass.protocol.TurnKind
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import net.osmand.aidlapi.navigation.ARoute

/** Result of an OsmAnd-driven route computation: full polyline plus structured turn list. */
data class OsmAndRouteResult(val track: List<LatLng>, val turns: List<Turn>)

/**
 * Bind to OsmAnd, ask it to compute a route, fetch the structured polyline + turn list, then
 * unbind.
 *
 * Requires the glass-nav OsmAnd fork (stock OsmAnd has no [OsmAndAidlClient.getActiveRoute]
 * or [OsmAndAidlClient.rerouteEvents] support).
 *
 * @throws RoutingException for any failure: OsmAnd not installed, missing fork extensions,
 *   navigate() refused, no route ready within the timeout, or a degenerate result.
 */
@Throws(RoutingException::class)
suspend fun computeOsmAndRoute(
    context: Context,
    start: LatLng,
    end: LatLng,
    mode: NavigationMode,
    startName: String = "Start",
    destName: String = "Destination",
    routeTimeoutMs: Long = 60_000L,
): OsmAndRouteResult {
    val client = OsmAndAidlClient(context.applicationContext)
    try {
        if (!client.connect()) {
            throw RoutingException(
                "OsmAnd not installed. Install the glass-nav OsmAnd fork from F-Droid or the project releases."
            )
        }
        return computeOsmAndRoute(client, start, end, mode, startName, destName, routeTimeoutMs)
    } finally {
        client.disconnect()
    }
}

/**
 * As [computeOsmAndRoute] but using an already-connected client. The caller owns the
 * connection lifecycle.
 */
@Throws(RoutingException::class)
suspend fun computeOsmAndRoute(
    client: OsmAndAidlClient,
    start: LatLng,
    end: LatLng,
    mode: NavigationMode,
    startName: String = "Start",
    destName: String = "Destination",
    routeTimeoutMs: Long = 60_000L,
): OsmAndRouteResult {
    if (!client.hasOptionCExtensions()) {
        throw RoutingException(
            "Bound OsmAnd does not implement glass-nav fork extensions. Install the patched OsmAnd build."
        )
    }
    // Subscribe before issuing navigate() so we don't miss the recompute event for fast routes.
    val events = client.rerouteEvents()
    val ok = client.navigate(
        startLat = start.lat, startLon = start.lon, startName = startName,
        destLat = end.lat, destLon = end.lon, destName = destName,
        profile = mode.osmandProfile,
        force = true,
        needLocationPermission = true,
    )
    if (!ok) throw RoutingException("OsmAnd refused the navigate() request")

    val event = withTimeoutOrNull(routeTimeoutMs) { events.firstOrNull() }
        ?: throw RoutingException("OsmAnd did not return a route within ${routeTimeoutMs / 1000}s")
    Log.i(TAG, "OsmAnd route ready: fingerprint ${event.oldFingerprint} -> ${event.newFingerprint}")

    val snapshot = client.getActiveRoute()
        ?: throw RoutingException("OsmAnd signalled route ready but getActiveRoute() returned null")
    return snapshot.first.toResult()
}

private fun ARoute.toResult(): OsmAndRouteResult {
    val track = polyline.map { LatLng(it.latitude, it.longitude) }
    if (track.size < 2) {
        throw RoutingException("OsmAnd route polyline has ${track.size} point(s); expected ≥ 2")
    }
    val src = turns
    if (src.isEmpty()) {
        throw RoutingException("OsmAnd route has no direction info")
    }
    val mapped = src.mapIndexed { idx, t ->
        // OsmAnd doesn't emit explicit START / ARRIVE kinds — the first direction info carries
        // the initial heading, the last is the destination point. Synthesise the boundary
        // kinds here; map interior "continue" entries to START so the Glass UI dims them.
        val kind = when {
            idx == 0 -> TurnKind.START
            idx == src.size - 1 -> TurnKind.ARRIVE
            else -> TurnTypeMapping.fromOsmAndTurnType(t.turnType) ?: TurnKind.START
        }
        Turn(
            seq = idx,
            lat = t.lat,
            lon = t.lon,
            kind = kind,
            distanceFromStartM = t.distanceFromStartM,
            instruction = t.instructionText.ifBlank { kind.glyph() },
        )
    }
    return OsmAndRouteResult(track = track, turns = mapped)
}

private val NavigationMode.osmandProfile: String
    get() = when (this) {
        NavigationMode.CYCLING -> "bicycle"
        NavigationMode.WALKING -> "pedestrian"
        NavigationMode.DRIVING -> "car"
    }

private const val TAG = "OsmAndRouter"
