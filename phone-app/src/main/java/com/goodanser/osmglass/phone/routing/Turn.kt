package com.goodanser.osmglass.phone.routing

import com.goodanser.osmglass.protocol.TurnKind

/**
 * One decision point along a route, suitable for shipping to Glass.
 *
 * Coordinates are WGS84 lat/lon. {@code distanceFromStartM} is cumulative track distance from the
 * route start to this turn point, in meters.
 */
data class Turn(
    val seq: Int,
    val lat: Double,
    val lon: Double,
    val kind: TurnKind,
    val distanceFromStartM: Int,
    val instruction: String,
)

/** Short glance-distance glyph for the LiveCard, e.g. "← Left". */
fun TurnKind.glyph(): String = when (this) {
    TurnKind.START -> "▲ Start"
    TurnKind.TL -> "← Turn left"
    TurnKind.TR -> "→ Turn right"
    TurnKind.TSLL -> "↖ Slight left"
    TurnKind.TSLR -> "↗ Slight right"
    TurnKind.TSHL -> "⇐ Sharp left"
    TurnKind.TSHR -> "⇒ Sharp right"
    TurnKind.KL -> "↰ Keep left"
    TurnKind.KR -> "↱ Keep right"
    TurnKind.TU -> "↺ U-turn"
    TurnKind.ARRIVE -> "● Arrive"
}
