package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.fixture.FixtureCorpus
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.PaintRasterRequest
import org.graphiks.kalligraphie.raster.RasterDiagnostic
import org.graphiks.kalligraphie.raster.RasterResult
import org.graphiks.kalligraphie.raster.Rgba8Image
import kotlin.test.assertIs

/**
 * Renders the variable COLR v1 fixture as a paint sheet across [weights] and [codepoints].
 *
 * The scene asserts its own premises on the **raw** rasters, before the opaque white backing
 * would flatten every alpha to 255: each cell must carry ink, each `wght` instance must differ
 * from the default one, and the fixture's inert `Transform → Solid` column must be pixel-identical
 * to its plain `Solid` column. A variation that silently stopped applying would otherwise be
 * pinned as a plausible picture.
 */
internal object VariablePaintSheetScene {
    private const val PADDING = 2

    fun sheet(
        corpus: FixtureCorpus,
        fontPath: String,
        codepoints: List<Int>,
        weights: List<Float>,
        pixelsPerEm: Double,
    ): GoldenImage {
        require(codepoints.isNotEmpty()) { "a paint sheet needs at least one code point." }
        require(weights.size >= 2) { "a paint sheet needs at least two instances to prove variation." }
        val rows = weights.map { weight ->
            codepoints.map { codePoint -> rasterize(corpus, fontPath, codePoint, weight, pixelsPerEm) }
        }
        rows.forEach { row ->
            row.forEach { image ->
                check(image.width > 0 && image.height > 0) { "a paint sheet cell produced no ink." }
                check(hasInk(image)) { "a paint sheet cell produced no coverage." }
            }
        }
        rows.forEachIndexed { index, row ->
            if (index == 0) return@forEachIndexed
            row.forEachIndexed { column, image ->
                check(image.copyPixels().toList() != rows[0][column].copyPixels().toList()) {
                    "U+" + codepoints[column].toString(16) + " is identical at wght ${weights[0]} and ${weights[index]}"
                }
            }
        }
        if (codepoints.size >= 3) {
            rows.forEachIndexed { index, row ->
                check(row[1].copyPixels().toList() == row[2].copyPixels().toList()) {
                    "the inert transform changed a pixel at wght ${weights[index]}"
                }
            }
        }
        return composeOverWhite(rows, codepoints.size)
    }

    private fun hasInk(image: Rgba8Image): Boolean {
        val pixels = image.copyPixels()
        var index = 3
        while (index < pixels.size) {
            if (pixels[index].toInt() != 0) return true
            index += 4
        }
        return false
    }

    private fun rasterize(
        corpus: FixtureCorpus,
        fontPath: String,
        codePoint: Int,
        weight: Float,
        pixelsPerEm: Double,
    ): Rgba8Image =
        openRenderableFixture(
            bytes = corpus.bytes(fontPath),
            requirements = colrV1PaintRequirements(),
            variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", weight))),
        ).use { fixture ->
            val paint = fixture.paintOf(codePoint)
            val unitsPerEm = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode]).outline.unitsPerEm
            requireRasterized(codePoint, GlyphRasterizer.rasterizePaint(paint, PaintRasterRequest(pixelsPerEm, unitsPerEm)))
        }

    private fun requireRasterized(codePoint: Int, result: RasterResult<Rgba8Image>): Rgba8Image =
        when (result) {
            is RasterResult.Success -> result.value
            is RasterResult.Failure -> error(
                "U+" + codePoint.toString(16) + " rasterization failed: " +
                    result.diagnostics.joinToString { diagnostic ->
                        when (diagnostic) {
                            is RasterDiagnostic.LimitExceeded ->
                                "${diagnostic.field}(observed=${diagnostic.observed}, limit=${diagnostic.limit})"

                            is RasterDiagnostic.InvalidRequest -> "${diagnostic.field}: ${diagnostic.detail}"
                        }
                    },
            )
        }

    private fun composeOverWhite(rows: List<List<Rgba8Image>>, columns: Int): GoldenImage {
        val images = rows.flatten()
        val leftOffset = -images.minOf { image -> image.left }
        val maxAscent = images.maxOf { image -> image.top + image.height }
        val maxDescent = images.maxOf { image -> -image.top }
        val cellWidth = images.maxOf { image -> image.left + leftOffset + image.width } + 2 * PADDING
        val cellHeight = maxAscent + maxDescent + 2 * PADDING
        val canvas = RgbaCanvas(columns * cellWidth, rows.size * cellHeight)
        rows.forEachIndexed { row, cells ->
            cells.forEachIndexed { column, image ->
                canvas.drawColor(
                    image = image,
                    penX = column * cellWidth + PADDING + leftOffset,
                    baselineY = row * cellHeight + PADDING + maxAscent,
                )
            }
        }
        return canvas.toGoldenImage()
    }
}
