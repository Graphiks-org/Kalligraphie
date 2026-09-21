package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenScene
import org.graphiks.kalligraphie.e2e.GoldenSceneFamily
import org.graphiks.kalligraphie.raster.BitmapRasterRequest
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.OutlineRasterRequest
import org.graphiks.kalligraphie.raster.PaintRasterRequest
import org.graphiks.kalligraphie.raster.RasterResult

/** A catalogued scene paired with the renderer that produces its canonical image. */
internal class JvmGoldenEntry(
    val scene: GoldenScene,
    val render: () -> GoldenRenderOutcome,
)

/**
 * The JVM scene catalog. Phase 1 contributed the outline smoke scene; phase 3
 * absorbs the composition conformance of `raster-cpu` as golden scenes: isolated
 * glyphs (outline, paint, bitmap), composed lines through the paragraph facade,
 * and per-font alphabet sheets.
 */
internal object JvmGoldenSceneCatalog {
    private const val LIBERATION_SANS = "/fonts/liberation/LiberationSans-Regular.ttf"
    private const val AMIRI = "/fonts/amiri/Amiri-Regular.ttf"
    private const val NOTO_DEVANAGARI = "/fonts/noto-devanagari/NotoSansDevanagari-Regular.ttf"
    private const val BUNGEE_COLOR = "/fonts/bungee-color/BungeeColor-Regular.ttf"
    private const val EMOJI_TWO_COLR_V0 = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf"
    private const val SKIA_EBDT_FORMAT1 = "/fonts/skia-ebdt-format1/ebdt_fmt1.ttf"

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

    fun entries(): List<JvmGoldenEntry> = listOf(
        smokeOutlineScene(),
        emojiPaintScene(),
        ebdtBitmapScene(),
    ) + composedLineScenes() + outlineSheetScenes() + paintSheetScenes()

    private fun smokeOutlineScene(): JvmGoldenEntry {
        val scene = GoldenScene(
            id = "glyph.outline.liberation-sans.A.64",
            family = GoldenSceneFamily.GLYPH_OUTLINE,
            width = 43,
            height = 45,
            tags = setOf("smoke", "scripts:latin"),
        )
        return JvmGoldenEntry(scene) {
            openOutlineFixture(fixtureBytes(LIBERATION_SANS)).use { fixture ->
                val outline = fixture.outlineOf(0x41)
                when (val result = GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm = 64.0))) {
                    is RasterResult.Success -> alpha8Outcome(scene, result.value.width, result.value.height, result.value.copyPixels())
                    is RasterResult.Failure -> refused(scene, "Liberation Sans 'A'", result.diagnostics.first().field)
                }
            }
        }
    }

    private fun emojiPaintScene(): JvmGoldenEntry {
        val scene = GoldenScene(
            id = "glyph.paint.emoji-two-colr-v0.u1F600.64",
            family = GoldenSceneFamily.GLYPH_PAINT,
            width = 71,
            height = 72,
            tags = setOf("scripts:emoji"),
        )
        return JvmGoldenEntry(scene) {
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
                        detail = "${scene.id} paint graph has no solid outline",
                    )
                } else {
                    val request = PaintRasterRequest(pixelsPerEm = 64.0, unitsPerEm = solidOutline.outline.unitsPerEm)
                    when (val result = GlyphRasterizer.rasterizePaint(paint, request)) {
                        is RasterResult.Success -> rgba8Outcome(scene, result.value.width, result.value.height, result.value.copyPixels())
                        is RasterResult.Failure -> refused(scene, "emoji COLRv0 paint", result.diagnostics.first().field)
                    }
                }
            }
        }
    }

    private fun ebdtBitmapScene(): JvmGoldenEntry {
        val scene = GoldenScene(
            id = "glyph.bitmap.skia-ebdt-format1.u1F600.16",
            family = GoldenSceneFamily.GLYPH_BITMAP,
            width = 13,
            height = 13,
            tags = setOf("scripts:emoji"),
        )
        return JvmGoldenEntry(scene) {
            openRenderableFixture(
                bytes = fixtureBytes(SKIA_EBDT_FORMAT1),
                requirements = bitmapRequirements(),
            ).use { fixture ->
                val bitmap = fixture.bitmapOf(0x1F600)
                if (bitmap.strike != BitmapStrike(16, 16, 1)) {
                    GoldenRenderOutcome.Refused(
                        code = GoldenDiagnosticCode.RENDER_FAILED,
                        detail = "${scene.id} resolved strike ${bitmap.strike}, expected BitmapStrike(16, 16, 1)",
                    )
                } else {
                    val request = BitmapRasterRequest(GlyphColor(0, 0, 0, 255))
                    when (val result = GlyphRasterizer.rasterizeBitmap(bitmap, request)) {
                        is RasterResult.Success -> rgba8Outcome(scene, result.value.width, result.value.height, result.value.copyPixels())
                        is RasterResult.Failure -> refused(scene, "EBDT format 1 bitmap", result.diagnostics.first().field)
                    }
                }
            }
        }
    }

    private fun composedLineScenes(): List<JvmGoldenEntry> = listOf(
        composedLine(
            id = "line.latin.48",
            width = 250,
            height = 49,
            text = "Kalligraphie",
            language = "en",
            tags = setOf("scripts:latin"),
        ),
        composedLine(
            id = "line.greek.48",
            width = 267,
            height = 51,
            text = "Καλλιγραφία",
            language = "el",
            tags = setOf("scripts:greek"),
        ),
        composedLine(
            id = "line.cyrillic.48",
            width = 299,
            height = 49,
            text = "Каллиграфия",
            language = "ru",
            tags = setOf("scripts:cyrillic"),
        ),
        composedLine(
            id = "line.arabic.48",
            width = 182,
            height = 64,
            text = "الخط العربي",
            language = "ar",
            baseDirection = BaseDirection.RIGHT_TO_LEFT,
            tags = setOf("scripts:arabic", "bidi"),
        ),
        composedLine(
            id = "line.devanagari.48",
            width = 156,
            height = 52,
            text = "देवनागरी",
            language = "hi",
            tags = setOf("scripts:devanagari"),
        ),
        composedLine(
            id = "line.mixed.48",
            width = 1235,
            height = 62,
            text = "Kalligraphie — Ελληνικά — Кириллица — العربية — देवनागरी",
            language = "en",
            requiredFaces = 3,
            tags = setOf("multi-face", "bidi"),
        ),
    )

    private fun outlineSheetScenes(): List<JvmGoldenEntry> = listOf(
        outlineSheet("sheet.outline.liberation-latin.32", LIBERATION_SANS, LATIN, 576, 140, "scripts:latin"),
        outlineSheet("sheet.outline.liberation-greek.32", LIBERATION_SANS, GREEK, 464, 140, "scripts:greek"),
        outlineSheet("sheet.outline.liberation-cyrillic.32", LIBERATION_SANS, CYRILLIC, 560, 160, "scripts:cyrillic"),
        outlineSheet("sheet.outline.amiri-arabic.32", AMIRI, ARABIC, 736, 162, "scripts:arabic"),
        outlineSheet("sheet.outline.noto-devanagari.32", NOTO_DEVANAGARI, DEVANAGARI, 624, 156, "scripts:devanagari"),
    )

    private fun paintSheetScenes(): List<JvmGoldenEntry> = listOf(
        paintSheet("sheet.paint.bungee-color-latin.48", BUNGEE_COLOR, LATIN_LETTERS, 48.0, 656, 90, "scripts:latin"),
        paintSheet("sheet.paint.emoji-two-colr-v0.64", EMOJI_TWO_COLR_V0, EMOJI, 64.0, 1200, 76, "scripts:emoji"),
    )

    private fun composedLine(
        id: String,
        width: Int,
        height: Int,
        text: String,
        language: String,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        requiredFaces: Int = 0,
        tags: Set<String>,
    ): JvmGoldenEntry {
        val scene = GoldenScene(id, GoldenSceneFamily.COMPOSED_LINE, width, height, tags)
        return JvmGoldenEntry(scene) {
            composed(scene) { ComposedLineScenes.line(text, language, baseDirection, requiredFaces) }
        }
    }

    private fun outlineSheet(
        id: String,
        fontPath: String,
        codepoints: List<Int>,
        width: Int,
        height: Int,
        tag: String,
    ): JvmGoldenEntry {
        val scene = GoldenScene(id, GoldenSceneFamily.ALPHABET_SHEET, width, height, setOf(tag))
        return JvmGoldenEntry(scene) {
            composed(scene) { GlyphSheetScenes.outlineSheet(fontPath, codepoints, 32.0) }
        }
    }

    private fun paintSheet(
        id: String,
        fontPath: String,
        codepoints: List<Int>,
        pixelsPerEm: Double,
        width: Int,
        height: Int,
        tag: String,
    ): JvmGoldenEntry {
        val scene = GoldenScene(id, GoldenSceneFamily.ALPHABET_SHEET, width, height, setOf(tag))
        return JvmGoldenEntry(scene) {
            composed(scene) { GlyphSheetScenes.paintSheet(fontPath, codepoints, pixelsPerEm, paletteIndex = 0) }
        }
    }

    private inline fun composed(scene: GoldenScene, render: () -> GoldenImage): GoldenRenderOutcome =
        try {
            val image = render()
            if (image.width != scene.width || image.height != scene.height) {
                GoldenRenderOutcome.Refused(
                    code = GoldenDiagnosticCode.SCENE_BOUNDS_INVALID,
                    detail = "${scene.id} rendered ${image.width}x${image.height}, expected ${scene.width}x${scene.height}",
                )
            } else {
                GoldenRenderOutcome.Rendered(image)
            }
        } catch (error: Throwable) {
            GoldenRenderOutcome.Refused(
                code = GoldenDiagnosticCode.RENDER_FAILED,
                detail = "${scene.id}: ${error.message}",
            )
        }

    private fun alpha8Outcome(scene: GoldenScene, width: Int, height: Int, pixels: ByteArray): GoldenRenderOutcome =
        if (width != scene.width || height != scene.height) {
            GoldenRenderOutcome.Refused(
                code = GoldenDiagnosticCode.SCENE_BOUNDS_INVALID,
                detail = "${scene.id} rendered ${width}x${height}, expected ${scene.width}x${scene.height}",
            )
        } else {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(width, height, pixels))
        }

    private fun rgba8Outcome(scene: GoldenScene, width: Int, height: Int, pixels: ByteArray): GoldenRenderOutcome =
        if (width != scene.width || height != scene.height) {
            GoldenRenderOutcome.Refused(
                code = GoldenDiagnosticCode.SCENE_BOUNDS_INVALID,
                detail = "${scene.id} rendered ${width}x${height}, expected ${scene.width}x${scene.height}",
            )
        } else {
            GoldenRenderOutcome.Rendered(GoldenImage.rgba8(width, height, pixels))
        }

    private fun refused(scene: GoldenScene, what: String, field: String): GoldenRenderOutcome = GoldenRenderOutcome.Refused(
        code = GoldenDiagnosticCode.RENDER_FAILED,
        detail = "${scene.id} $what refused: $field",
    )
}
