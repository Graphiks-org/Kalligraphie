package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.raster.A8Image

/**
 * Composes one text at several design weights of one variable font into a single coverage canvas.
 *
 * Every row is laid out and shaped independently — a paragraph request carries one instance
 * descriptor, so a paragraph cannot mix weights — and the rows share one baseline grid: row `r`'s
 * baseline sits `r * LEADING` below the first. That is what makes the styles comparable rather than
 * merely stacked.
 *
 * The canvas is cropped to the ink of every row at once, on the same rule the composed lines use:
 * [PADDING] exactly around the ink, which is also the padding the catalogued entry declares, so an
 * auto-sized frame re-places the image without moving a pixel.
 *
 * The scene asserts its own premise: the weights must really reach the geometry, so the heaviest row
 * has to carry more ink than the lightest. A face whose variation silently stopped applying would
 * otherwise render five identical rows and pin them as a golden fingerprint.
 */
internal object VariationLadderScene {
    /** Vertical distance between two consecutive baselines, in pixels. */
    private const val LEADING = 64

    /** Margin around the composed rows, in pixels; the entry's frame declares the same padding. */
    private const val PADDING = 2

    /**
     * Renders [text] once per entry of [weights], on one canvas with the rows [LEADING] apart.
     *
     * @param fontPath the variable face to instantiate at every weight.
     * @param weights design `wght` values, lightest first; at least two are required, since a ladder
     * of one rung says nothing about variation.
     */
    fun ladder(text: String, fontPath: String, weights: List<Float>, language: String = "en"): GoldenImage {
        require(weights.size >= 2) { "a weight ladder needs at least two weights." }
        require(weights.zipWithNext().all { (lighter, heavier) -> lighter < heavier }) {
            "a weight ladder must be ordered from the lightest weight to the heaviest."
        }
        val rows = weights.map { weight ->
            ComposedLineScenes.placeLine(
                text = text,
                language = language,
                baseDirection = BaseDirection.LEFT_TO_RIGHT,
                requiredFaces = 1,
                fontPaths = listOf(fontPath),
                variation = FontVariationCoordinates(listOf(FontVariationCoordinate(tag = "wght", value = weight))),
            )
        }
        val light = coverageOf(rows.first())
        val heavy = coverageOf(rows.last())
        check(heavy > light) {
            "'$text' carries $heavy coverage at wght ${weights.last()} and $light at wght " +
                "${weights.first()}: the variation did not reach the outlines"
        }

        val rungs = rows.flatMapIndexed { row, glyphs -> glyphs.map { glyph -> Rung(glyph, row) } }
        val ink = rungs.mapNotNull { rung -> rung.inkInCanvasSpace() }
        check(ink.isNotEmpty()) { "'$text' produced no ink to lay out." }
        val inkLeft = ink.minOf { box -> box.minX }
        val inkTop = ink.minOf { box -> box.minY }
        val inkRight = ink.maxOf { box -> box.maxX }
        val inkBottom = ink.maxOf { box -> box.maxY }
        val canvas = A8Canvas(inkRight - inkLeft + 1 + 2 * PADDING, inkBottom - inkTop + 1 + 2 * PADDING)
        rungs.forEach { rung ->
            canvas.drawCoverage(
                image = rung.glyph.image,
                penX = rung.glyph.penX - inkLeft + PADDING,
                baselineY = rung.glyph.baselineY - inkTop + PADDING + rung.row * LEADING,
            )
        }
        return canvas.toGoldenImage()
    }

    /** One placed glyph together with the ladder row it belongs to. */
    private class Rung(val glyph: ComposedLineScenes.PlacedGlyph, val row: Int) {
        /**
         * Returns the ink of this glyph in ladder-canvas coordinates, or `null` when it carries none.
         *
         * The canvas is in image orientation while the raster is in design orientation, so the
         * raster's first row is the canvas' last one — the same reversal `A8Canvas.drawCoverage`
         * applies while it draws.
         */
        fun inkInCanvasSpace(): InkBox? {
            val box = inkBoxOf(glyph.image) ?: return null
            val x0 = glyph.penX + glyph.image.left
            val y0 = glyph.baselineY - (glyph.image.top + glyph.image.height) + row * LEADING
            return InkBox(
                minX = x0 + box.minColumn,
                maxX = x0 + box.maxColumn,
                minY = y0 + (glyph.image.height - 1 - box.maxRow),
                maxY = y0 + (glyph.image.height - 1 - box.minRow),
            )
        }
    }

    /** Ink extent of one raster, in the raster's own columns and rows. */
    private class RasterInk(val minColumn: Int, val maxColumn: Int, val minRow: Int, val maxRow: Int)

    /** Ink extent of one element in ladder-canvas coordinates, both bounds inclusive. */
    private class InkBox(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    /** Returns the ink extent of [image], or `null` when every sample is empty. */
    private fun inkBoxOf(image: A8Image): RasterInk? {
        var minColumn = Int.MAX_VALUE
        var maxColumn = -1
        var minRow = Int.MAX_VALUE
        var maxRow = -1
        for (row in 0 until image.height) {
            for (column in 0 until image.width) {
                if (image[column, row] == 0) continue
                if (column < minColumn) minColumn = column
                if (column > maxColumn) maxColumn = column
                if (row < minRow) minRow = row
                if (row > maxRow) maxRow = row
            }
        }
        return if (maxColumn < 0) null else RasterInk(minColumn, maxColumn, minRow, maxRow)
    }

    /** Sums the coverage of one row of placed glyphs, the ink the ladder compares between weights. */
    private fun coverageOf(row: List<ComposedLineScenes.PlacedGlyph>): Long =
        row.sumOf { glyph -> glyph.image.copyPixels().sumOf { sample -> (sample.toInt() and 0xFF).toLong() } }
}
