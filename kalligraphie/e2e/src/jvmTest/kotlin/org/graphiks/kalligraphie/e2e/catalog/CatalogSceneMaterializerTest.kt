package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

class CatalogSceneMaterializerTest {
    @Test
    fun anAutoSizedSceneFramesItsInkBoxWithPadding() {
        val entry = autoSizedEntry(padding = 2)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf") {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(4, 3, inkAt(x = 1, y = 1, width = 4, height = 3)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer)

        assertEquals(6, materialized.scene.width, "ink box is 2x2, padding 2 on each side")
        assertEquals(6, materialized.scene.height)
        val framed = assertIs<GoldenRenderOutcome.Rendered>(materialized.render()).image
        assertEquals(6, framed.width)
        assertEquals(6, framed.height)
    }

    @Test
    fun aPinnedSceneRefusesAFrameThatNoLongerMatches() {
        val entry = pinnedEntry(width = 10, height = 10)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf") {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(9, 10, ByteArray(90)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer)

        val refused = assertIs<GoldenRenderOutcome.Refused>(materialized.render())
        assertEquals(GoldenDiagnosticCode.SCENE_BOUNDS_INVALID, refused.code)
    }

    @Test
    fun aPinnedSceneKeepsItsDeclaredFrameWhenTheRenderAgrees() {
        val entry = pinnedEntry(width = 10, height = 10)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf") {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(10, 10, ByteArray(100)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer)

        assertEquals(10, materialized.scene.width)
        assertEquals(GoldenSceneFamily.GLYPH_OUTLINE, materialized.scene.family)
    }

    @Test
    fun anAutoSizedSceneWithNoInkIsRefused() {
        val entry = autoSizedEntry(padding = 1)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf") {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(8, 8, ByteArray(64)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer)

        val refused = assertIs<GoldenRenderOutcome.Refused>(materialized.render())
        assertEquals(GoldenDiagnosticCode.BLANK_SCENE, refused.code)
    }

    @Test
    fun theRendererFontPathMustBelongToTheEntryCorpusKey() {
        val entry = autoSizedEntry(padding = 1)
        val renderer = CatalogSceneRenderer("/fonts/amiri/Amiri-Regular.ttf") {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(2, 2, byteArrayOf(0, 0, 0, 1)))
        }

        val failure = CatalogSceneMaterializer.fontPathMismatch(entry, renderer)
        assertEquals(
            "entry outline.glyf declares corpus key liberation but renders /fonts/amiri/Amiri-Regular.ttf",
            failure,
        )
    }

    @Test
    fun aRendererSceneIdOverridesTheEntryIdAndDefaultsToIt() {
        val entry = autoSizedEntry(padding = 1)
        val render = {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(2, 2, byteArrayOf(0, 0, 0, 1)))
        }
        val inheriting = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf", render = render)
        val overriding = CatalogSceneRenderer(
            "/fonts/liberation/LiberationSans-Regular.ttf",
            sceneId = "glyph.outline.liberation-sans.A.64",
            render = render,
        )

        assertEquals("outline.glyf", CatalogSceneMaterializer.materialize(entry, inheriting).scene.id)
        assertEquals(
            "glyph.outline.liberation-sans.A.64",
            CatalogSceneMaterializer.materialize(entry, overriding).scene.id,
        )
    }

    private fun autoSizedEntry(padding: Int) = CatalogEntry(
        id = "outline.glyf",
        axis = CatalogAxis.OUTLINE,
        technology = "glyf outlines",
        font = CorpusKey("liberation"),
        status = CatalogStatus.Supported("abc1234"),
        family = GoldenSceneFamily.GLYPH_OUTLINE,
        frame = SceneFramePolicy.AutoSized(padding = padding),
    )

    private fun pinnedEntry(width: Int, height: Int) = CatalogEntry(
        id = "outline.glyf",
        axis = CatalogAxis.OUTLINE,
        technology = "glyf outlines",
        font = CorpusKey("liberation"),
        status = CatalogStatus.Supported("abc1234"),
        family = GoldenSceneFamily.GLYPH_OUTLINE,
        frame = SceneFramePolicy.Pinned(width = width, height = height),
    )

    private fun inkAt(x: Int, y: Int, width: Int, height: Int): ByteArray {
        val pixels = ByteArray(width * height)
        pixels[y * width + x] = 0xFF.toByte()
        pixels[(y + 1) * width + x + 1] = 0xFF.toByte()
        return pixels
    }
}
