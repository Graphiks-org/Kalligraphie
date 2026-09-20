package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenScene
import org.graphiks.kalligraphie.e2e.GoldenSceneFamily
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.OutlineRasterRequest
import org.graphiks.kalligraphie.raster.RasterResult
import kotlin.test.assertIs

/** A catalogued scene paired with the renderer that produces its canonical image. */
internal class JvmGoldenEntry(
    val scene: GoldenScene,
    val render: () -> GoldenRenderOutcome,
)

/** The JVM scene catalog. One smoke scene in phase 1; composed families arrive in phase 3. */
internal object JvmGoldenSceneCatalog {
    private const val LIBERATION_SANS = "/fonts/liberation/LiberationSans-Regular.ttf"

    fun entries(): List<JvmGoldenEntry> = listOf(smokeOutlineScene())

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
                    is RasterResult.Success -> {
                        val image = result.value
                        if (image.width != scene.width || image.height != scene.height) {
                            GoldenRenderOutcome.Refused(
                                code = GoldenDiagnosticCode.SCENE_BOUNDS_INVALID,
                                detail = "Liberation Sans 'A' rendered ${image.width}x${image.height}, " +
                                    "expected ${scene.width}x${scene.height}",
                            )
                        } else {
                            GoldenRenderOutcome.Rendered(
                                GoldenImage.alpha8(image.width, image.height, image.copyPixels()),
                            )
                        }
                    }

                    is RasterResult.Failure -> GoldenRenderOutcome.Refused(
                        code = GoldenDiagnosticCode.RENDER_FAILED,
                        detail = "Liberation Sans 'A' refused: ${result.diagnostics.first().field}",
                    )
                }
            }
        }
    }
}
