@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates

class VariationNormalizerTest {
    private val wght = FvarAxis("wght", 100f, 400f, 900f, hidden = false, nameId = 1)
    private val fvar = FvarData(listOf(wght), emptyList())

    @Test
    fun defaultMapsToZero() {
        val result = VariationNormalizer.normalize(coords(400f), fvar, null)
        val success = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(result)
        assertEquals(0f, success.value.single().value)
    }

    @Test
    fun maximumMapsToOneAndMinimumToMinusOne() {
        val max = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(
            VariationNormalizer.normalize(coords(900f), fvar, null),
        )
        assertEquals(1f, max.value.single().value)
        val min = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(
            VariationNormalizer.normalize(coords(100f), fvar, null),
        )
        assertEquals(-1f, min.value.single().value)
    }

    @Test
    fun clampsOutOfRangeValuesAndReportsThem() {
        val result = VariationNormalizer.normalize(coords(1200f), fvar, null)
        val success = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(result)
        assertEquals(1f, success.value.single().value)
        assertEquals(1, success.diagnostics.size)
        assertEquals("font.variation.axis-clamped", success.diagnostics.single().code)
    }

    @Test
    fun unknownAxisFails() {
        val result = VariationNormalizer.normalize(
            FontVariationCoordinates(listOf(FontVariationCoordinate("wdth", 100f))),
            fvar,
            null,
        )
        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.unknown-axis", failure.error.code)
    }

    @Test
    fun appliesAvarSegmentMap() {
        // wght 650 is halfway between default 400 and max 900 => +0.5 before avar => 0.25 after.
        val avar = AvarData(listOf(listOf(
            AvarSegment(-1f, -1f), AvarSegment(0f, 0f), AvarSegment(0.5f, 0.25f), AvarSegment(1f, 1f),
        )))
        val result = VariationNormalizer.normalize(coords(650f), fvar, avar)
        val success = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(result)
        assertEquals(0.25f, success.value.single().value)
    }

    private fun coords(value: Float) =
        FontVariationCoordinates(listOf(FontVariationCoordinate("wght", value)))
}
