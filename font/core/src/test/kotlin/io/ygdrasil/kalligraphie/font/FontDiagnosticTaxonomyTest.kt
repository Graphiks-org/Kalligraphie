package io.ygdrasil.kalligraphie.font

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FontDiagnosticTaxonomyTest {
    @Test
    fun `accepted taxonomy codes stay inside pure Kotlin text diagnostic namespaces`() {
        val taxonomy = defaultFontDiagnosticTaxonomy()

        assertEquals(
            listOf(
                "font.catalog",
                "font.source",
                "font.sfnt",
                "font.scaler",
                "text.shaping",
                "text.paragraph",
                "text.glyph",
                "glyph.artifact",
                "text.gpu",
                "unsupported.text",
            ),
            taxonomy.acceptedNamespaces,
        )
        assertTrue(taxonomy.codes.isNotEmpty())
        taxonomy.codes.forEach { code ->
            assertContains(taxonomy.acceptedNamespaces, code.namespace)
            assertTrue(code.code.startsWith("${code.namespace}."))
            assertContains(code.requiredFields, "subject")
            assertContains(code.requiredFields, "route")
            assertContains(code.requiredFields, "severity")
            assertContains(code.requiredFields, "claimImpact")
            assertFalse(code.claimPromotionAllowed)
        }
    }

    @Test
    fun `required fields classify source sfnt scaler shaping paragraph and gpu route diagnostics`() {
        val taxonomy = defaultFontDiagnosticTaxonomy()

        assertRequiredFields(
            taxonomy.code("font.catalog.duplicate-face"),
            "fixtureId",
            "familyName",
            "styleName",
        )
        assertRequiredFields(
            taxonomy.code("font.catalog.provenance-missing"),
            "fixtureId",
            "familyName",
            "styleName",
        )
        assertRequiredFields(
            taxonomy.code("font.source.bytes-unavailable"),
            "sourceId",
            "sourceKind",
        )
        assertRequiredFields(
            taxonomy.code("font.sfnt.required-table-missing"),
            "sourceId",
            "tableTag",
        )
        assertRequiredFields(
            taxonomy.code("font.sfnt.optional-table-malformed"),
            "sourceId",
            "tableTag",
        )
        assertRequiredFields(
            taxonomy.code("font.sfnt.cmap-format-unsupported"),
            "sourceId",
            "format",
            "platformId",
            "encodingId",
        )
        assertRequiredFields(
            taxonomy.code("font.sfnt.cmap-unusable"),
            "sourceId",
        )
        assertRequiredFields(
            taxonomy.code("font.sfnt.table-out-of-bounds"),
            "sourceId",
            "tableTag",
            "offset",
            "length",
            "sourceLength",
        )
        assertRequiredFields(
            taxonomy.code("font.sfnt.table-duplicate"),
            "sourceId",
            "tableTag",
            "offset",
            "length",
            "sourceLength",
        )
        assertRequiredFields(
            taxonomy.code("font.sfnt.table-overlap"),
            "sourceId",
            "tableTag",
            "offset",
            "length",
            "sourceLength",
        )
        assertRequiredFields(
            taxonomy.code("font.scaler.outline-unavailable"),
            "sourceId",
            "typefaceId",
            "glyphId",
        )
        assertRequiredFields(
            taxonomy.code("text.shaping.emoji-sequence-unsupported"),
            "textRange",
            "script",
        )
        assertRequiredFields(
            taxonomy.code("text.paragraph.line-breaker-dependency-gated"),
            "textRange",
            "paragraphRoute",
        )
        assertRequiredFields(
            taxonomy.code("text.glyph.cache-key-nondeterministic"),
            "glyphId",
            "attemptedRoute",
            "reason",
        )
        assertRequiredFields(
            taxonomy.code("text.glyph.LCD-future-research"),
            "glyphId",
            "attemptedRoute",
            "fallbackRoute",
        )
        assertRequiredFields(
            taxonomy.code("text.gpu.artifact-unregistered"),
            "artifactId",
            "generation",
        )
        assertRequiredFields(
            taxonomy.code("unsupported.text.artifact_unregistered"),
            "rendererRoute",
            "artifactId",
        )
    }

    @Test
    fun `legacy diagnostics map to target classifications without closing gates`() {
        val taxonomy = defaultFontDiagnosticTaxonomy()

        assertLegacyMapping(
            mapping = taxonomy.legacyMapping("font.native-engine-unavailable"),
            targetCode = "font.source.native-engine-request-unsupported",
            classification = FontDiagnosticClaimImpact.EXPECTED_UNSUPPORTED,
        )
        assertLegacyMapping(
            mapping = taxonomy.legacyMapping("font.bitmap-strike-unavailable"),
            targetCode = "glyph.artifact.bitmap-strike-unavailable",
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
        )
        assertLegacyMapping(
            mapping = taxonomy.legacyMapping("font.emoji-sequence-shaping-unsupported"),
            targetCode = "text.shaping.emoji-sequence-unsupported",
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
        )
    }

    @Test
    fun `sample diagnostics carry subject source route severity impact and no claim promotion`() {
        val samples = defaultFontDiagnosticTaxonomy().sampleDiagnostics.associateBy { sample -> sample.label }

        listOf(
            "source-failure",
            "sfnt-failure",
            "scaler-failure",
            "shaping-refusal",
            "glyph-strike-key-refusal",
            "gpu-text-route-refusal",
        ).forEach { label ->
            val sample = assertNotNull(samples[label], "Missing sample diagnostic $label")
            assertTrue(sample.subject.isNotBlank())
            assertTrue(sample.route.isNotBlank())
            assertFalse(sample.claimPromotionAllowed)
            assertContains(sample.fields.keys, "severity")
            assertContains(sample.fields.keys, "claimImpact")
            assertContains(sample.fields.keys, "route")
            assertContains(sample.fields.keys, "subject")
        }

        assertEquals("font.source.bytes-unavailable", samples.getValue("source-failure").code)
        assertEquals("font.sfnt.required-table-missing", samples.getValue("sfnt-failure").code)
        assertEquals("font.sfnt.cmap-format-unsupported", samples.getValue("sfnt-cmap-refusal").code)
        assertEquals("font.scaler.outline-unavailable", samples.getValue("scaler-failure").code)
        assertEquals("text.glyph.cache-key-nondeterministic", samples.getValue("glyph-strike-key-refusal").code)
        assertEquals("text.shaping.emoji-sequence-unsupported", samples.getValue("shaping-refusal").code)
        assertEquals("unsupported.text.artifact_unregistered", samples.getValue("gpu-text-route-refusal").code)
        assertEquals("tracked-gap", samples.getValue("source-failure").classification.serializedName)
    }

    @Test
    fun `generic font missing label is rejected and classified as tracked gap`() {
        val classification = defaultFontDiagnosticTaxonomy().classify("font missing")

        assertFalse(classification.accepted)
        assertEquals("font missing", classification.inputCode)
        assertEquals(null, classification.targetCode)
        assertEquals(FontDiagnosticClaimImpact.TRACKED_GAP, classification.classification)
        assertEquals("generic-or-unknown-diagnostic", classification.reason)
        assertFalse(classification.claimPromotionAllowed)
    }

    @Test
    fun `checked in diagnostic taxonomy json matches deterministic writer output`() {
        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/pure-kotlin-text/font-diagnostic-taxonomy.json"))
        val actual = FontDiagnosticTaxonomyWriter.writeTaxonomyJson().value

        assertEquals(expected, actual)
        assertContains(actual, """"classification":"tracked-gap"""")
        assertContains(actual, """"claimPromotionAllowed":false""")
        assertContains(actual, """"inputCode":"font missing"""")
        assertContains(actual, """"reason":"generic-or-unknown-diagnostic"""")
        assertFalse(actual.contains(""""claimPromotionAllowed":true"""))
        assertFalse(actual.contains("target-supported"))
        assertFalse(actual.contains("current-supported"))
    }

    private fun assertRequiredFields(
        code: FontDiagnosticCode,
        vararg fields: String,
    ) {
        fields.forEach { field ->
            assertContains(code.requiredFields, field)
        }
        assertFalse(code.claimPromotionAllowed)
    }

    private fun assertLegacyMapping(
        mapping: LegacyFontDiagnosticMapping,
        targetCode: String,
        classification: FontDiagnosticClaimImpact,
    ) {
        assertEquals(targetCode, mapping.targetCode)
        assertEquals(classification, mapping.classification)
        assertEquals("open", mapping.gateStatus)
        assertFalse(mapping.claimPromotionAllowed)
    }

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (current.parent != null && !Files.isDirectory(current.resolve("font/fixtures"))) {
            current = current.parent
        }
        return current
    }
}
