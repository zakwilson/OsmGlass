package dev.glass.phone.render

import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleMenu
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes

/**
 * Decluttered cycling map theme shared by the Glass snippet renderer and the on-phone preview.
 *
 * Why BIKER: mapsforge 0.25 ships a built-in cycling style with a stylemenu carving POIs and other
 * overlays into named categories. The default OSMARENDER theme has no menu, so every rule renders
 * unconditionally — that's the source of the clutter (shop pins, house numbers, every food/fuel
 * marker, contour lines, transit stops…) on the Glass HUD.
 *
 * How the filter works: mapsforge's RenderThemeHandler treats `null` categories as "no filter"
 * and a non-null set as "drop every rule whose cat is not in the set." Rules without a cat
 * attribute (basic roads, water, terrain) always render. So returning a small whitelist keeps
 * orientation cues while hiding POI noise.
 */
object MapTheme {

    private val KEEP_CATEGORIES: Set<String> = setOf(
        "nature",
        "road_detail",
        "off_road",
    )

    private val callback = XmlRenderThemeMenuCallback { _: XmlRenderThemeStyleMenu ->
        KEEP_CATEGORIES
    }

    fun theme(): XmlRenderTheme = MapsforgeThemes.BIKER.apply { setMenuCallback(callback) }
}
