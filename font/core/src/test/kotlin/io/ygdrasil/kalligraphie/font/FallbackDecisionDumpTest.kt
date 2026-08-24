package io.ygdrasil.kalligraphie.font

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallbackDecisionDumpTest {
    @Test
    fun `fallback decision trace dump matches repo golden`() {
        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/fallback/fallback-decision-trace.json"))
        val actual = defaultFallbackEvidenceBundle().fallbackDecisionTraceJson

        assertEquals(expected.trimEnd(), actual.trimEnd())
        assertContains(actual, """"dumpId": "fallback-decision-trace"""")
        assertContains(actual, """"fixtureId":"fallback-script-arabic"""")
        assertContains(actual, """"fixtureId":"fallback-locale-serbian"""")
        assertContains(actual, """"fixtureId":"fallback-emoji-preference"""")
    }

    @Test
    fun `resolved font runs dump matches repo golden`() {
        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/fallback/resolved-font-runs.json"))
        val actual = defaultFallbackEvidenceBundle().resolvedFontRunsJson

        assertEquals(expected.trimEnd(), actual.trimEnd())
        assertContains(actual, """"dumpId": "resolved-font-runs"""")
        assertContains(actual, """"fixtureId":"fallback-missing-glyph"""")
        assertContains(actual, """"fixtureId":"fallback-family-unavailable"""")
    }

    @Test
    fun `fallback dump exposes script locale emoji reasons and stable refusal diagnostics`() {
        val bundle = defaultFallbackEvidenceBundle()

        assertContains(bundle.fallbackDecisionTraceJson, """"reason":"script-fallback"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"reason":"locale-hint"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"reason":"emoji-preference"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"diagnosticCode":"font.fallback-glyph-unavailable"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"diagnosticCode":"font.fallback-family-unavailable"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"text.shaping.script-unsupported"""")
        assertContains(bundle.resolvedFontRunsJson, """"diagnosticCode":"text.shaping.fallback-missing"""")
        assertFalse(bundle.fallbackDecisionTraceJson.contains("HarfBuzz", ignoreCase = true))
        assertFalse(bundle.fallbackDecisionTraceJson.contains("FreeType", ignoreCase = true))
        assertFalse(bundle.resolvedFontRunsJson.contains("SkTypeface"))
        assertTrue(bundle.fallbackDecisionTraceJson.indexOf("fallback-family-generic") < bundle.fallbackDecisionTraceJson.indexOf("fallback-script-arabic"))
    }

    @Test
    fun `complete miss evidence exposes cluster ranges and shaping fallback diagnostics`() {
        val bundle = defaultFallbackEvidenceBundle()

        assertContains(
            bundle.fallbackDecisionTraceJson,
            """"fixtureId":"fallback-family-unavailable","request":{"text":"ა","locale":null,"preferredFamilies":["Missing Sans"],"style":{"weight":400,"width":5,"slant":"upright"}},"decisions":[{"textRange":"0..0","clusterRange":"0..0"""",
        )
        assertContains(
            bundle.resolvedFontRunsJson,
            """"fixtureId":"fallback-family-unavailable","request":{"text":"ა","locale":null,"preferredFamilies":["Missing Sans"],"style":{"weight":400,"width":5,"slant":"upright"}},"runs":[],"diagnosticRanges":[{"textRange":"0..0","clusterRange":"0..0","diagnosticCode":"font.fallback-family-unavailable"},{"textRange":"0..0","clusterRange":"0..0","diagnosticCode":"text.shaping.fallback-missing"}]""",
        )
    }

    @Test
    fun `per fixture fallback assets match repo goldens`() {
        val bundle = defaultFallbackEvidenceBundle()

        fallbackFixtureIds().forEach { fixtureId ->
            val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/fallback/$fixtureId.json"))
            val actual = bundle.fixtureJsonById.getValue(fixtureId)

            assertEquals(expected.trimEnd(), actual.trimEnd())
            assertContains(actual, """"dumpId":"fallback-fixture"""")
            assertContains(actual, """"fixtureId":"$fixtureId"""")
        }
    }

    @Test
    fun `cluster fixture fallback assets match repo goldens`() {
        val bundle = defaultFallbackClusterEvidenceBundle()

        fallbackClusterFixtureIds().forEach { fixtureId ->
            val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/fallback/$fixtureId.json"))
            val actual = bundle.fixtureJsonById.getValue(fixtureId)

            assertEquals(expected.trimEnd(), actual.trimEnd())
            assertContains(actual, """"dumpId":"fallback-fixture"""")
            assertContains(actual, """"fixtureId":"$fixtureId"""")
        }
    }

    @Test
    fun `variable fallback evidence records axis support clamping and selected coordinates`() {
        val bundle = defaultFallbackEvidenceBundle()

        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-axis-clamped"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-axis-missing"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-metrics-variation-missing"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-named-instance"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-multi-axis"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-variable-cff2"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"requestedVariationCoordinates":[{"axisTag":"wght","value":900.0}]""")
        assertContains(bundle.fallbackDecisionTraceJson, """"selectedVariationCoordinates":[{"axisTag":"wght","value":700.0}]""")
        assertContains(bundle.fallbackDecisionTraceJson, """"selectedNamedInstance":"Condensed Bold"""")
        assertContains(
            bundle.fallbackDecisionTraceJson,
            """"requestedVariationCoordinates":[{"axisTag":"wdth","value":80.0},{"axisTag":"wght","value":700.0}]""",
        )
        assertContains(
            bundle.fallbackDecisionTraceJson,
            """"selectedVariationCoordinates":[{"axisTag":"wdth","value":80.0},{"axisTag":"wght","value":700.0}]""",
        )
        assertContains(bundle.fallbackDecisionTraceJson, """"diagnosticCode":"font.fallback.axis-clamped"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"diagnosticCode":"font.variation-axis-unsupported"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"diagnosticCode":"font.metrics-variation-unavailable"""")
        assertContains(bundle.resolvedFontRunsJson, """"variationCoordinates":[{"axisTag":"wght","value":700.0}]""")
        assertContains(bundle.resolvedFontRunsJson, """"variationCoordinates":[{"axisTag":"wght","value":650.0}]""")
    }

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (current.parent != null && !Files.isDirectory(current.resolve("font/fixtures"))) {
            current = current.parent
        }
        return current
    }

    private fun fallbackFixtureIds(): List<String> = listOf(
        "fallback-axis-clamped",
        "fallback-axis-missing",
        "fallback-emoji-preference",
        "fallback-family-generic",
        "fallback-family-unavailable",
        "fallback-locale-serbian",
        "fallback-metrics-variation-missing",
        "fallback-missing-glyph",
        "fallback-multi-axis",
        "fallback-named-instance",
        "fallback-script-arabic",
        "fallback-variable-cff2",
    )

    private fun fallbackClusterFixtureIds(): List<String> = listOf(
        "fallback-cluster-arabic-mark",
        "fallback-cluster-cjk-vs",
        "fallback-cluster-devanagari",
        "fallback-cluster-emoji-zwj",
        "fallback-cluster-latin-mark",
        "fallback-cluster-negative-split",
        "fallback-cluster-skin-tone",
        "fallback-cluster-thai",
        "fallback-cluster-vs15-vs16",
    )
}
