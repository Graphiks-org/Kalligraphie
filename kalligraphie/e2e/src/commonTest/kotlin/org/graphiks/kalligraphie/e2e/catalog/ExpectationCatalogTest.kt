// ExpectationCatalogTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpectationCatalogTest {
    @Test
    fun theCatalogPassesItsOwnAudit() {
        val violations = CatalogAuditor.audit(ExpectationCatalog.entries)
        assertTrue(violations.isEmpty(), violations.joinToString("\n") { violation -> "${violation.rule}: ${violation.detail}" })
    }

    @Test
    fun everyAxisIsDeclaredExactlyOncePerEntry() {
        val regrouped = ExpectationCatalog.byAxis.values.flatten()
        assertEquals(ExpectationCatalog.entries.size, regrouped.size)
    }

    @Test
    fun theMigratedGoldenScenesAreAllDeclared() {
        val expected = setOf(
            "glyph.outline.liberation-sans.A.64",
            "glyph.paint.emoji-two-colr-v0.u1F600.64",
            "glyph.bitmap.skia-ebdt-format1.u1F600.16",
            "line.latin.48", "line.greek.48", "line.cyrillic.48",
            "line.arabic.48", "line.devanagari.48", "line.mixed.48",
            "sheet.outline.liberation-latin.32", "sheet.outline.liberation-greek.32",
            "sheet.outline.liberation-cyrillic.32", "sheet.outline.amiri-arabic.32",
            "sheet.outline.noto-devanagari.32",
            "sheet.paint.bungee-color-latin.48", "sheet.paint.emoji-two-colr-v0.64",
        )
        val declared = ExpectationCatalog.entries
            .filter { entry -> entry.status is CatalogStatus.Supported }
            .mapNotNull { entry -> (entry.frame as? SceneFramePolicy.Pinned)?.let { entry.id } }
            .toSet()
        assertEquals(16, declared.size, "the sixteen migrated scenes must all be declared: $declared")
        assertEquals(expected.size, declared.size)
    }
}
