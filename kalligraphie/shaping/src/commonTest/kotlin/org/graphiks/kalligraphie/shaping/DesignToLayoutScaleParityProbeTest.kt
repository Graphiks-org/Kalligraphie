package org.graphiks.kalligraphie.shaping

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-target parity probe for the design-unit -> layout-unit boundary (design §8.6).
 *
 * `DesignToLayoutScale.convert` computes `designUnit.toDouble() * layoutSize / unitsPerEm.toDouble()`
 * and then narrows with `toFloat()`. On Kotlin/JS and Kotlin/Wasm `Float` has different storage
 * semantics than on the JVM, so this probe pins the exact IEEE-754 bit pattern every target must
 * produce. It never opens a shaping backend, so the missing web shaper is irrelevant.
 *
 * The `expectedBits` tables below are the JVM oracle: they were generated once by evaluating
 * `convert(u).value.toRawBits()` on the JVM and are hardcoded on purpose. The assertions must not
 * regenerate them at runtime, otherwise the probe would only compare a target against itself.
 *
 * Scope: this probe covers single `DesignToLayoutScale.convert` values bit-exactly on every target
 * and the boundary-narrowed double accumulation below. It does not cover line-wrap thresholds,
 * pixel-boundary rounding, or the product-level float32 narrowing rule for `LayoutUnit.value` on
 * Kotlin/JS; those are Phase 4 exit criteria, not asserted here.
 */
class DesignToLayoutScaleParityProbeTest {
    private data class ScaleCase(val layoutSize: Float, val unitsPerEm: Int)

    private val scaleCases = listOf(
        ScaleCase(layoutSize = 12f, unitsPerEm = 1000),
        ScaleCase(layoutSize = 14.5f, unitsPerEm = 1024),
        ScaleCase(layoutSize = 16.25f, unitsPerEm = 2048),
        ScaleCase(layoutSize = 0.5f, unitsPerEm = 2048),
        ScaleCase(layoutSize = 72.75f, unitsPerEm = 1000),
    )

    private val designUnits = listOf(0, 1, 3, 7, 123, 511, 1000, 2048, 65535)

    /** JVM `Float.toRawBits()` per scale case, aligned with [designUnits]. */
    private val expectedBitsByCase = listOf(
        // 12f / 1000
        listOf(0, 1011129254, 1024685244, 1034684465, 1069346193, 1086601560, 1094713344, 1103403942, 1145346785),
        // 14.5f / 1024
        listOf(0, 1013448704, 1026424832, 1036713984, 1071575040, 1088916480, 1096978432, 1105723392, 1147666200),
        // 16.25f / 2048
        listOf(0, 1006764032, 1019412480, 1029931008, 1064949760, 1082244864, 1090381824, 1099038720, 1140981630),
        // 0.5f / 2048
        listOf(0, 964689920, 977272832, 987758592, 1022754816, 1040154624, 1048182784, 1056964608, 1098907392),
        // 72.75f / 1000
        listOf(0, 1033174516, 1046445294, 1057119797, 1091513352, 1108652917, 1116831744, 1125449204, 1167392095),
    )

    @Test
    fun everySingleConversionMatchesTheJvmRawBitOracle() {
        val divergences = mutableListOf<String>()
        for (caseIndex in scaleCases.indices) {
            val scaleCase = scaleCases[caseIndex]
            val expectedBits = expectedBitsByCase[caseIndex]
            val scale = DesignToLayoutScale.create(scaleCase.layoutSize, scaleCase.unitsPerEm)
            for (unitIndex in designUnits.indices) {
                val designUnit = designUnits[unitIndex]
                val actualBits = scale.convert(designUnit).value.toRawBits()
                if (actualBits != expectedBits[unitIndex]) {
                    divergences +=
                        "size=${scaleCase.layoutSize} upem=${scaleCase.unitsPerEm} " +
                        "unit=$designUnit expected=${expectedBits[unitIndex]} actual=$actualBits"
                }
            }
        }
        assertEquals(emptyList(), divergences, "Design-to-layout conversions diverged from the JVM oracle.")
    }

    /**
     * A fixed multiset of design units summed through the conversion boundary.
     *
     * Normalization rule: cross-target geometry accumulation is performed in `Double` and narrowed
     * to `Float` once, at the boundary — `sum.toFloat().toRawBits()`. Kotlin/JS represents `Float`
     * as a JavaScript `Number` and `LayoutUnit.value` therefore keeps double precision on JS, so a
     * bare `.value.toDouble()` sum still diverges from the JVM by 1 ULP. The converted boundary
     * value is materialized as a genuine `Float` through `toRawBits()`/`fromBits()` before it joins
     * the double sum, which makes every target agree on the same IEEE-754 bit pattern. Step-by-step
     * `Float` accumulation (the pre-fix behavior) is a known divergence to be avoided in product
     * geometry; residual JS precision risk is tracked for Phase 2/4.
     */
    private val accumulationMultiset = listOf(
        1, 3, 7, 123, 511, 1000, 2048, 65535,
        1, 3, 7, 123, 511, 1000, 2048, 65535,
    )

    private val expectedAccumulationBits = listOf(
        1154461466, // 12f / 1000
        1156911584, // 14.5f / 1024
        1149850328, // 16.25f / 2048
        1107768832, // 0.5f / 2048
        1176330930, // 72.75f / 1000
    )

    @Test
    fun everyRepeatedAccumulationMatchesTheJvmRawBitOracle() {
        val divergences = mutableListOf<String>()
        for (caseIndex in scaleCases.indices) {
            val scaleCase = scaleCases[caseIndex]
            val scale = DesignToLayoutScale.create(scaleCase.layoutSize, scaleCase.unitsPerEm)
            var accumulated = 0.0
            for (designUnit in accumulationMultiset) {
                // Materialize the converted boundary value as a true Float before summing: Kotlin/JS
                // keeps `LayoutUnit.value` in double precision, so `.value.toDouble()` alone is not
                // the float32 boundary value the JVM sees. toRawBits/fromBits pins the same Float
                // on every target; the sum is then Double and narrows once below.
                val boundary = Float.fromBits(scale.convert(designUnit).value.toRawBits())
                accumulated += boundary.toDouble()
            }
            val actualBits = accumulated.toFloat().toRawBits()
            val expectedBits = expectedAccumulationBits[caseIndex]
            if (actualBits != expectedBits) {
                divergences +=
                    "accumulated size=${scaleCase.layoutSize} upem=${scaleCase.unitsPerEm} " +
                    "expected=$expectedBits actual=$actualBits"
            }
        }
        assertEquals(emptyList(), divergences, "Accumulated design-to-layout bits diverged from the JVM oracle.")
    }
}
