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
     * A fixed multiset of design units summed through the conversion boundary. Repeated `Float`
     * accumulation rounds to single precision after every addition on the JVM; a target that keeps
     * wider precision between additions diverges here even when each individual conversion matches.
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
            var accumulated = 0f
            for (designUnit in accumulationMultiset) {
                accumulated += scale.convert(designUnit).value
            }
            val actualBits = accumulated.toRawBits()
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
