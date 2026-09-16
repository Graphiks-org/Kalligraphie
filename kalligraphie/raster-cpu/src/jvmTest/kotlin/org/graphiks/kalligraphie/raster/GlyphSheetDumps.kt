package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphRepresentation
import kotlin.test.assertIs

/**
 * Renders per-font alphabet sheets into demonstration dumps.
 *
 * The sheet functions require every listed code point to resolve to a real
 * glyph with ink: an unassigned or unmapped code point fails the sheet instead
 * of leaving a silent hole. Each glyph is drawn left-aligned on a shared row
 * baseline inside a uniform cell. [bitmapDump] renders one normalized bitmap
 * strike instead.
 */
internal object GlyphSheetDumps {
    private const val COLUMNS = 16
    private const val PADDING = 2

    /** Renders an outline sheet as a flipped P5 PGM (white ink on black). */
    fun outlineSheet(
        fontPath: String,
        codepoints: List<Int>,
        pixelsPerEm: Double,
    ): Dump {
        require(codepoints.isNotEmpty()) { "a sheet needs at least one code point." }
        return openRasterFixture(fixtureBytes(fontPath), outlineRequirements()).use { fixture ->
            val images = codepoints.map { codepoint -> resolveOutline(fixture, codepoint, pixelsPerEm) }
            Dump(
                bytes = renderCoverageSheet(images),
                note = "$COLUMNS columns, ${pixelsPerEm.toInt()} pixels per em, flipped vertically",
            )
        }
    }

    /** Renders a color sheet as a flipped P6 PPM (glyph colors over white). */
    fun paintSheet(
        fontPath: String,
        codepoints: List<Int>,
        pixelsPerEm: Double,
        paletteIndex: Int,
    ): Dump {
        require(codepoints.isNotEmpty()) { "a sheet needs at least one code point." }
        return openRasterFixture(
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
    }

    /** Renders one normalized bitmap strike as a P6 PPM (black ink over white). */
    fun bitmapDump(
        fontPath: String,
        codepoint: Int,
    ): Dump = openRasterFixture(
        fixtureBytes(fontPath),
        FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile())),
    ).use { fixture ->
        val glyph = resolveGlyph(fixture, codepoint)
        val bitmap = assertIs<GlyphRepresentation.Bitmap>(
            requireSuccess(codepoint, "materialization", fixture.asset.resolveGlyph(FontGlyphRequest(glyph))),
            "${label(codepoint)} is not a bitmap representation",
        ).bitmap
        val image = requireRasterized(
            codepoint,
            GlyphRasterizer.rasterizeBitmap(
                bitmap,
                BitmapRasterRequest(org.graphiks.kalligraphie.api.GlyphColor(0, 0, 0, 255)),
            ),
        )
        val canvas = RgbaCanvas(image.width + 2 * PADDING, image.height + 2 * PADDING)
        canvas.drawBitmap(image, PADDING, PADDING)
        Dump(
            bytes = canvas.toPpm(),
            note = "strike ${bitmap.strike.pixelsPerEmX}x${bitmap.strike.pixelsPerEmY}, black ink over white",
        )
    }

    private fun resolveOutline(fixture: RasterFixture, codepoint: Int, pixelsPerEm: Double): A8Image {
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

    private fun resolvePaint(fixture: RasterFixture, codepoint: Int, pixelsPerEm: Double): Rgba8Image {
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

    private fun resolveGlyph(fixture: RasterFixture, codepoint: Int): Int {
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
