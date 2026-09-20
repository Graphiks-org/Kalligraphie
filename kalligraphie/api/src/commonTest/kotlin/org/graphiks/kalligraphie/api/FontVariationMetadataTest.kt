package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FontVariationMetadataTest {
    @Test
    fun axisCarriesDesignBoundsAndHiddenFlag() {
        val axis = FontVariationAxis("wght", 100f, 400f, 900f, nameId = 256, hidden = false)
        assertEquals("wght", axis.tag)
        assertEquals(100f, axis.minValue)
        assertEquals(400f, axis.defaultValue)
        assertEquals(900f, axis.maxValue)
        assertEquals(256, axis.nameId)
        assertEquals(false, axis.hidden)
    }

    @Test
    fun axisRejectsInvertedBounds() {
        assertFailsWith<IllegalArgumentException> { FontVariationAxis("wght", 900f, 400f, 100f, 0, false) }
    }

    @Test
    fun axisRejectsTagsThatAreNotFourCharacters() {
        assertFailsWith<IllegalArgumentException> { FontVariationAxis("wg", 100f, 400f, 900f, 0, false) }
        assertFailsWith<IllegalArgumentException> { FontVariationAxis("wghtt", 100f, 400f, 900f, 0, false) }
    }

    @Test
    fun namedInstanceRejectsNegativeIndex() {
        assertFailsWith<IllegalArgumentException> {
            FontNamedInstance(index = -1, nameId = 257, postScriptNameId = null, coordinates = FontVariationCoordinates.default)
        }
    }

    @Test
    fun namedInstanceKeepsItsCoordinateSelection() {
        val instance = FontNamedInstance(
            index = 1,
            nameId = 257,
            postScriptNameId = null,
            coordinates = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", 700f))),
        )
        assertEquals(257, instance.nameId)
        assertEquals(700f, instance.coordinates.coordinates.single().value)
    }

    @Test
    fun fontMetricsExposeDesignValues() {
        val metrics = FontMetrics(ascender = 800f, descender = -200f, lineGap = 0f)
        assertEquals(800f, metrics.ascender)
        assertEquals(-200f, metrics.descender)
    }

    @Test
    fun statAxisValueCarriesTagAndFlags() {
        val table = StatTable(listOf(StatAxisValue(axisTag = "wght", format = 1, values = listOf(700f), flags = 0, nameId = 259)))
        assertEquals("wght", table.axisValueTables.single().axisTag)
        assertEquals(0, table.axisValueTables.single().flags)
    }

    @Test
    fun statAxisValueRejectsTagsThatAreNotFourCharacters() {
        assertFailsWith<IllegalArgumentException> {
            StatAxisValue(axisTag = "wg", format = 1, values = listOf(700f), flags = 0, nameId = 259)
        }
        assertFailsWith<IllegalArgumentException> {
            StatAxisValue(axisTag = "wghtt", format = 1, values = listOf(700f), flags = 0, nameId = 259)
        }
    }
}
