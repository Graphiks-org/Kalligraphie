package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.GlyphPaintIR
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
        if (codepoints.size >= 3) {
            check(hasChroma(rows.first().first())) { "the gradient column carries no chroma." }
        }
        rows.forEachIndexed { index, row ->
            if (index == 0) return@forEachIndexed
            row.forEachIndexed { column, image ->
                check(!sameRaster(image, rows[0][column])) {
                    "U+" + codepoints[column].toString(16) + " is identical at wght ${weights[0]} and ${weights[index]}"
                }
            }
        }
        if (codepoints.size >= 3) {
            rows.forEachIndexed { index, row ->
                check(sameRaster(row[1], row[2])) {
                    "the inert transform changed a raster at wght ${weights[index]}"
                }
            }
        }
        return composeOverWhite(rows, codepoints.size)
    }

    /**
     * Renders a real colour font's alphabet across [weights] as a paint sheet over white.
     *
     * Unlike [sheet], the columns are unrelated letterforms, so no cross-column equality is
     * asserted: every cell must carry ink and coverage, the sheet must carry chroma, and each
     * weight's row must differ from the first.
     */
    fun alphabetSheet(
        corpus: FixtureCorpus,
        fontPath: String,
        codepoints: List<Int>,
        weights: List<Float>,
        pixelsPerEm: Double,
    ): GoldenImage {
        require(codepoints.isNotEmpty()) { "an alphabet sheet needs at least one code point." }
        require(weights.size >= 2) { "an alphabet sheet needs at least two instances to prove variation." }
        val rows = weights.map { weight ->
            codepoints.map { codePoint -> rasterize(corpus, fontPath, codePoint, weight, pixelsPerEm) }
        }
        rows.forEach { row ->
            row.forEach { image ->
                check(image.width > 0 && image.height > 0) { "an alphabet cell produced no ink." }
                check(hasInk(image)) { "an alphabet cell produced no coverage." }
            }
        }
        check(rows.any { row -> row.any { image -> hasChroma(image) } }) { "the alphabet sheet carries no chroma." }
        rows.forEachIndexed { index, row ->
            if (index == 0) return@forEachIndexed
            row.forEachIndexed { column, image ->
                check(!sameRaster(image, rows[0][column])) {
                    "U+" + codepoints[column].toString(16) + " is identical at wght ${weights[0]} and ${weights[index]}"
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

    private fun hasChroma(image: Rgba8Image): Boolean {
        val pixels = image.copyPixels()
        var index = 0
        while (index * 4 + 3 < pixels.size) {
            val red = pixels[index * 4].toInt() and 0xFF
            val green = pixels[index * 4 + 1].toInt() and 0xFF
            val blue = pixels[index * 4 + 2].toInt() and 0xFF
            val alpha = pixels[index * 4 + 3].toInt() and 0xFF
            if (alpha != 0 && (red != green || green != blue)) return true
            index += 1
        }
        return false
    }

    private fun sameRaster(left: Rgba8Image, right: Rgba8Image): Boolean =
        left.left == right.left && left.top == right.top &&
            left.width == right.width && left.height == right.height &&
            left.copyPixels().toList() == right.copyPixels().toList()

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
            val unitsPerEm = designScale(paint)
            requireRasterized(codePoint, GlyphRasterizer.rasterizePaint(paint, PaintRasterRequest(pixelsPerEm, unitsPerEm)))
        }

    /**
     * The design scale of a paint graph: the first outline it carries, whether a clip inside a
     * real font's group or the synthetic scene's clip root.
     */
    private fun designScale(paint: GlyphPaintIR): Int =
        paint.nodes.filterIsInstance<GlyphPaintNode.GlyphClip>().firstOrNull()?.outline?.unitsPerEm
            ?: paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().firstOrNull()?.outline?.unitsPerEm
            ?: error("the paint graph carries no outline to read a design scale from")

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
