@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.SfntReader

/**
 * The engine half of the exit criterion `2. Avances/bornes = HarfBuzz à même location`: on the TrueType
 * route `GlyphMetrics.bounds`/`scaledBounds` must be the **instanced** ink bounds (the varied outline
 * bbox, phantom points excluded), not the static `glyf` header bbox.
 *
 * The fixture is the shared OFL variable face `NotoSansJP-VerticalFixture.ttf`
 * (`wght 100/100/900`, `avar`, `gvar`, `HVAR`); glyph `A` = gid 1. Its ink bbox is
 * `(11, 0, 563, 726)` at the default, `(0, 0, 622, 737)` at normalized `wght = 0.55999755859375`
 * (design `wght = 500`, post-`avar`) and `(-8, 0, 668, 745)` at normalized `wght = 1.0`. These numbers
 * were re-derived outside the implementation with fontTools 4.65.0 (`TTFont.getGlyphSet(normalized=
 * True)` + `BoundsPen`). The oracle is fontTools-derived: this test does not cross-check HarfBuzz
 * (`hb_font_get_glyph_extents` is not bound), so exit criterion 2 stays `Partiel` and the ink-bounds
 * cross-check is sequenced separately.
 *
 * The CFF2 fixture is the regression guard: its route already fed `outline.bounds` before this change,
 * so its bounds must not move.
 */
class VariableFontInkBoundsTest {
    private val trueTypeBytes: ByteArray = fixture("/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf")
    private val cff2Bytes: ByteArray = fixture("/fonts/cff2-variable/SyntheticVariable-CFF2.otf")

    @Test
    fun trueTypeInkBoundsVaryWithTheLocation() {
        assertEquals(DesignBounds(11, 0, 563, 726), trueTypeBounds(null))
        assertEquals(DesignBounds(0, 0, 622, 737), trueTypeBounds(0.55999755859375f))
        assertEquals(DesignBounds(-8, 0, 668, 745), trueTypeBounds(1.0f))
    }

    @Test
    fun theDefaultInstanceKeepsTheStaticHeaderBounds() {
        assertEquals(DesignBounds(11, 0, 563, 726), trueTypeBounds(null))
        assertEquals(trueTypeBounds(null), trueTypeBounds(0.0f))
    }

    @Test
    fun scaledBoundsTrackTheInstancedDesignBounds() {
        val metrics = trueTypeMetrics(1.0f)
        assertEquals(DesignBounds(-8, 0, 668, 745), metrics.bounds)
        assertEquals(-8f, metrics.scaledBounds.minX.value)
        assertEquals(0f, metrics.scaledBounds.minY.value)
        assertEquals(668f, metrics.scaledBounds.maxX.value)
        assertEquals(745f, metrics.scaledBounds.maxY.value)
    }

    @Test
    fun cff2InkBoundsAreUnchanged() {
        assertEquals(DesignBounds(0, 0, 100, 200), cff2Bounds(null))
        assertEquals(DesignBounds(0, 0, 100, 250), cff2Bounds(0.5f))
        assertEquals(DesignBounds(0, 0, 100, 300), cff2Bounds(1.0f))
    }

    private fun trueTypeBounds(normalizedWght: Float?): DesignBounds = trueTypeMetrics(normalizedWght).bounds

    private fun trueTypeMetrics(normalizedWght: Float?): GlyphMetrics = metrics(trueTypeBytes, normalizedWght)

    private fun cff2Bounds(normalizedWght: Float?): DesignBounds = metrics(cff2Bytes, normalizedWght).bounds

    private fun metrics(bytes: ByteArray, normalizedWght: Float?): GlyphMetrics {
        val axes = normalizedWght?.let { listOf(FontAxisCoordinate("wght", it)) } ?: emptyList()
        return assertIs<FontOperationResult.Success<GlyphMetrics>>(
            prepared(bytes).readGlyphMetrics(GlyphId(1), LAYOUT_SIZE, axes),
        ).value
    }

    private fun prepared(bytes: ByteArray): PreparedTrueTypeFont {
        val parsed = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("variable-ink-bounds"))),
        ).value
        return PreparedTrueTypeFont(FontSource(bytes, FontSourceProvenance("variable-ink-bounds")), parsed)
    }

    private fun fixture(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }

    private companion object {
        /** The fixture's `head.unitsPerEm`, so a scaled metric equals its design-unit value. */
        const val LAYOUT_SIZE = 1000f
    }
}
