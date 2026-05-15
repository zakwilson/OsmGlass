package dev.glass.phone.render

import android.app.Application
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.glass.phone.routing.LatLng
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric-backed test for the Mapsforge offscreen renderer.
 *
 * Tests are skipped if `data/monaco.map` is not present at the repo root — see README for how to
 * download it. This is the verification path for Risk 1: rendering must work without an Activity
 * or attached MapView.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SnippetRendererTest {

    private val mapFile = File("../data/monaco.map")
    private lateinit var renderer: SnippetRenderer

    @Before fun setUp() {
        assumeTrue(
            "data/monaco.map not present — run `curl -sSL -o data/monaco.map https://download.mapsforge.org/maps/v5/europe/monaco.map`",
            mapFile.exists() && mapFile.length() > 0,
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        renderer = SnippetRenderer(app, mapFile)
    }

    @After fun tearDown() {
        if (::renderer.isInitialized) renderer.close()
    }

    @Test fun `renders a non-empty PNG of the expected dimensions`() {
        // Center of Monaco-Ville (Place du Palais) — well inside the .map bounds.
        val png = renderer.render(LatLng(43.7311, 7.4197))
        assertThat(png.size).isGreaterThan(100)
        val bm = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertThat(bm).isNotNull
        assertThat(bm.width).isEqualTo(SnippetRenderer.WIDTH)
        assertThat(bm.height).isEqualTo(SnippetRenderer.HEIGHT)
    }

    @Test fun `can render twice in a row without crashing`() {
        renderer.render(LatLng(43.7311, 7.4197))
        val png2 = renderer.render(LatLng(43.7325, 7.4242), arrowRotationDeg = 45f)
        assertThat(png2.size).isGreaterThan(100)
    }
}
