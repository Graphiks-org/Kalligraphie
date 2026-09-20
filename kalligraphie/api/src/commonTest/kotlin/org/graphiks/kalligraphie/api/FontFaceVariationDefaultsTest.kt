package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FontFaceVariationDefaultsTest {
    private class StaticFace : FontFace {
        override val id: FontFaceId = FontFaceId(FontSourceId.Opaque("test", "0", "face"), 0)
        override val metadata: FontFaceMetadata = FontFaceMetadata("Test", "Regular", 1000, 10)
        override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> =
            error("not used")
    }

    @Test
    fun defaultVariationAxesAndInstancesAreEmpty() {
        val face = StaticFace()
        assertEquals(emptyList(), face.variationAxes())
        assertEquals(emptyList(), face.namedInstances())
    }

    @Test
    fun defaultNormalizeIsAnUnsupportedContractFailure() {
        val result = StaticFace().normalize(FontVariationCoordinates(listOf(FontVariationCoordinate("wght", 700f))))
        assertTrue(result is FontOperationResult.Failure)
    }

    @Test
    fun descriptorCarriesAnOptionalDesignVariation() {
        val descriptor = FontInstanceDescriptor(
            layoutSize = LayoutUnit(12f),
            variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", 700f))),
        )
        assertEquals("wght", descriptor.variation?.coordinates?.single()?.tag)
    }
}
