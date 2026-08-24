package io.ygdrasil.kalligraphie.font

import kotlin.test.Test
import kotlin.test.assertContains

class VariableFallbackEvidenceTest {
    @Test
    fun variableFallbackDumpsRecordAxisFactsAndSelectedCoordinates() {
        val bundle = defaultFallbackEvidenceBundle()

        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-axis-clamped"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-axis-missing"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-metrics-variation-missing"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-named-instance"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-multi-axis"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"fixtureId":"fallback-variable-cff2"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"selectedNamedInstance":"Condensed Bold"""")
        assertContains(bundle.fallbackDecisionTraceJson, """"selectedVariationCoordinates":[{"axisTag":"wght","value":700.0}]""")
        assertContains(
            bundle.fallbackDecisionTraceJson,
            """"selectedVariationCoordinates":[{"axisTag":"wdth","value":80.0},{"axisTag":"wght","value":700.0}]""",
        )
        assertContains(bundle.resolvedFontRunsJson, """"variationCoordinates":[{"axisTag":"wght","value":650.0}]""")
    }
}
