package io.ygdrasil.kalligraphie.text

import io.ygdrasil.kalligraphie.text.shaping.defaultSystemFontScanEvidenceBundle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemFontScanTest {
    @Test
    fun `system font scan evidence bundle matches repo goldens`() {
        val bundle = defaultSystemFontScanEvidenceBundle()
        val root = projectRoot()

        assertEquals(
            Files.readString(root.resolve("font/fixtures/expected/fallback/system-font-scan.json")).trimEnd(),
            bundle.systemFontScanJson.trimEnd(),
        )
        assertEquals(
            Files.readString(root.resolve("font/fixtures/expected/fallback/system-font-scan-font-catalog-link.json")).trimEnd(),
            bundle.fontCatalogLinkJson.trimEnd(),
        )
        assertEquals(
            Files.readString(root.resolve("font/fixtures/expected/fallback/system-font-scan-fallback-trace.json")).trimEnd(),
            bundle.fallbackTraceJson.trimEnd(),
        )
    }

    @Test
    fun `system font scan evidence stays host dependent and deterministic`() {
        val bundle = defaultSystemFontScanEvidenceBundle()

        assertContains(bundle.systemFontScanJson, """"dumpId": "system-font-scan"""")
        assertContains(bundle.systemFontScanJson, """"hostDependent": true""")
        assertContains(bundle.systemFontScanJson, """"diagnosticCode":"font.source.host-dependent"""")
        assertContains(bundle.systemFontScanJson, """"diagnosticCode":"font.source.unreadable"""")
        assertContains(bundle.systemFontScanJson, """"diagnosticCode":"font.outline-format.unsupported-wrapper"""")
        assertContains(bundle.systemFontScanJson, """"diagnosticCode":"font.catalog.duplicate-face"""")
        assertContains(bundle.systemFontScanJson, """"diagnosticCode":"font.required-table-missing"""")
        assertContains(bundle.fontCatalogLinkJson, """"linkedSystemScans"""")
        assertContains(bundle.fallbackTraceJson, """"selectedSourceKind":"SystemScannedFontSource"""")
        assertContains(bundle.fallbackTraceJson, """"selectedHostDependent":true""")
        assertFalse(bundle.systemFontScanJson.contains("HarfBuzz", ignoreCase = true))
        assertFalse(bundle.systemFontScanJson.contains("FreeType", ignoreCase = true))
        assertTrue(bundle.systemFontScanJson.indexOf("duplicate.ttf") < bundle.systemFontScanJson.indexOf("valid.ttf"))
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
