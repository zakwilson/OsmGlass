package dev.glass.phone.routing

import dev.glass.protocol.TurnKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class GpxTurnExtractorTest {

    private val extractor = GpxTurnExtractor()
    private val gpx by lazy {
        javaClass.classLoader!!.getResourceAsStream("sample-route.gpx")!!
            .bufferedReader().readText()
    }

    @Test fun `extracts seven track points and four turns`() {
        val r = extractor.parse(gpx)
        assertThat(r.track).hasSize(7)
        assertThat(r.turns).hasSize(4)
    }

    @Test fun `start and end map to START and ARRIVE`() {
        val r = extractor.parse(gpx)
        assertThat(r.turns.first().kind).isEqualTo(TurnKind.START)
        assertThat(r.turns.last().kind).isEqualTo(TurnKind.ARRIVE)
    }

    @Test fun `interior turns map TL and TSLR`() {
        val r = extractor.parse(gpx)
        assertThat(r.turns[1].kind).isEqualTo(TurnKind.TL)
        assertThat(r.turns[2].kind).isEqualTo(TurnKind.TSLR)
    }

    @Test fun `start distance is zero, distances are monotonically non-decreasing`() {
        val r = extractor.parse(gpx)
        assertThat(r.turns.first().distanceFromStartM).isEqualTo(0)
        var prev = 0
        for (t in r.turns) {
            assertThat(t.distanceFromStartM).isGreaterThanOrEqualTo(prev)
            prev = t.distanceFromStartM
        }
    }

    @Test fun `track polyline length is roughly 510 metres along Berlin segment`() {
        val r = extractor.parse(gpx)
        val len = cumulativeMeters(r.track).last()
        assertThat(len).isBetween(450.0, 600.0)
    }

    @Test fun `instruction text uses the GPX desc when present`() {
        val r = extractor.parse(gpx)
        assertThat(r.turns[1].instruction).isEqualTo("Turn left")
        assertThat(r.turns[2].instruction).isEqualTo("Slight right")
    }
}
