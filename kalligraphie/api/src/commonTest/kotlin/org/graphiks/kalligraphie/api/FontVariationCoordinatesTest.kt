package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FontVariationCoordinatesTest {
    @Test
    fun sortsCoordinatesByUniqueTag() {
        val coordinates = FontVariationCoordinates(
            listOf(FontVariationCoordinate("wght", 700f), FontVariationCoordinate("opsz", 14f)),
        )
        assertEquals(listOf("opsz", "wght"), coordinates.coordinates.map { it.tag })
    }

    @Test
    fun rejectsDuplicateTags() {
        assertFailsWith<IllegalArgumentException> {
            FontVariationCoordinates(listOf(FontVariationCoordinate("wght", 400f), FontVariationCoordinate("wght", 700f)))
        }
    }

    @Test
    fun rejectsTagsThatAreNotFourCharacters() {
        assertFailsWith<IllegalArgumentException> { FontVariationCoordinate("wg", 400f) }
        assertFailsWith<IllegalArgumentException> { FontVariationCoordinate("wghtt", 400f) }
    }

    @Test
    fun rejectsNonFiniteValues() {
        assertFailsWith<IllegalArgumentException> { FontVariationCoordinate("wght", Float.NaN) }
        assertFailsWith<IllegalArgumentException> { FontVariationCoordinate("wght", Float.POSITIVE_INFINITY) }
    }

    @Test
    fun canonicalizesNegativeZero() {
        assertEquals(0f, FontVariationCoordinate("wght", -0f).value)
    }

    @Test
    fun emptySelectionEqualsDefault() {
        assertEquals(FontVariationCoordinates.default, FontVariationCoordinates(emptyList()))
    }
}
