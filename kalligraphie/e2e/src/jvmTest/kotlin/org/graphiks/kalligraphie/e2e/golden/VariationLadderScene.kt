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

        val rungs = rows.flatMapIndexed { row, glyphs ->
            glyphs.mapNotNull { glyph -> glyph.inkInCanvas(row * LEADING) }
        }
        val ink = unionOf(rungs) ?: error("'$text' produced no ink to lay out.")
        val canvas = A8Canvas(ink.width + 2 * PADDING, ink.height + 2 * PADDING)
        rows.forEachIndexed { row, glyphs ->
            glyphs.forEach { glyph ->
                canvas.drawCoverage(
                    image = glyph.image,
                    penX = glyph.penX - ink.minX + PADDING,
                    baselineY = glyph.baselineY - ink.minY + PADDING + row * LEADING,
                )
            }
        }
        return canvas.toGoldenImage()
    }

    /** Sums the coverage of one row of placed glyphs, the ink the ladder compares between weights. */
    private fun coverageOf(row: List<ComposedLineScenes.PlacedGlyph>): Long =
        row.sumOf { glyph -> glyph.image.copyPixels().sumOf { sample -> (sample.toInt() and 0xFF).toLong() } }
}
