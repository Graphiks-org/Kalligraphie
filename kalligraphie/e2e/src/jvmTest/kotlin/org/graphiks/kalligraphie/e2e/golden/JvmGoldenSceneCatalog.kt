package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenScene
import org.graphiks.kalligraphie.e2e.GoldenSceneFamily
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.BitmapRasterRequest
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
 * absorbs the composition conformance of `raster-cpu` (outline, paint, bitmap)
 * as golden scenes. Composed lines and alphabet sheets follow.
 */
internal object JvmGoldenSceneCatalog {
    private const val LIBERATION_SANS = "/fonts/liberation/LiberationSans-Regular.ttf"
    private const val EMOJI_TWO_COLR_V0 = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf"
    private const val SKIA_EBDT_FORMAT1 = "/fonts/skia-ebdt-format1/ebdt_fmt1.ttf"

    fun entries(): List<JvmGoldenEntry> = listOf(
        smokeOutlineScene(),
        emojiPaintScene(),
        ebdtBitmapScene(),
    )

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
