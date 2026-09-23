package org.graphiks.kalligraphie.e2e.golden

import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.raster.A8Image
import org.graphiks.kalligraphie.raster.BitmapRasterRequest
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.OutlineRasterRequest
import org.graphiks.kalligraphie.raster.PaintRasterRequest
import org.graphiks.kalligraphie.raster.RasterResult
import org.graphiks.kalligraphie.raster.Rgba8Image

/**
 * Composes one image out of every rendering route the corpus can drive.
 *
 * The mosaic answers a question no single-route scene can: *can a consumer mix faces, styles and
 * representations in one artefact?* Each band is laid out on its own — one word per outline decoder,
 * one line whose scripts only three faces together cover, one glyph at four weights, one colour paint
 * and one bitmap strike — and every band lands on one shared colour canvas with a common left margin,
 * so the routes are compared side by side rather than inferred.
 *
 * The canvas is cropped to the ink of all bands, on the same rule the composed lines use, so an
 * auto-sized frame re-places it without moving a pixel.
 *
 * Two premises are asserted rather than assumed, because a mosaic that stopped mixing would still
 * produce a plausible picture: the heaviest weight of the style band must carry more coverage than
 * the lightest, and the composed canvas must carry chroma — proof that the colour band really painted
 * colour rather than a greyscale silhouette.
 */
internal object CompositionMosaicScene {
    private const val LIBERATION = "/fonts/liberation/LiberationSans-Regular.ttf"
    private const val AMIRI = "/fonts/amiri/Amiri-Regular.ttf"
    private const val NOTO_DEVANAGARI = "/fonts/noto-devanagari/NotoSansDevanagari-Regular.ttf"
    private const val NOTO_SANS_JP = "/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"
    private const val CFF_LIBERATION = "/fonts/cff-liberation/LiberationSans-CFF.otf"
    private const val CFF2_LIBERATION = "/fonts/cff2-liberation/LiberationSans-CFF2.otf"
    private const val EMOJI_TWO_COLR_V0 = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf"
    private const val SKIA_CBDT = "/fonts/skia-cbdt/cbdt.ttf"

    /** Left margin every band starts from, in pixels. */
    private const val MARGIN = 8

    /** Vertical gap between two bands, in pixels. */
    private const val BAND_GAP = 8

    /** Horizontal gap between two cells of the style band, in pixels. */
    private const val CELL_GAP = 12

    /** Margin around the composed ink, in pixels; the entry's frame declares the same padding. */
    private const val PADDING = 2

    /** The ink every monochrome band composites with, so the colour band stands out. */
    private const val INK = 0x141414

    /** The design weights of the style band, lightest first. */
    private val STYLE_WEIGHTS: List<Float> = listOf(100f, 400f, 700f, 900f)

    /** The word every outline band renders, so the decoders are compared on the same shapes. */
    private const val WORD = "Kalligraphie"

    /** The line whose scripts only three faces together can cover. */
    private const val MULTI_SCRIPT = "Ελληνικά — العربية — देवनागरी"

    /** Renders the mosaic: seven bands, one colour canvas, one shared left margin. */
    fun mosaic(): GoldenImage {
        val bands = ArrayList<Band>()
        // The one family carrying a full alphabet renders the word, laid out and shaped by the facade.
        bands += Band.coverage(
            ComposedLineScenes.placeLine(
                text = WORD,
                language = "en",
                requiredFaces = 1,
                fontPaths = listOf(LIBERATION),
            ),
        )
        // The two converted fixtures carry a single glyph each, so each contributes that letter: the
        // band still shows the decoder, while the word is what only the full family can lay out.
        bands += Band.coverage(listOf(glyphAt(CFF_LIBERATION)))
        bands += Band.coverage(listOf(glyphAt(CFF2_LIBERATION)))
        bands += Band.coverage(
            ComposedLineScenes.placeLine(
                text = MULTI_SCRIPT,
                language = "en",
                baseDirection = BaseDirection.LEFT_TO_RIGHT,
                requiredFaces = 3,
                fontPaths = listOf(LIBERATION, AMIRI, NOTO_DEVANAGARI),
            ),
        )
        val styles = styleCells()
        val light = coverageOf(styles.first())
        val heavy = coverageOf(styles.last())
        check(heavy > light) {
            "the style band carries $heavy coverage at wght ${STYLE_WEIGHTS.last()} and $light at " +
                "wght ${STYLE_WEIGHTS.first()}: the variation did not reach the outlines"
        }
        bands += Band.style(styles)
        val paint = emojiPaint()
        check(hasChroma(paint)) {
            "the colour band carries no chroma: the COLR v0 paint rasterised to grey, so the mosaic " +
                "would show a silhouette where the catalogue expects colour"
        }
        bands += Band.colour(paint)
        bands += Band.strike(bitmapStrike())
        return compose(bands)
    }

    /** One band of the mosaic: the ink it occupies and how to paint it at a canvas origin. */
    private class Band private constructor(
        val ink: CanvasInk,
        private val paint: (RgbaCanvas, Int, Int) -> Unit,
    ) {
        /** Number of rows this band occupies. */
        val height: Int get() = ink.height

        /** Paints this band into [canvas], shifted by ([shiftX], [shiftY]). */
        fun paintInto(canvas: RgbaCanvas, shiftX: Int, shiftY: Int) = paint(canvas, shiftX, shiftY)

        companion object {
            /** A band of coverage glyphs, drawn with the mosaic ink on their own baselines. */
            fun coverage(glyphs: List<ComposedLineScenes.PlacedGlyph>): Band {
                val ink = glyphs.inkInCanvas() ?: error("a coverage band produced no ink")
                return Band(ink) { canvas, shiftX, shiftY ->
                    glyphs.forEach { glyph ->
                        canvas.drawCoverage(
                            image = glyph.image,
                            penX = glyph.penX + shiftX,
                            baselineY = glyph.baselineY + shiftY,
                            ink = INK,
                        )
                    }
                }
            }

            /** A band of style cells, laid side by side with [CELL_GAP] between them. */
            fun style(cells: List<List<ComposedLineScenes.PlacedGlyph>>): Band {
                val boxes = cells.map { cell -> cell.inkInCanvas() ?: error("a style cell carries no ink") }
                val baseline = boxes.maxOf { box -> box.maxY }
                val placed = ArrayList<Placed>()
                var cursor = 0
                for ((index, cell) in cells.withIndex()) {
                    val cellX = cursor - boxes[index].minX
                    placed += Placed(cell, cellX, baseline - boxes[index].maxY)
                    cursor += boxes[index].width + CELL_GAP
                }
                val ink = CanvasInk(
                    minX = 0,
                    minY = baseline + boxes.minOf { box -> box.minY - box.maxY },
                    maxX = cursor - CELL_GAP - 1,
                    maxY = baseline,
                )
                return Band(ink) { canvas, shiftX, shiftY ->
                    for ((cell, cellX, cellY) in placed) {
                        cell.forEach { glyph ->
                            canvas.drawCoverage(
                                image = glyph.image,
                                penX = glyph.penX + cellX + shiftX,
                                baselineY = glyph.baselineY + cellY + shiftY,
                                ink = INK,
                            )
                        }
                    }
                }
            }

            /** A colour glyph, drawn on its own baseline the way the colour sheets draw one. */
            fun colour(image: Rgba8Image): Band {
                val baseline = image.height
                val ink = inkInCanvas(image, x = 0, y = baseline, flipped = true)
                    ?: error("the colour band produced no ink")
                return Band(ink) { canvas, shiftX, shiftY ->
                    canvas.drawColor(image, penX = shiftX, baselineY = baseline + shiftY)
                }
            }

            /** A bitmap strike, drawn top-left at the size the strike really is. */
            fun strike(image: Rgba8Image): Band {
                val ink = inkInCanvas(image, x = 0, y = 0, flipped = false)
                    ?: error("the bitmap band produced no ink")
                return Band(ink) { canvas, shiftX, shiftY ->
                    canvas.drawBitmap(image, x = shiftX, y = shiftY)
                }
            }
        }
    }

    /** One style cell together with the offset that aligns it on the band's shared baseline. */
    private data class Placed(
        val cell: List<ComposedLineScenes.PlacedGlyph>,
        val cellX: Int,
        val cellY: Int,
    )

    /**
     * Returns the style cells, one per weight, each already laid out at its own instance.
     *
     * The family is the single-glyph vertical-metrics fixture: a real font with `gvar`, so the
     * weights change the drawing, and one the shaping suite already cross-checks against HarfBuzz.
     * A Latin variable family with a full alphabet is deliberately left to the weight ladder, which
     * renders text; here the point is that the *same* image carries several styles at once.
     */
    private fun styleCells(): List<List<ComposedLineScenes.PlacedGlyph>> = STYLE_WEIGHTS.map { weight ->
        ComposedLineScenes.placeLine(
            text = "A",
            language = "en",
            requiredFaces = 1,
            fontPaths = listOf(NOTO_SANS_JP),
            variation = FontVariationCoordinates(listOf(FontVariationCoordinate(tag = "wght", value = weight))),
        )
    }

    /** Stacks the bands and paints them twice: once to measure the ink, once to frame it. */
    private fun compose(bands: List<Band>): GoldenImage {
        val draft = RgbaCanvas(
            width = bands.maxOf { band -> band.ink.width } + 2 * MARGIN,
            height = bands.sumOf { band -> band.height } + (bands.size - 1) * BAND_GAP + 2 * MARGIN,
        )
        paint(bands, draft, 0, 0)
        check(hasChroma(draft)) { "the composed mosaic carries no chroma: every band painted grey" }
        val ink = draft.inkBox() ?: error("the mosaic produced no ink")
        val canvas = RgbaCanvas(ink.width + 2 * PADDING, ink.height + 2 * PADDING)
        paint(bands, canvas, PADDING - ink.minX, PADDING - ink.minY)
        return canvas.toGoldenImage()
    }

    /** Paints every band top to bottom, [MARGIN] from the left and [BAND_GAP] apart. */
    private fun paint(bands: List<Band>, canvas: RgbaCanvas, shiftX: Int, shiftY: Int) {
        var cursor = MARGIN
        for (band in bands) {
            band.paintInto(canvas, MARGIN - band.ink.minX + shiftX, cursor - band.ink.minY + shiftY)
            cursor += band.height + BAND_GAP
        }
    }

    /** Rasterises the capital A of [path] at 64 pixels per em, on its own glyph origin. */
    private fun glyphAt(path: String): ComposedLineScenes.PlacedGlyph =
        openOutlineFixture(fixtureBytes(path)).use { fixture ->
            val image = assertIs<RasterResult.Success<A8Image>>(
                GlyphRasterizer.rasterizeOutline(fixture.outlineOf(0x41), OutlineRasterRequest(pixelsPerEm = 64.0)),
            ).value
            ComposedLineScenes.PlacedGlyph(image = image, penX = 0, baselineY = 0)
        }

    /** The U+1F600 paint of the COLR v0 emoji family, rasterised at 64 pixels per em. */    private fun emojiPaint(): Rgba8Image =
        openRenderableFixture(
            bytes = fixtureBytes(EMOJI_TWO_COLR_V0),
            requirements = paintRequirements(),
            renderVariant = FontRenderVariantSnapshot(cpalPaletteIndex = 0),
        ).use { fixture ->
            val paint = fixture.paintOf(0x1F600)
            val solid = paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().firstOrNull()
                ?: error("the emoji paint graph has no solid outline to scale from")
            assertIs<RasterResult.Success<Rgba8Image>>(
                GlyphRasterizer.rasterizePaint(
                    paint,
                    PaintRasterRequest(pixelsPerEm = 64.0, unitsPerEm = solid.outline.unitsPerEm),
                ),
            ).value
        }

    /** The U+1F600 CBDT strike, at the size the strike really is: this route does not scale it. */
    private fun bitmapStrike(): Rgba8Image =
        openRenderableFixture(
            bytes = fixtureBytes(SKIA_CBDT),
            requirements = colourBitmapRequirements(),
        ).use { fixture ->
            assertIs<RasterResult.Success<Rgba8Image>>(
                GlyphRasterizer.rasterizeBitmap(
                    fixture.bitmapOf(0x1F600),
                    BitmapRasterRequest(GlyphColor(0, 0, 0, 255)),
                ),
            ).value
        }

    /** Returns whether any canvas pixel carries chroma rather than grey. */
    private fun hasChroma(canvas: RgbaCanvas): Boolean {
        for (y in 0 until canvas.height) {
            for (x in 0 until canvas.width) {
                if (hasChroma(canvas.pixel(x, y))) return true
            }
        }
        return false
    }

    /** Returns whether any pixel of [image] carries chroma; transparent pixels count as grey. */
    private fun hasChroma(image: Rgba8Image): Boolean {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (hasChroma(image[x, y])) return true
            }
        }
        return false
    }

    /** Returns whether the packed pixel `0xAARRGGBB` carries chroma. */
    private fun hasChroma(pixel: Int): Boolean {
        val red = (pixel ushr 16) and 0xFF
        val green = (pixel ushr 8) and 0xFF
        val blue = pixel and 0xFF
        return red != green || green != blue
    }

    /** Sums the coverage of one style cell, the ink the mosaic compares between weights. */
    private fun coverageOf(cell: List<ComposedLineScenes.PlacedGlyph>): Long =
        cell.sumOf { glyph -> glyph.image.copyPixels().sumOf { sample -> (sample.toInt() and 0xFF).toLong() } }
}
