package org.graphiks.kalligraphie.e2e.golden

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.sha256Hex

class JvmGoldenSceneCatalogTest {
    @Test
    fun smokeOutlineSceneRendersTheLiberationSansCapitalA() {
        val entry = JvmGoldenSceneCatalog.entries().single { candidate -> candidate.scene.id == "glyph.outline.liberation-sans.A.64" }
        val rendered = assertIs<GoldenRenderOutcome.Rendered>(entry.render())
        assertEquals(43, rendered.image.width)
        assertEquals(45, rendered.image.height)
        assertEquals(EXPECTED_A_SHA256, sha256Hex(rendered.image.copyCanonicalBytes()))
    }

    @Test
    fun catalogIdsAreUnique() {
        val ids = JvmGoldenSceneCatalog.entries().map { entry -> entry.scene.id }
        assertEquals(ids.distinct().size, ids.size)
    }

    private companion object {
        const val EXPECTED_A_SHA256 = "bade575a06ee0217858ff2b2eb9850f0c949323ca47a307666f99495fdbf2ca3"
    }
}
