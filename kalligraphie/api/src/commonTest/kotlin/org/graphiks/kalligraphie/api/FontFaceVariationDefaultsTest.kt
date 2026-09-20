package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontFaceVariationDefaultsTest {
    private class StaticFace : FontFace {
        override val id: FontFaceId = FontFaceId(FontSourceId.Opaque("test", "0", "face"), 0)
        override val metadata: FontFaceMetadata = FontFaceMetadata("Test", "Regular", 1000, 10)
        override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> =
            error("not used")
    }

    private class StaticInstance : FontInstance {
        override val key: FontInstanceKey = FontInstanceKey(
            face = FontFaceId(FontSourceId.Opaque("test", "0", "face"), 0),
            interpretation = FontDataInterpretationVersion("test", "1"),
            layoutSize = LayoutUnit(12f),
        )
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
        assertEquals(
            "font.unsupported-representation-profile",
            assertIs<FontOperationResult.Failure>(result).error.code,
        )
    }

    @Test
    fun defaultStatIsAnEmptySuccess() {
        val stat = assertIs<FontOperationResult.Success<StatTable?>>(StaticFace().stat())
        assertNull(stat.value)
    }

    @Test
    fun defaultFontMetricsIsAnUnsupportedContractFailure() {
        assertIs<FontOperationResult.Failure>(StaticInstance().fontMetrics())
    }

    @Test
    fun descriptorCarriesAnOptionalDesignVariation() {
        val descriptor = FontInstanceDescriptor(
            layoutSize = LayoutUnit(12f),
            variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", 700f))),
        )
        assertEquals("wght", descriptor.variation?.coordinates?.single()?.tag)
    }

    @Test
    fun defaultDescriptorHasNoVariationSelection() {
        assertNull(FontInstanceDescriptor().variation)
    }
}
