package org.graphiks.kalligraphie.e2e.golden

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

class JvmGoldenSceneCatalogTest {
    @Test
    fun smokeOutlineSceneRendersTheLiberationSansCapitalA() {
        val entry = JvmGoldenSceneCatalog.entries().single { candidate -> candidate.scene.id == "glyph.outline.liberation-sans.A.64" }
        val rendered = assertIs<GoldenRenderOutcome.Rendered>(entry.render())
        assertEquals(43, rendered.image.width)
        assertEquals(45, rendered.image.height)
    }

    @Test
    fun catalogIdsAreUnique() {
        val ids = JvmGoldenSceneCatalog.entries().map { entry -> entry.scene.id }
        assertEquals(ids.distinct().size, ids.size)
    }
}
