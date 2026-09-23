package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenOrientation
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.fixture.FixtureCorpus
import org.graphiks.kalligraphie.e2e.golden.GlyphSheetScenes
import org.graphiks.kalligraphie.e2e.golden.bitmapOf
import org.graphiks.kalligraphie.e2e.golden.bitmapRequirements
import org.graphiks.kalligraphie.e2e.golden.colourBitmapRequirements
import org.graphiks.kalligraphie.e2e.golden.openOutlineFixture
import org.graphiks.kalligraphie.e2e.golden.outlineOf
import org.graphiks.kalligraphie.e2e.golden.openRenderableFixture
import org.graphiks.kalligraphie.e2e.golden.paintOf
import org.graphiks.kalligraphie.e2e.golden.paintRequirements
import org.graphiks.kalligraphie.raster.BitmapRasterRequest
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.OutlineRasterRequest
import org.graphiks.kalligraphie.raster.PaintRasterRequest
import org.graphiks.kalligraphie.raster.RasterResult

/**
 * The scenes that need the portable glyph-representation route and nothing else.
 *
 * These render a font's bytes into outlines, paint graphs, bitmaps or sheets and hand the result to
 * the CPU rasterizer, so they run wherever the platform declares that route: they are the half of
 * the catalogue every platform can verify. The scenes that lay text out through the paragraph
 * facade are registered beside them in `JvmSceneRenderers`, and the harness checks that this map
 * holds exactly the entries whose route the platform can serve.
 */
internal object PortableSceneRenderers {
    /** Every portable renderer, keyed by catalog entry id. */
    val byId: Map<String, CatalogSceneRenderer> = mapOf(
        "outline.glyf-simple-composite" to CatalogSceneRenderer(
            fontPath = LIBERATION_SANS,
            route = CatalogRoute.PORTABLE_GLYPH,
            sceneId = "glyph.outline.liberation-sans.A.64",
            render = ::renderLiberationCapitalA,
        ),
        "color.colr-v0-single-glyph" to CatalogSceneRenderer(
            fontPath = EMOJI_TWO_COLR_V0,
            route = CatalogRoute.PORTABLE_GLYPH,
            sceneId = "glyph.paint.emoji-two-colr-v0.u1F600.64",
            render = ::renderEmojiTwoPaint,
        ),
        "bitmap.ebdt-format1" to CatalogSceneRenderer(
            fontPath = SKIA_EBDT_FORMAT1,
            route = CatalogRoute.PORTABLE_GLYPH,
            sceneId = "glyph.bitmap.skia-ebdt-format1.u1F600.16",
            render = ::renderEbdtFormat1Bitmap,
        ),
        "script.latin.outline-sheet" to CatalogSceneRenderer(LIBERATION_SANS, CatalogRoute.PORTABLE_GLYPH, sceneId = "sheet.outline.liberation-latin.32") { corpus ->
            composed { GlyphSheetScenes.outlineSheet(corpus, LIBERATION_SANS, LATIN, 32.0) }
        },
        "script.greek.outline-sheet" to CatalogSceneRenderer(LIBERATION_SANS, CatalogRoute.PORTABLE_GLYPH, sceneId = "sheet.outline.liberation-greek.32") { corpus ->
            composed { GlyphSheetScenes.outlineSheet(corpus, LIBERATION_SANS, GREEK, 32.0) }
        },
        "script.cyrillic.outline-sheet" to CatalogSceneRenderer(LIBERATION_SANS, CatalogRoute.PORTABLE_GLYPH, sceneId = "sheet.outline.liberation-cyrillic.32") { corpus ->
            composed { GlyphSheetScenes.outlineSheet(corpus, LIBERATION_SANS, CYRILLIC, 32.0) }
        },
        "script.arabic.outline-sheet" to CatalogSceneRenderer(AMIRI, CatalogRoute.PORTABLE_GLYPH, sceneId = "sheet.outline.amiri-arabic.32") { corpus ->
            composed { GlyphSheetScenes.outlineSheet(corpus, AMIRI, ARABIC, 32.0) }
        },
        "script.devanagari.outline-sheet" to CatalogSceneRenderer(NOTO_DEVANAGARI, CatalogRoute.PORTABLE_GLYPH, sceneId = "sheet.outline.noto-devanagari.32") { corpus ->
            composed { GlyphSheetScenes.outlineSheet(corpus, NOTO_DEVANAGARI, DEVANAGARI, 32.0) }
        },
        "color.colr-v0-alphabet-sheet" to CatalogSceneRenderer(BUNGEE_COLOR, CatalogRoute.PORTABLE_GLYPH, sceneId = "sheet.paint.bungee-color-latin.48") { corpus ->
            composed { GlyphSheetScenes.paintSheet(corpus, BUNGEE_COLOR, LATIN_LETTERS, 48.0, paletteIndex = 0) }
        },
        "color.colr-v0-emoji-sheet" to CatalogSceneRenderer(EMOJI_TWO_COLR_V0, CatalogRoute.PORTABLE_GLYPH, sceneId = "sheet.paint.emoji-two-colr-v0.64") { corpus ->
            composed { GlyphSheetScenes.paintSheet(corpus, EMOJI_TWO_COLR_V0, EMOJI, 64.0, paletteIndex = 0) }
        },
        "bitmap.cbdt-png.u1f600.16" to CatalogSceneRenderer(SKIA_CBDT, CatalogRoute.PORTABLE_GLYPH, render = ::renderCbdtColourStrike),
        "bitmap.sbix-png.u1f600.16" to CatalogSceneRenderer(SKIA_SBIX, CatalogRoute.PORTABLE_GLYPH, render = ::renderSbixColourStrike),
        "outline.cff1-static" to CatalogSceneRenderer(CFF_LIBERATION, CatalogRoute.PORTABLE_GLYPH, render = ::renderCff1CapitalA),
        "outline.cff2-static" to CatalogSceneRenderer(CFF2_LIBERATION, CatalogRoute.PORTABLE_GLYPH, render = ::renderCff2CapitalA),
        "metrics.vvar-advance-height" to CatalogSceneRenderer(KALLIGRAPHIE_VAR_VVAR, CatalogRoute.PORTABLE_GLYPH, render = ::renderVvarCapitalA),
    )

    private fun renderCbdtColourStrike(corpus: FixtureCorpus): GoldenRenderOutcome = colourStrike(corpus, SKIA_CBDT, "CBLC/CBDT strike")

    private fun renderSbixColourStrike(corpus: FixtureCorpus): GoldenRenderOutcome = colourStrike(corpus, SKIA_SBIX, "sbix strike")

    private fun renderCff1CapitalA(corpus: FixtureCorpus): GoldenRenderOutcome = outlineCapitalA(corpus, CFF_LIBERATION, "CFF 1 'A'")

    private fun renderCff2CapitalA(corpus: FixtureCorpus): GoldenRenderOutcome = outlineCapitalA(corpus, CFF2_LIBERATION, "CFF 2 'A'")

    private fun renderVvarCapitalA(corpus: FixtureCorpus): GoldenRenderOutcome = outlineCapitalA(corpus, KALLIGRAPHIE_VAR_VVAR, "VVAR fixture 'A'")

    /** Renders the U+1F600 bitmap of [fontPath], as the 16 ppem RGBA strike it resolves to. */
    private fun colourStrike(corpus: FixtureCorpus, fontPath: String, what: String): GoldenRenderOutcome =
        openRenderableFixture(corpus.bytes(fontPath), colourBitmapRequirements()).use { fixture ->
            val bitmap = fixture.bitmapOf(0x1F600)
            when (val result = GlyphRasterizer.rasterizeBitmap(bitmap, BitmapRasterRequest(GlyphColor(0, 0, 0, 255)))) {
                // A normalized strike already arrives in image orientation: its decoded rows run top
                // to bottom, and the bitmap compositor copies them one-to-one.
                is RasterResult.Success -> GoldenRenderOutcome.Rendered(
                    GoldenImage.rgba8(result.value.width, result.value.height, result.value.copyPixels()),
                )

                is RasterResult.Failure -> refused(what, result.diagnostics.first().field)
            }
        }

    /** Renders the capital A of the font at [fontPath] through the portable outline route. */
    private fun outlineCapitalA(corpus: FixtureCorpus, fontPath: String, what: String): GoldenRenderOutcome =
        openOutlineFixture(corpus.bytes(fontPath)).use { fixture ->
            val outline = fixture.outlineOf(0x41)
            when (val result = GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm = 64.0))) {
                // The outline route hands back the rasterizer's own rows, whose row zero is the
                // smallest design y — the visual bottom. Declaring it here is what lets the dump
                // writer put it back upright.
                is RasterResult.Success -> GoldenRenderOutcome.Rendered(
                    GoldenImage.alpha8(
                        result.value.width,
                        result.value.height,
                        result.value.copyPixels(),
                        GoldenOrientation.DESIGN,
                    ),
                )

                is RasterResult.Failure -> refused(what, result.diagnostics.first().field)
            }
        }

    private fun renderLiberationCapitalA(corpus: FixtureCorpus): GoldenRenderOutcome =
        openOutlineFixture(corpus.bytes(LIBERATION_SANS)).use { fixture ->
            val outline = fixture.outlineOf(0x41)
            when (val result = GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm = 64.0))) {
                is RasterResult.Success -> GoldenRenderOutcome.Rendered(
                    GoldenImage.alpha8(
                        result.value.width,
                        result.value.height,
                        result.value.copyPixels(),
                        GoldenOrientation.DESIGN,
                    ),
                )

                is RasterResult.Failure -> refused("Liberation Sans 'A'", result.diagnostics.first().field)
            }
        }

    private fun renderEmojiTwoPaint(corpus: FixtureCorpus): GoldenRenderOutcome =
        openRenderableFixture(
            bytes = corpus.bytes(EMOJI_TWO_COLR_V0),
            requirements = paintRequirements(),
            renderVariant = FontRenderVariantSnapshot(cpalPaletteIndex = 0),
        ).use { fixture ->
            val paint = fixture.paintOf(0x1F600)
            val solidOutline = paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().firstOrNull()
            if (solidOutline == null) {
                GoldenRenderOutcome.Refused(
                    code = GoldenDiagnosticCode.RENDER_FAILED,
                    detail = "paint graph has no solid outline",
                )
            } else {
                val request = PaintRasterRequest(pixelsPerEm = 64.0, unitsPerEm = solidOutline.outline.unitsPerEm)
                when (val result = GlyphRasterizer.rasterizePaint(paint, request)) {
                    // The paint route keeps the rasterizer's design orientation too, which the
                    // composed colour sheets reverse glyph by glyph when they draw.
                    is RasterResult.Success -> GoldenRenderOutcome.Rendered(
                        GoldenImage.rgba8(
                            result.value.width,
                            result.value.height,
                            result.value.copyPixels(),
                            GoldenOrientation.DESIGN,
                        ),
                    )

                    is RasterResult.Failure -> refused("emoji COLRv0 paint", result.diagnostics.first().field)
                }
            }
        }

    private fun renderEbdtFormat1Bitmap(corpus: FixtureCorpus): GoldenRenderOutcome =
        openRenderableFixture(
            bytes = corpus.bytes(SKIA_EBDT_FORMAT1),
            requirements = bitmapRequirements(),
        ).use { fixture ->
            val bitmap = fixture.bitmapOf(0x1F600)
            if (bitmap.strike != BitmapStrike(16, 16, 1)) {
                GoldenRenderOutcome.Refused(
                    code = GoldenDiagnosticCode.RENDER_FAILED,
                    detail = "resolved strike ${bitmap.strike}, expected BitmapStrike(16, 16, 1)",
                )
            } else {
                val request = BitmapRasterRequest(GlyphColor(0, 0, 0, 255))
                when (val result = GlyphRasterizer.rasterizeBitmap(bitmap, request)) {
                    // Same as the CBDT and sbix strikes: decoded rows already run top to bottom.
                    is RasterResult.Success -> GoldenRenderOutcome.Rendered(
                        GoldenImage.rgba8(result.value.width, result.value.height, result.value.copyPixels()),
                    )

                    is RasterResult.Failure -> refused("EBDT format 1 bitmap", result.diagnostics.first().field)
                }
            }
        }
}
