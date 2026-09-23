package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenOrientation
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.golden.ComposedLineScenes
import org.graphiks.kalligraphie.e2e.golden.CompositionMosaicScene
import org.graphiks.kalligraphie.e2e.golden.GlyphSheetScenes
import org.graphiks.kalligraphie.e2e.golden.VariationLadderScene
import org.graphiks.kalligraphie.e2e.golden.bitmapOf
import org.graphiks.kalligraphie.e2e.golden.bitmapRequirements
import org.graphiks.kalligraphie.e2e.golden.colourBitmapRequirements
import org.graphiks.kalligraphie.e2e.golden.fixtureBytes
import org.graphiks.kalligraphie.e2e.golden.openOutlineFixture
import org.graphiks.kalligraphie.e2e.golden.openRenderableFixture
import org.graphiks.kalligraphie.e2e.golden.outlineOf
import org.graphiks.kalligraphie.e2e.golden.outlineRequirements
import org.graphiks.kalligraphie.e2e.golden.paintOf
import org.graphiks.kalligraphie.e2e.golden.paintRequirements
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.raster.BitmapRasterRequest
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.OutlineRasterRequest
import org.graphiks.kalligraphie.raster.PaintRasterRequest
import org.graphiks.kalligraphie.raster.RasterResult

/** The renderers of the migrated scenes, keyed by catalog entry id. */
internal object SceneRenderers {
    private const val LIBERATION_SANS = "/fonts/liberation/LiberationSans-Regular.ttf"
    private const val AMIRI = "/fonts/amiri/Amiri-Regular.ttf"
    private const val NOTO_DEVANAGARI = "/fonts/noto-devanagari/NotoSansDevanagari-Regular.ttf"
    private const val BUNGEE_COLOR = "/fonts/bungee-color/BungeeColor-Regular.ttf"
    private const val EMOJI_TWO_COLR_V0 = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf"
    private const val SKIA_EBDT_FORMAT1 = "/fonts/skia-ebdt-format1/ebdt_fmt1.ttf"
    private const val SKIA_CBDT = "/fonts/skia-cbdt/cbdt.ttf"
    private const val SKIA_SBIX = "/fonts/skia-sbix/sbix.ttf"
    private const val CFF_LIBERATION = "/fonts/cff-liberation/LiberationSans-CFF.otf"
    private const val CFF2_LIBERATION = "/fonts/cff2-liberation/LiberationSans-CFF2.otf"
    private const val KALLIGRAPHIE_VAR_VVAR = "/fonts/kalligraphie-var-vvar/KalligraphieVarVVAR.ttf"
    private const val WORK_SANS = "/fonts/worksans/WorkSans[wght].ttf"
    private const val NOTO_SANS_JP = "/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"

    private val LATIN_LETTERS: List<Int> = (0x41..0x5A).toList()
    private val LATIN: List<Int> = LATIN_LETTERS + (0x61..0x7A) + (0x30..0x39)

    // U+03A2 is unassigned.
    private val GREEK: List<Int> = (0x391..0x3A9).filter { codepoint -> codepoint != 0x3A2 } + (0x3B1..0x3C9)
    private val CYRILLIC: List<Int> = (0x410..0x42F).toList() + (0x430..0x44F).toList()

    // Core Arabic letters only: Persian/Urdu variants (U+063B–U+063F) and tatweel (U+0640) are outside the curated set.
    private val ARABIC: List<Int> = (0x621..0x63A).toList() + (0x641..0x64A).toList()
    private val DEVANAGARI: List<Int> = (0x905..0x939).toList() + (0x966..0x96F).toList()

    // U+1F602 (7 layers) and U+1F604 (10 layers) exceed the shared paint profile maxPaths=6; omitted deliberately.
    private val EMOJI: List<Int> = (0x1F600..0x1F607).filter { codepoint ->
        codepoint != 0x1F602 && codepoint != 0x1F604
    }

    /**
     * Every registered renderer, keyed by catalog entry id.
     *
     * A migrated renderer also declares the scene id it feeds: those scenes were named before the
     * catalog existed and keep their committed manifest keys, which the entry id — a name of the
     * technology, not of the scene — does not reproduce. A scene born with the catalog declares
     * none and takes the entry id.
     */
    val byId: Map<String, CatalogSceneRenderer> = mapOf(
        "outline.glyf-simple-composite" to CatalogSceneRenderer(
            LIBERATION_SANS,
            sceneId = "glyph.outline.liberation-sans.A.64",
            render = ::renderLiberationCapitalA,
        ),
        "color.colr-v0-single-glyph" to CatalogSceneRenderer(
            EMOJI_TWO_COLR_V0,
            sceneId = "glyph.paint.emoji-two-colr-v0.u1F600.64",
            render = ::renderEmojiTwoPaint,
        ),
        "bitmap.ebdt-format1" to CatalogSceneRenderer(
            SKIA_EBDT_FORMAT1,
            sceneId = "glyph.bitmap.skia-ebdt-format1.u1F600.16",
            render = ::renderEbdtFormat1Bitmap,
        ),
        "script.latin.composed-line" to CatalogSceneRenderer(LIBERATION_SANS, sceneId = "line.latin.48") {
            composed { ComposedLineScenes.line("Kalligraphie", "en", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.greek.composed-line" to CatalogSceneRenderer(LIBERATION_SANS, sceneId = "line.greek.48") {
            composed { ComposedLineScenes.line("Καλλιγραφία", "el", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.cyrillic.composed-line" to CatalogSceneRenderer(LIBERATION_SANS, sceneId = "line.cyrillic.48") {
            composed { ComposedLineScenes.line("Каллиграфия", "ru", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.arabic.composed-line" to CatalogSceneRenderer(AMIRI, sceneId = "line.arabic.48") {
            composed { ComposedLineScenes.line("الخط العربي", "ar", BaseDirection.RIGHT_TO_LEFT, requiredFaces = 0) }
        },
        "script.devanagari.composed-line" to CatalogSceneRenderer(NOTO_DEVANAGARI, sceneId = "line.devanagari.48") {
            composed { ComposedLineScenes.line("देवनागरी", "hi", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.mixed.composed-line" to CatalogSceneRenderer(LIBERATION_SANS, sceneId = "line.mixed.48") {
            composed {
                ComposedLineScenes.line(
                    "Kalligraphie — Ελληνικά — Кириллица — العربية — देवनागरी",
                    "en",
                    BaseDirection.LEFT_TO_RIGHT,
                    requiredFaces = 3,
                )
            }
        },
        "script.latin.outline-sheet" to CatalogSceneRenderer(LIBERATION_SANS, sceneId = "sheet.outline.liberation-latin.32") {
            composed { GlyphSheetScenes.outlineSheet(LIBERATION_SANS, LATIN, 32.0) }
        },
        "script.greek.outline-sheet" to CatalogSceneRenderer(LIBERATION_SANS, sceneId = "sheet.outline.liberation-greek.32") {
            composed { GlyphSheetScenes.outlineSheet(LIBERATION_SANS, GREEK, 32.0) }
        },
        "script.cyrillic.outline-sheet" to CatalogSceneRenderer(LIBERATION_SANS, sceneId = "sheet.outline.liberation-cyrillic.32") {
            composed { GlyphSheetScenes.outlineSheet(LIBERATION_SANS, CYRILLIC, 32.0) }
        },
        "script.arabic.outline-sheet" to CatalogSceneRenderer(AMIRI, sceneId = "sheet.outline.amiri-arabic.32") {
            composed { GlyphSheetScenes.outlineSheet(AMIRI, ARABIC, 32.0) }
        },
        "script.devanagari.outline-sheet" to CatalogSceneRenderer(NOTO_DEVANAGARI, sceneId = "sheet.outline.noto-devanagari.32") {
            composed { GlyphSheetScenes.outlineSheet(NOTO_DEVANAGARI, DEVANAGARI, 32.0) }
        },
        "color.colr-v0-alphabet-sheet" to CatalogSceneRenderer(BUNGEE_COLOR, sceneId = "sheet.paint.bungee-color-latin.48") {
            composed { GlyphSheetScenes.paintSheet(BUNGEE_COLOR, LATIN_LETTERS, 48.0, paletteIndex = 0) }
        },
        "color.colr-v0-emoji-sheet" to CatalogSceneRenderer(EMOJI_TWO_COLR_V0, sceneId = "sheet.paint.emoji-two-colr-v0.64") {
            composed { GlyphSheetScenes.paintSheet(EMOJI_TWO_COLR_V0, EMOJI, 64.0, paletteIndex = 0) }
        },
        "bitmap.cbdt-png.u1f600.16" to CatalogSceneRenderer(SKIA_CBDT, render = ::renderCbdtColourStrike),
        "bitmap.sbix-png.u1f600.16" to CatalogSceneRenderer(SKIA_SBIX, render = ::renderSbixColourStrike),
        "outline.cff1-static" to CatalogSceneRenderer(CFF_LIBERATION, render = ::renderCff1CapitalA),
        "outline.cff2-static" to CatalogSceneRenderer(CFF2_LIBERATION, render = ::renderCff2CapitalA),
        "metrics.vvar-advance-height" to CatalogSceneRenderer(KALLIGRAPHIE_VAR_VVAR, render = ::renderVvarCapitalA),
        "variation.wght-ladder" to CatalogSceneRenderer(WORK_SANS) {
            composed { renderWeightLadder() }
        },
        "composition.every-route-mosaic" to CatalogSceneRenderer(
            LIBERATION_SANS,
            additionalFontPaths = listOf(
                AMIRI,
                NOTO_DEVANAGARI,
                NOTO_SANS_JP,
                CFF_LIBERATION,
                CFF2_LIBERATION,
                EMOJI_TWO_COLR_V0,
                SKIA_CBDT,
            ),
        ) {
            composed { CompositionMosaicScene.mosaic() }
        },
    )

    /** The weights of the ladder scene, lightest first: the named instances the family publishes. */
    private val LADDER_WEIGHTS: List<Float> = listOf(100f, 300f, 500f, 700f, 900f)

    /** Renders the capitalised word at every weight of the variable fixture, on one baseline grid. */
    private fun renderWeightLadder(): GoldenImage =
        VariationLadderScene.ladder("Kalligraphie", WORK_SANS, LADDER_WEIGHTS)

    private fun renderCbdtColourStrike(): GoldenRenderOutcome = colourStrike(SKIA_CBDT, "CBLC/CBDT strike")

    private fun renderSbixColourStrike(): GoldenRenderOutcome = colourStrike(SKIA_SBIX, "sbix strike")

    private fun renderCff1CapitalA(): GoldenRenderOutcome = outlineCapitalA(CFF_LIBERATION, "CFF 1 'A'")

    private fun renderCff2CapitalA(): GoldenRenderOutcome = outlineCapitalA(CFF2_LIBERATION, "CFF 2 'A'")

    private fun renderVvarCapitalA(): GoldenRenderOutcome = outlineCapitalA(KALLIGRAPHIE_VAR_VVAR, "VVAR fixture 'A'")

    /** Renders the U+1F600 bitmap of [fontPath], as the 16 ppem RGBA strike it resolves to. */
    private fun colourStrike(fontPath: String, what: String): GoldenRenderOutcome =
        openRenderableFixture(fixtureBytes(fontPath), colourBitmapRequirements()).use { fixture ->
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
    private fun outlineCapitalA(fontPath: String, what: String): GoldenRenderOutcome =
        openOutlineFixture(fixtureBytes(fontPath)).use { fixture ->
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

    private fun renderLiberationCapitalA(): GoldenRenderOutcome =
        openOutlineFixture(fixtureBytes(LIBERATION_SANS)).use { fixture ->
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

    private fun renderEmojiTwoPaint(): GoldenRenderOutcome =
        openRenderableFixture(
            bytes = fixtureBytes(EMOJI_TWO_COLR_V0),
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

    private fun renderEbdtFormat1Bitmap(): GoldenRenderOutcome =
        openRenderableFixture(
            bytes = fixtureBytes(SKIA_EBDT_FORMAT1),
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

    private inline fun composed(render: () -> GoldenImage): GoldenRenderOutcome =
        try {
            GoldenRenderOutcome.Rendered(render())
        } catch (error: Throwable) {
            GoldenRenderOutcome.Refused(
                code = GoldenDiagnosticCode.RENDER_FAILED,
                detail = "composed scene failed: ${error.message}",
            )
        }

    private fun refused(what: String, field: String): GoldenRenderOutcome = GoldenRenderOutcome.Refused(
        code = GoldenDiagnosticCode.RENDER_FAILED,
        detail = "$what refused: $field",
    )
}
