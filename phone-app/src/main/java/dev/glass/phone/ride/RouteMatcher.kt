package dev.glass.phone.ride

import dev.glass.phone.routing.LatLng
import dev.glass.phone.routing.Turn
import dev.glass.phone.routing.cumulativeMeters
import dev.glass.phone.routing.haversineMeters
import dev.glass.protocol.TurnKind
import kotlin.math.max

/**
 * Projects the rider's current GPS fix onto a planned route polyline + turn list, returning the
 * upcoming turn index, the distance to that turn (in meters), and an off-route flag.
 *
 * Off-route: if the perpendicular distance from the fix to the polyline exceeds {@link OFF_ROUTE_M}
 * for two consecutive calls, we report off-route to the caller (which will trigger a recompute).
 */
class RouteMatcher(
    private val track: List<LatLng>,
    private val turns: List<Turn>,
    private val cumulativeM: DoubleArray = cumulativeMeters(track),
) {

    private var consecutiveOffRoute = 0

    data class Match(
        val nextTurnIndex: Int,
        val distanceToTurnM: Int,
        val distanceFromStartM: Int,
        val offRoute: Boolean,
        val perpendicularDistanceM: Double,
    )

    fun match(fix: LatLng): Match {
        require(track.size >= 2 && turns.isNotEmpty()) { "track and turns required" }
        var bestSeg = 0
        var bestFracDist = Double.POSITIVE_INFINITY
        var bestPerp = Double.POSITIVE_INFINITY
        var bestProjAlong = 0.0
        for (i in 0 until track.lastIndex) {
            val a = track[i]
            val b = track[i + 1]
            val (perpM, alongFrac) = projectOnSegment(a, b, fix)
            if (perpM < bestPerp) {
                bestPerp = perpM
                bestSeg = i
                bestFracDist = perpM
                bestProjAlong = alongFrac
            }
        }
        val segStart = cumulativeM[bestSeg]
        val segEnd = cumulativeM[bestSeg + 1]
        val distFromStart = (segStart + (segEnd - segStart) * bestProjAlong).toInt()
        // Skip the synthetic START turn — riders care about the next *maneuver*, not the route's
        // origin. Falls through to ARRIVE on a degenerate start+end-only route.
        val nextTurnIdx = turns.indexOfFirst {
            it.kind != TurnKind.START && it.distanceFromStartM >= distFromStart
        }.let { if (it == -1) turns.lastIndex else it }
        val distToTurn = max(0, turns[nextTurnIdx].distanceFromStartM - distFromStart)
        val isOff = bestPerp > OFF_ROUTE_M
        consecutiveOffRoute = if (isOff) consecutiveOffRoute + 1 else 0
        val reportOff = consecutiveOffRoute >= 2
        return Match(
            nextTurnIndex = nextTurnIdx,
            distanceToTurnM = distToTurn,
            distanceFromStartM = distFromStart,
            offRoute = reportOff,
            perpendicularDistanceM = bestPerp,
        )
    }

    /** Returns (perpendicular meters, along-segment fraction in [0,1]). */
    private fun projectOnSegment(a: LatLng, b: LatLng, p: LatLng): Pair<Double, Double> {
        val ax = a.lon
        val ay = a.lat
        val bx = b.lon
        val by = b.lat
        val px = p.lon
        val py = p.lat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return haversineMeters(a, p) to 0.0
        var t = ((px - ax) * dx + (py - ay) * dy) / len2
        t = t.coerceIn(0.0, 1.0)
        val proj = LatLng(ay + t * dy, ax + t * dx)
        return haversineMeters(proj, p) to t
    }

    companion object {
        const val OFF_ROUTE_M = 50.0
    }
}
