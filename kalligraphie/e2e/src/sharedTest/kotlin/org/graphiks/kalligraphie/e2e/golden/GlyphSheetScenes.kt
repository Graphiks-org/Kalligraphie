package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.e2e.fixture.FixtureCorpus
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.raster.A8Image
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.OutlineRasterRequest
import org.graphiks.kalligraphie.raster.PaintRasterRequest
import org.graphiks.kalligraphie.raster.RasterDiagnostic
import org.graphiks.kalligraphie.raster.RasterResult
import org.graphiks.kalligraphie.raster.Rgba8Image
import kotlin.test.assertIs

/**
 * Renders per-font alphabet sheets as canonical golden images.
 *
 * The sheet functions require every listed code point to resolve to a real
 * glyph with ink: an unassigned or unmapped code point fails the sheet instead
 * of leaving a silent hole. Each glyph is drawn left-aligned on a shared row
 * baseline inside a uniform cell. The composed canvas is the canonical image:
 * canonicalization never flips again.
 */
internal object GlyphSheetScenes {
    private const val COLUMNS = 16
    private const val PADDING = 2

    /** Renders an outline sheet as a coverage canvas. */
    fun outlineSheet(
        corpus: FixtureCorpus,
        fontPath: String,
        codepoints: List<Int>,
        pixelsPerEm: Double,
    ): GoldenImage {
        require(codepoints.isNotEmpty()) { "a sheet needs at least one code point." }
        return openOutlineFixture(corpus.bytes(fontPath)).use { fixture ->
            val images = codepoints.map { codepoint -> resolveOutline(fixture, codepoint, pixelsPerEm) }
            renderCoverageSheet(images)
        }
    }

    /** Renders a color sheet as an RGBA canvas (glyph colors already composited over white). */
    fun paintSheet(
        corpus: FixtureCorpus,
        fontPath: String,
        codepoints: List<Int>,
        pixelsPerEm: Double,
        paletteIndex: Int,
    ): GoldenImage {
        require(codepoints.isNotEmpty()) { "a sheet needs at least one code point." }
        return openRenderableFixture(
            bytes = corpus.bytes(fontPath),
            requirements = paintRequirements(),
            renderVariant = FontRenderVariantSnapshot(cpalPaletteIndex = paletteIndex),
        ).use { fixture ->
            val images = codepoints.map { codepoint -> resolvePaint(fixture, codepoint, pixelsPerEm) }
            renderColorSheet(images)
        }
    }

    private fun resolveOutline(fixture: E2eFontFixture, codepoint: Int, pixelsPerEm: Double): A8Image {
        val glyph = resolveGlyph(fixture, codepoint)
        val representation = requireSuccess(
            codepoint,
            "materialization",
            fixture.asset.resolveGlyph(FontGlyphRequest(glyph)),
        )
        val outline = assertIs<GlyphRepresentation.Outline>(
            representation,
            "${label(codepoint)} is not an outline representation",
        ).outline
        val image = requireRasterized(
            codepoint,
            GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm)),
        )
        check(image.width > 0 && image.height > 0) { "${label(codepoint)} produced no ink" }
        return image
    }

    private fun resolvePaint(fixture: E2eFontFixture, codepoint: Int, pixelsPerEm: Double): Rgba8Image {
        val glyph = resolveGlyph(fixture, codepoint)
        val representation = requireSuccess(
            codepoint,
            "materialization",
            fixture.asset.resolveGlyph(FontGlyphRequest(glyph)),
        )
        val paint = assertIs<GlyphRepresentation.Paint>(
            representation,
            "${label(codepoint)} is not a paint representation",
        ).paint
        val unitsPerEm = assertIs<GlyphPaintNode.SolidOutline>(
            paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().firstOrNull(),
            "${label(codepoint)} has no solid outline node",
        ).outline.unitsPerEm
        val image = requireRasterized(
            codepoint,
            GlyphRasterizer.rasterizePaint(paint, PaintRasterRequest(pixelsPerEm, unitsPerEm)),
        )
        check(image.width > 0 && image.height > 0) { "${label(codepoint)} produced no ink" }
        return image
    }

    private fun resolveGlyph(fixture: E2eFontFixture, codepoint: Int): Int {
        val resolution = requireSuccess(
            codepoint,
            "resolution",
            fixture.instance.resolveGlyph(codepoint),
        ).glyphId.value
        check(resolution != 0) { "${label(codepoint)} resolves to .notdef (glyph 0)" }
        return resolution
    }

    private fun label(codepoint: Int): String = "U+" + codepoint.toString(16).uppercase().padStart(4, '0')

    private fun <T> requireSuccess(codepoint: Int, what: String, result: FontOperationResult<T>): T =
        when (result) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure ->
                error("${label(codepoint)} $what failed: ${result.error.code}")

            is FontOperationResult.Cancelled -> error("${label(codepoint)} $what was cancelled")
        }

    private fun <T> requireRasterized(codepoint: Int, result: RasterResult<T>): T =
        when (result) {
            is RasterResult.Success -> result.value
            is RasterResult.Failure -> error(
                "${label(codepoint)} rasterization failed: " + result.diagnostics.joinToString { diagnostic ->
                    when (diagnostic) {
                        is RasterDiagnostic.LimitExceeded ->
                            "${diagnostic.field}(observed=${diagnostic.observed}, limit=${diagnostic.limit})"

                        is RasterDiagnostic.InvalidRequest -> "${diagnostic.field}: ${diagnostic.detail}"
                    }
                },
            )
        }

    private fun renderCoverageSheet(images: List<A8Image>): GoldenImage {
        val metrics = sheetMetrics(
            lefts = images.map { image -> image.left },
            widths = images.map { image -> image.width },
            tops = images.map { image -> image.top },
            heights = images.map { image -> image.height },
        )
        val rows = (images.size + COLUMNS - 1) / COLUMNS
        val canvas = A8Canvas(COLUMNS * metrics.cellWidth, rows * metrics.cellHeight)
        images.forEachIndexed { index, image ->
            val cellLeft = (index % COLUMNS) * metrics.cellWidth
            val cellTop = (index / COLUMNS) * metrics.cellHeight
            canvas.drawCoverage(
                image = image,
                penX = cellLeft + PADDING + metrics.leftOffset,
                baselineY = cellTop + PADDING + metrics.maxAscent,
            )
        }
        return canvas.toGoldenImage()
    }

    private fun renderColorSheet(images: List<Rgba8Image>): GoldenImage {
        val metrics = sheetMetrics(
            lefts = images.map { image -> image.left },
            widths = images.map { image -> image.width },
            tops = images.map { image -> image.top },
            heights = images.map { image -> image.height },
        )
        val rows = (images.size + COLUMNS - 1) / COLUMNS
        val canvas = RgbaCanvas(COLUMNS * metrics.cellWidth, rows * metrics.cellHeight)
        images.forEachIndexed { index, image ->
            val cellLeft = (index % COLUMNS) * metrics.cellWidth
            val cellTop = (index / COLUMNS) * metrics.cellHeight
            canvas.drawColor(
                image = image,
                penX = cellLeft + PADDING + metrics.leftOffset,
                baselineY = cellTop + PADDING + metrics.maxAscent,
            )
        }
        return canvas.toGoldenImage()
    }

    private class SheetMetrics(
        val leftOffset: Int,
        val cellWidth: Int,
        val cellHeight: Int,
        val maxAscent: Int,
    )

    private fun sheetMetrics(lefts: List<Int>, widths: List<Int>, tops: List<Int>, heights: List<Int>): SheetMetrics {
        val leftOffset = -lefts.min()
        val cellWidth = lefts.indices.maxOf { index -> lefts[index] + leftOffset + widths[index] } + 2 * PADDING
        val maxAscent = tops.indices.maxOf { index -> tops[index] + heights[index] }
        val maxDescent = tops.indices.maxOf { index -> -tops[index] }
        return SheetMetrics(
            leftOffset = leftOffset,
            cellWidth = cellWidth,
            cellHeight = maxAscent + maxDescent + 2 * PADDING,
            maxAscent = maxAscent,
        )
    }
}
