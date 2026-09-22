package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.api.ShapingDirection
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VariableLocationPlumbingTest {
    @Test
    fun aNonDefaultLocationReachesThePlatformPrepareWhenSupported() {
        val binding = RecordingPlatformBinding(supportsVariationLocation = true)
        val prepared = HarfBuzzBindings.fromBinding(binding).prepare(
            fontBytes = byteArrayOf(0),
            faceIndex = 0,
            layoutSize = 1000f,
            variationLocation = floatArrayOf(1.0f),
        )
        prepared.close()
        assertEquals(1, binding.preparedLocations.size)
        assertContentEquals(floatArrayOf(1.0f), binding.preparedLocations.single())
    }

    @Test
    fun anUnsupportedBindingRejectsANonDefaultLocation() {
        val binding = RecordingPlatformBinding(supportsVariationLocation = false)
        val failure = assertFailsWith<HarfBuzzBindingException> {
            HarfBuzzBindings.fromBinding(binding).prepare(
                fontBytes = byteArrayOf(0),
                faceIndex = 0,
                layoutSize = 1000f,
                variationLocation = floatArrayOf(1.0f),
            )
        }
        assertEquals(HarfBuzzBindingFailure.VARIATION_UNSUPPORTED, failure.failure)
        assertEquals(emptyList(), binding.preparedLocations)
    }

    @Test
    fun anEmptyLocationNeverCallsTheVariationPath() {
        val binding = RecordingPlatformBinding(supportsVariationLocation = false)
        val prepared = HarfBuzzBindings.fromBinding(binding).prepare(
            fontBytes = byteArrayOf(0),
            faceIndex = 0,
            layoutSize = 1000f,
            variationLocation = FloatArray(0),
        )
        prepared.close()
        assertEquals(1, binding.preparedLocations.size)
        assertContentEquals(FloatArray(0), binding.preparedLocations.single())
    }

    /**
     * An all-zero (design-default) location is a no-op and must not trip the gate: metrics,
     * outlines and COLR all succeed on the pinned binding, so shaping must too.
     */
    @Test
    fun anExplicitDefaultLocationIsAdmittedEvenWhenUnsupported() {
        val binding = RecordingPlatformBinding(supportsVariationLocation = false)
        val prepared = HarfBuzzBindings.fromBinding(binding).prepare(
            fontBytes = byteArrayOf(0),
            faceIndex = 0,
            layoutSize = 1000f,
            variationLocation = floatArrayOf(0.0f),
        )
        prepared.close()
        assertEquals(1, binding.preparedLocations.size)
        assertContentEquals(floatArrayOf(0.0f), binding.preparedLocations.single())
    }

    private class RecordingPlatformBinding(
        override val supportsVariationLocation: Boolean,
    ) : HarfBuzzPlatformBinding {
        override val identity = PlatformBindingIdentity(
            operatingSystem = "test",
            architecture = "test",
            artifactId = "test",
            artifactSha256 = "0".repeat(64),
            upstreamSourceRevision = "test",
            buildChainIdentity = "test",
        )
        val preparedLocations = mutableListOf<FloatArray>()

        override fun createBuffer(): PlatformHarfBuzzBuffer = error("This test never shapes.")

        override fun prepare(fontBytes: ByteArray, faceIndex: Int, variationLocation: FloatArray): PlatformPreparedFont {
            preparedLocations += variationLocation
            return FakePreparedFont(unitsPerEm = 1000)
        }

        override fun release(prepared: PlatformPreparedFont) = Unit
    }

    private class FakePreparedFont(override val unitsPerEm: Int) : PlatformPreparedFont {
        override fun horizontalAdvance(glyphId: Int): Int = 0
        override fun ligatureCarets(
            direction: ShapingDirection,
            glyphId: Int,
            offset: Int,
            count: Int,
        ): PlatformLigatureCarets = error("This test never reads carets.")
    }
}
