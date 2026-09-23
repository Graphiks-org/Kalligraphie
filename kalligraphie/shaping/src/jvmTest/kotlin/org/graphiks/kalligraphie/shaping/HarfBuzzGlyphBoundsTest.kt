@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontGeometryParameters
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.LayoutUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Cross-checks the **bounds** half against HarfBuzz: our
 * `GlyphMetrics.bounds` equals HarfBuzz's `hb_font_get_glyph_extents` at the same variation
 * location.
 *
 * `hb_glyph_extents_t` is `{x_bearing, y_bearing, width, height}` with the font's y axis pointing
 * up, while `DesignBounds` is `(xMin, yMin, xMax, yMax)` with y pointing down and the top row
 * named `yMin`. The reconstruction is therefore `xMin = x_bearing`, `yMin = y_bearing + height`,
 * `xMax = x_bearing + width`, `yMax = y_bearing`; both the engine and HarfBuzz exclude `gvar`
 * phantom points from ink extents.
 */
class HarfBuzzGlyphBoundsTest {
    /**
     * The fixture's `A` (gid 1) has a varied outline. Audited outside the implementation with
     * fontTools 4.65.0 (`getGlyphSet` + `BoundsPen`) and HarfBuzz 14.3.0 (`hb_font_get_glyph_extents`
     * at `scale = upem = 1000`), which agree:
     *   default (wght 100)  extents (11, 726, 552, -726)  -> bbox (11, 0, 563, 726)
     *   norm 0.55999755859375 extents (0, 737, 622, -737) -> bbox (0, 0, 622, 737)
     *   norm 1.0            extents (-8, 745, 676, -745) -> bbox (-8, 0, 668, 745)
     * The static `glyf` header bbox is (11, 0, 563, 726) at every location, so the non-default rows
     * prove the engine's value is genuinely varied and matches HarfBuzz, not the static header.
     */
    @Test
    fun harfBuzzExtentsAtTheInstanceEqualOurInkBoundsOnTheTrueTypeRoute() {
        val fixture = "/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"
        assertEquals(DesignBounds(11, 0, 563, 726), crossCheck(fixture, null))
        assertEquals(DesignBounds(0, 0, 622, 737), crossCheck(fixture, 0.55999755859375f))
        assertEquals(DesignBounds(-8, 0, 668, 745), crossCheck(fixture, 1.0f))
    }

    /**
     * The CFF2 route already produced varied ink bounds before this cross-check; the test pins it as
     * a regression guard so a future blend regression is caught by the same cross-check. Audited with
     * fontTools 4.65.0 and HarfBuzz 14.3.0 at `scale = upem = 1000`:
     *   default (wght 0)    extents (0, 200, 100, -200) -> bbox (0, 0, 100, 200)
     *   norm 0.5 (wght 500) extents (0, 250, 100, -250) -> bbox (0, 0, 100, 250)
     *   norm 1.0 (wght 1000) extents (0, 300, 100, -300) -> bbox (0, 0, 100, 300)
     */
    @Test
    fun harfBuzzExtentsAtTheInstanceEqualOurInkBoundsOnTheCff2Route() {
        val fixture = "/fonts/cff2-variable/SyntheticVariable-CFF2.otf"
        assertEquals(DesignBounds(0, 0, 100, 200), crossCheck(fixture, null))
        assertEquals(DesignBounds(0, 0, 100, 250), crossCheck(fixture, 0.5f))
        assertEquals(DesignBounds(0, 0, 100, 300), crossCheck(fixture, 1.0f))
    }

    private fun crossCheck(fixture: String, normalized: Float?): DesignBounds {
        val bytes = bytesFor(fixture)
        val instance = instanceAt(fixture, bytes, normalized)
        val engineBounds = assertIs<FontOperationResult.Success<GlyphMetrics>>(
            instance.metrics(GlyphId(1)),
        ).value.bounds

        val binding = openHarfBuzzPlatformBinding()
        val prepared = binding.prepare(bytes, 0, normalized?.let { floatArrayOf(it) } ?: FloatArray(0))
        try {
            val extents = prepared.extents(1)
            assertEquals(
                DesignBounds(
                    minX = extents.xBearing,
                    minY = extents.yBearing + extents.height,
                    maxX = extents.xBearing + extents.width,
                    maxY = extents.yBearing,
                ),
                engineBounds,
            )
        } finally {
            binding.release(prepared)
        }
        return engineBounds
    }

    private fun instanceAt(fixture: String, bytes: ByteArray, normalized: Float?): FontInstance {
        val source = FontSource(bytes, FontSourceProvenance(fixture))
        val catalog = Kalligraphie.embedded(listOf(source)).successValue()
        val face = catalog.resolveFace(
            catalog.faces.single().id,
            FontAccessRequirementsSnapshot.layoutOnly(),
        ).successValue()
        val geometry = normalized?.let {
            FontGeometryParameters(normalizedAxes = listOf(FontAxisCoordinate("wght", it)))
        } ?: FontGeometryParameters()
        return face.instantiate(
            FontInstanceDescriptor(layoutSize = LayoutUnit(1000f), geometry = geometry),
        ).successValue()
    }

    private fun bytesFor(fixture: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(fixture)).use { it.readBytes() }

    private fun <T> FontOperationResult<T>.successValue(): T =
        assertIs<FontOperationResult.Success<T>>(this).value
}
