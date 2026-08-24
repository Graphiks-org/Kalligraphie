package io.ygdrasil.kalligraphie.text

import kotlin.test.Test
import kotlin.test.assertContains
import io.ygdrasil.kalligraphie.text.shaping.defaultFallbackShapedGlyphRunEvidenceJson

class VariableFallbackShapingEvidenceTest {
    @Test
    fun variableFallbackShapingDumpLinksVariableFallbackFixtures() {
        val actual = defaultFallbackShapedGlyphRunEvidenceJson()

        assertContains(actual, """"fixtureId":"fallback-axis-clamped"""")
        assertContains(actual, """"fixtureId":"fallback-axis-missing"""")
        assertContains(actual, """"fixtureId":"fallback-metrics-variation-missing"""")
        assertContains(actual, """"fixtureId":"fallback-named-instance"""")
        assertContains(actual, """"fixtureId":"fallback-multi-axis"""")
        assertContains(actual, """"fixtureId":"fallback-variable-cff2"""")
    }
}
