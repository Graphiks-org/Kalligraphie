package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingResourceProfile
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.raster.RasterFixture
import org.graphiks.kalligraphie.raster.fixtureBytes
import org.graphiks.kalligraphie.raster.openRasterFixture
import org.graphiks.kalligraphie.raster.outlineRequirements
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.TextSnapshots
import kotlin.test.assertIs

/**
 * One placed wordmark in the font's own design units.
 *
 * Every [PlacedGlyph.outline] is already translated to its pen position, so the
 * consumer only applies one uniform shift to place the whole word; the pen
 * positions are carried for reference and must never be applied twice.
 */
internal class PlacedWordmark(
    val unitsPerEm: Int,
    val glyphs: List<PlacedGlyph>,
)

/**
 * Opens the two logo fonts and shapes the wordmark through the pinned HarfBuzz backend.
 *
 * Both fonts are instantiated at [LogoLayoutSize] so shaped positions can be
 * converted back into design units with a single exact ratio.
 */
internal class KalligraphieLogoFonts private constructor(
    private val badge: RasterFixture,
    private val wordmarkFont: RasterFixture,
    private val backend: ShapingBackend,
) : AutoCloseable {

    /** Resolves the Amiri `K` outline used inside the badge. */
    fun badgeGlyph(): GlyphOutlineIR = outlineOf(badge, glyphIdOf(badge, 'K'.code))

    /** Shapes [text] and resolves every glyph outline at its pen position. */
    fun wordmark(text: String): PlacedWordmark {
        val run = assertIs<FontOperationResult.Success<ShapedGlyphRun>>(
            backend.shape(shapingRequest(text)),
        ).value
        val outlines = run.glyphs.map { glyph -> outlineOf(wordmarkFont, glyph.glyphId) }
        val unitsPerEm = outlines.first().unitsPerEm
        val designPerLayout = unitsPerEm.toDouble() / wordmarkFont.instance.key.layoutSize.value.toDouble()

        var pen = 0.0
        val placed = run.glyphs.mapIndexed { index, glyph ->
            val x = pen + glyph.xOffset.value.toDouble() * designPerLayout
            pen += glyph.xAdvance.value.toDouble() * designPerLayout
            val y = glyph.yOffset.value.toDouble() * designPerLayout
            PlacedGlyph(
                glyphId = glyph.glyphId.value,
                x = x,
                y = y,
                outline = outlines[index].translated(x, y),
            )
        }
        return PlacedWordmark(unitsPerEm, placed)
    }

    override fun close() {
        try {
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        } finally {
            try {
                wordmarkFont.close()
            } finally {
                badge.close()
            }
        }
    }

    private fun shapingRequest(text: String): ShapingRequest {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(text.toCharArray())),
        ).snapshot
        val range = snapshot.range
        return ShapingRequest(
            snapshot = snapshot,
            itemRange = range,
            contextRange = range,
            font = wordmarkFont.instance,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            bot = true,
            eot = true,
            featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
            features = emptyList(),
            graphemeClusters = snapshot.scalars.indices.map { scalar ->
                TextRange(snapshot.textIndexAtScalarBoundary(scalar), snapshot.textIndexAtScalarBoundary(scalar + 1))
            },
            resourceProfile = ShapingResourceProfile.unbounded,
            cancellationToken = CancellationToken.none,
        )
    }

    private fun glyphIdOf(fixture: RasterFixture, codePoint: Int): GlyphId =
        assertIs<FontOperationResult.Success<GlyphResolution>>(
            fixture.instance.resolveGlyph(codePoint),
        ).value.glyphId

    private fun outlineOf(fixture: RasterFixture, glyphId: GlyphId): GlyphOutlineIR =
        when (val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
            fixture.asset.resolveGlyph(FontGlyphRequest(glyphId)),
        ).value) {
            is GlyphRepresentation.Outline -> representation.outline
            is GlyphRepresentation.Empty -> error("glyph ${glyphId.value} has no ink")
            is GlyphRepresentation.Paint -> error("glyph ${glyphId.value} resolved to paint, not an outline")
            is GlyphRepresentation.Bitmap -> error("glyph ${glyphId.value} resolved to a bitmap, not an outline")
        }

    companion object {
        /** Layout size used for both fonts; shaped positions are converted back from this. */
        const val LogoLayoutSize: Float = 1_000f

        const val BadgeFontResource: String = "/fonts/amiri/Amiri-Regular.ttf"
        const val WordmarkFontResource: String = "/fonts/great-vibes/GreatVibes-Regular.ttf"

        fun open(): KalligraphieLogoFonts {
            val badge = openRasterFixture(
                bytes = fixtureBytes(BadgeFontResource),
                requirements = outlineRequirements(),
                layoutSize = LayoutUnit(LogoLayoutSize),
            )
            val wordmark = try {
                openRasterFixture(
                    bytes = fixtureBytes(WordmarkFontResource),
                    requirements = outlineRequirements(),
                    layoutSize = LayoutUnit(LogoLayoutSize),
                )
            } catch (error: Throwable) {
                badge.close()
                throw error
            }
            val backend = assertIs<FontOperationResult.Success<ShapingBackend>>(JvmHarfBuzzShapingBackend.open()).value
            return KalligraphieLogoFonts(badge, wordmark, backend)
        }
    }
}
