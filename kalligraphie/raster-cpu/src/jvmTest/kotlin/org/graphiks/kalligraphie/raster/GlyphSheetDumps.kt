package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import kotlin.test.assertIs

/**
 * Renders per-font alphabet sheets into demonstration dumps.
 *
 * Every listed code point must resolve to a real glyph with ink: an unassigned
 * or unmapped code point fails the sheet instead of leaving a silent hole. Each
 * glyph is drawn left-aligned on a shared row baseline inside a uniform cell.
 */
internal object GlyphSheetDumps {
    private const val COLUMNS = 16
    private const val PADDING = 2

    /** Renders an outline sheet as a flipped P5 PGM (white ink on black). */
    fun outlineSheet(
        name: String,
        fontPath: String,
        codepoints: List<Int>,
        pixelsPerEm: Double,
    ): Dump = openRasterFixture(fixtureBytes(fontPath), outlineRequirements()).use { fixture ->
        val images = codepoints.map { codepoint -> resolveOutline(fixture, codepoint, pixelsPerEm) }
        Dump(
            bytes = renderCoverageSheet(images),
            note = "$COLUMNS columns, ${pixelsPerEm.toInt()} pixels per em, flipped vertically",
        )
    }

    /** Renders a color sheet as a flipped P6 PPM (glyph colors over white). */
    fun paintSheet(
        name: String,
        fontPath: String,
        codepoints: List<Int>,
        pixelsPerEm: Double,
        paletteIndex: Int,
    ): Dump = openRasterFixture(
        fixtureBytes(fontPath),
        FontAccessRequirementsSnapshot.renderable(listOf(paintProfile())),
        renderVariant = FontRenderVariantSnapshot(cpalPaletteIndex = paletteIndex),
    ).use { fixture ->
        val images = codepoints.map { codepoint -> resolvePaint(fixture, codepoint, pixelsPerEm) }
        Dump(
            bytes = renderColorSheet(images),
            note = "$COLUMNS columns, ${pixelsPerEm.toInt()} pixels per em, palette $paletteIndex, flipped vertically",
        )
    }

    /** Renders one normalized bitmap strike as a P6 PPM (black ink over white). */
    fun bitmapDump(
        name: String,
        fontPath: String,
        codepoint: Int,
    ): Dump = openRasterFixture(
        fixtureBytes(fontPath),
        FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile())),
    ).use { fixture ->
        val glyph = resolveGlyph(fixture, codepoint)
        val bitmap = assertIs<GlyphRepresentation.Bitmap>(
            assertIs<FontOperationResult.Success<GlyphRepresentation>>(
                fixture.asset.resolveGlyph(FontGlyphRequest(glyph)),
                "${label(codepoint)} is not a bitmap representation",
            ).value,
            "${label(codepoint)} is not a bitmap representation",
        ).bitmap
        val image = assertIs<RasterResult.Success<Rgba8Image>>(
            GlyphRasterizer.rasterizeBitmap(
                bitmap,
                BitmapRasterRequest(org.graphiks.kalligraphie.api.GlyphColor(0, 0, 0, 255)),
            ),
        ).value
        val canvas = RgbaCanvas(image.width + 2 * PADDING, image.height + 2 * PADDING)
        canvas.drawBitmap(image, PADDING, PADDING)
        Dump(
            bytes = canvas.toPpm(),
            note = "strike ${bitmap.strike.pixelsPerEmX}x${bitmap.strike.pixelsPerEmY}, black ink over white",
        )
    }

    private fun resolveOutline(fixture: RasterFixture, codepoint: Int, pixelsPerEm: Double): A8Image {
        val glyph = resolveGlyph(fixture, codepoint)
        val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
            fixture.asset.resolveGlyph(FontGlyphRequest(glyph)),
            "${label(codepoint)} is not an outline representation",
        ).value
        val outline = assertIs<GlyphRepresentation.Outline>(
            representation,
            "${label(codepoint)} is not an outline representation",
        ).outline
        val image = assertIs<RasterResult.Success<A8Image>>(
            GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm)),
        ).value
        check(image.width > 0 && image.height > 0) { "${label(codepoint)} produced no ink" }
        return image
    }

    private fun resolvePaint(fixture: RasterFixture, codepoint: Int, pixelsPerEm: Double): Rgba8Image {
        val glyph = resolveGlyph(fixture, codepoint)
        val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
            fixture.asset.resolveGlyph(FontGlyphRequest(glyph)),
            "${label(codepoint)} is not a paint representation",
        ).value
        val paint = assertIs<GlyphRepresentation.Paint>(
            representation,
            "${label(codepoint)} is not a paint representation",
        ).paint
        val unitsPerEm = assertIs<GlyphPaintNode.SolidOutline>(
            paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().firstOrNull(),
            "${label(codepoint)} has no solid outline node",
        ).outline.unitsPerEm
        val image = assertIs<RasterResult.Success<Rgba8Image>>(
            GlyphRasterizer.rasterizePaint(paint, PaintRasterRequest(pixelsPerEm, unitsPerEm)),
        ).value
        check(image.width > 0 && image.height > 0) { "${label(codepoint)} produced no ink" }
        return image
    }

    private fun resolveGlyph(fixture: RasterFixture, codepoint: Int): Int {
        val resolution = assertIs<FontOperationResult.Success<GlyphResolution>>(
            fixture.instance.resolveGlyph(codepoint),
            "${label(codepoint)} could not be resolved",
        ).value.glyphId.value
        check(resolution != 0) { "${label(codepoint)} resolves to .notdef (glyph 0)" }
        return resolution
    }

    private fun label(codepoint: Int): String = "U+" + codepoint.toString(16).uppercase().padStart(4, '0')

    private fun renderCoverageSheet(images: List<A8Image>): ByteArray {
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
        return canvas.toPgm()
    }

    private fun renderColorSheet(images: List<Rgba8Image>): ByteArray {
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
        return canvas.toPpm()
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
