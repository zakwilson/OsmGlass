package dev.glass.phone.osmand

import dev.glass.phone.routing.LatLng
import dev.glass.phone.routing.Turn
import dev.glass.protocol.TurnKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class OsmAndSnippetRendererTest {

    // Roughly 1 m of longitude at 49° N (≈ 0.0000137°), used to build evenly-spaced E/W tracks.
    private val degPerMeterLon = 1.0 / 73_172.0

    private fun straightEastTrack(meters: Int, lat: Double = 49.0, startLon: Double = -123.0) =
        (0..meters).map { LatLng(lat, startLon + it * degPerMeterLon) }

    private fun turn(lat: Double, lon: Double) =
        Turn(seq = 0, lat = lat, lon = lon, kind = TurnKind.TR, distanceFromStartM = 0, instruction = "x")

    @Test fun `slice extends window meters on each side of the nearest track point`() {
        val track = straightEastTrack(1000) // 1001 points, ~1 km long
        val anchorIdx = 500
        val anchor = track[anchorIdx]
        val window = OsmAndSnippetRenderer.sliceAroundTurn(track, turn(anchor.lat, anchor.lon), 150.0)

        // Window should include the anchor and extend ~150 m (≈ 150 points) in each direction.
        assertThat(window).contains(anchor)
        assertThat(window.size).isBetween(290, 310)
        assertThat(window.first().lon).isLessThan(anchor.lon)
        assertThat(window.last().lon).isGreaterThan(anchor.lon)
    }

    @Test fun `slice clips at the start of the track`() {
        val track = straightEastTrack(1000)
        val window = OsmAndSnippetRenderer.sliceAroundTurn(track, turn(track[0].lat, track[0].lon), 150.0)

        // Anchor is at index 0, so nothing to the west.
        assertThat(window.first()).isEqualTo(track[0])
        assertThat(window.size).isBetween(140, 160)
    }

    @Test fun `slice clips at the end of the track`() {
        val track = straightEastTrack(1000)
        val last = track.last()
        val window = OsmAndSnippetRenderer.sliceAroundTurn(track, turn(last.lat, last.lon), 150.0)

        assertThat(window.last()).isEqualTo(last)
        assertThat(window.size).isBetween(140, 160)
    }

    @Test fun `slice returns empty list for empty track`() {
        val window = OsmAndSnippetRenderer.sliceAroundTurn(emptyList(), turn(0.0, 0.0), 150.0)
        assertThat(window).isEmpty()
    }

    @Test fun `slice contains real track geometry, not synthesized points`() {
        // A right-angle bend: 100 points east, then 100 north. The slice around the corner must
        // include points from BOTH legs — i.e. the snippet shows the actual route shape.
        val east = (0..100).map { LatLng(49.0, -123.0 + it * degPerMeterLon) }
        val corner = east.last()
        val north = (1..100).map { LatLng(49.0 + it * degPerMeterLon, corner.lon) }
        val track = east + north

        val window = OsmAndSnippetRenderer.sliceAroundTurn(track, turn(corner.lat, corner.lon), 50.0)

        assertThat(window).contains(corner)
        // Has points to the west of the corner (eastward leg)…
        assertThat(window.any { it.lon < corner.lon }).isTrue()
        // …and points to the north of it (northward leg).
        assertThat(window.any { it.lat > corner.lat }).isTrue()
    }
}
