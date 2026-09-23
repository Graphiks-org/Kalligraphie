package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenOrientation
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.PixelFormat
import org.graphiks.kalligraphie.e2e.golden.GoldenDumpWriter
import org.graphiks.kalligraphie.e2e.fixture.E2eTestEnvironment
import org.graphiks.kalligraphie.e2e.golden.GoldenSceneCatalog

/**
 * Pins the orientation contract the dumps rest on.
 *
 * The canonical bytes do not say which end is up; the producer declares it, the dump writer acts on
 * it, and these tests are what stop a future scene from declaring the wrong end or a frame from
 * dropping the declaration on the way.
 */
class SceneOrientationTest {
    /**
     * Scenes that hand the rasterizer's own rows to the canonical model, so they declare
     * [GoldenOrientation.DESIGN]: the two CFF capitals, the TrueType one, the VVAR metrics one and
     * the COLR v0 paint. Every other scene goes through a composition canvas or a normalized bitmap
     * strike and is already in [GoldenOrientation.IMAGE].
     */
    private val designOrientedScenes = setOf(
        "glyph.outline.liberation-sans.A.64",
        "glyph.paint.emoji-two-colr-v0.u1F600.64",
        "outline.cff1-static",
        "outline.cff2-static",
        "metrics.vvar-advance-height",
    )

    @Test
    fun everySceneDeclaresTheOrientationOfItsProducer() {
        val misplaced = LinkedHashMap<String, String>()
        for ((entryId, renderer) in E2eTestEnvironment.renderers) {
            val sceneId = renderer.sceneId ?: entryId
            val expected = if (sceneId in designOrientedScenes) GoldenOrientation.DESIGN else GoldenOrientation.IMAGE
            val actual = rendered(renderer.render(E2eTestEnvironment.corpus), sceneId).orientation
            if (actual != expected) misplaced[sceneId] = "$actual, expected $expected"
        }
        assertTrue(misplaced.isEmpty(), "scenes declare the wrong orientation: $misplaced")
    }

    @Test
    fun anAutoSizedSceneKeepsItsOrientationThroughTheFrame() {
        // metrics.vvar-advance-height is both design-oriented and auto-sized, so a frame that
        // silently rebuilt its content would lose the declaration exactly here.
        val image = renderedScene("metrics.vvar-advance-height")
        assertEquals(GoldenOrientation.DESIGN, image.orientation)
    }

    @Test
    fun theDumpedCapitalAReadsUpright() {
        val image = renderedScene("outline.cff1-static")
        val rows = rasterInkRows(GoldenDumpWriter.encode(image), image)
        val apex = rows.take(3).sum()
        val legs = rows.takeLast(3).sum()
        assertTrue(apex < legs, "the dumped A is upside down: apex ink $apex, leg ink $legs, rows=$rows")
        val widest = rows.indices.maxBy { row -> rows[row] }
        assertTrue(widest >= rows.size / 2, "the widest row of the A is row $widest of ${rows.size}, above the middle")
    }

    private fun renderedScene(sceneId: String): GoldenImage =
        rendered(
            GoldenSceneCatalog.entries().single { entry -> entry.scene.id == sceneId }.render(),
            sceneId,
        )

    private fun rendered(outcome: GoldenRenderOutcome, sceneId: String): GoldenImage = when (outcome) {
        is GoldenRenderOutcome.Rendered -> outcome.image
        is GoldenRenderOutcome.Refused -> error("$sceneId refused to render: ${outcome.code.code} (${outcome.detail})")
    }

    /**
     * Counts the ink of every raster row of [dump], the way an image viewer reads it: row zero is
     * the first row of the file, whichever orientation the producer declared.
     */
    private fun rasterInkRows(dump: ByteArray, image: GoldenImage): List<Int> {
        val header = "P${if (image.format == PixelFormat.ALPHA_8) 5 else 6}\n${image.width} ${image.height}\n255\n"
        assertEquals(header, dump.copyOfRange(0, header.length).decodeToString())
        val raster = dump.copyOfRange(header.length, dump.size)
        val channels = if (image.format == PixelFormat.ALPHA_8) 1 else 3
        return (0 until image.height).map { row ->
            (0 until image.width).count { column ->
                val base = (row * image.width + column) * channels
                if (channels == 1) {
                    (raster[base].toInt() and 0xFF) > 8
                } else {
                    (0 until 3).any { channel -> (raster[base + channel].toInt() and 0xFF) < 200 }
                }
            }
        }
    }
}
