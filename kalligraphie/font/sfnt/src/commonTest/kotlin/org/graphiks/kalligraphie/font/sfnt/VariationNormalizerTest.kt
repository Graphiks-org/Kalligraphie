@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates

class VariationNormalizerTest {
    private val wght = FvarAxis("wght", 100f, 400f, 900f, hidden = false, nameId = 1)
    private val opsz = FvarAxis("opsz", 8f, 12f, 72f, hidden = false, nameId = 2)
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

    @Test
    fun appliesAvarPerAxisIndexWhenOnlyOneAxisIsMapped() {
        val twoAxis = FvarData(listOf(wght, opsz), emptyList())
        val avar = AvarData(listOf(
            listOf(AvarSegment(-1f, -1f), AvarSegment(0f, 0f), AvarSegment(0.5f, 0.25f), AvarSegment(1f, 1f)),
            emptyList(),
        ))
        val result = VariationNormalizer.normalize(
            FontVariationCoordinates(listOf(
                FontVariationCoordinate("wght", 650f),
                FontVariationCoordinate("opsz", 30f),
            )),
            twoAxis,
            avar,
        )
        val success = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(result)
        assertEquals(0.25f, success.value.first { it.tag == "wght" }.value)
        assertEquals(0.3f, success.value.first { it.tag == "opsz" }.value)
    }

    @Test
    fun reportsOneDiagnosticPerClampedAxis() {
        val twoAxis = FvarData(listOf(wght, opsz), emptyList())
        val result = VariationNormalizer.normalize(
            FontVariationCoordinates(listOf(
                FontVariationCoordinate("wght", 50f),
                FontVariationCoordinate("opsz", 200f),
            )),
            twoAxis,
            null,
        )
        val success = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(result)
        assertEquals(2, success.diagnostics.size)
        assertTrue(success.diagnostics.all { it.code == "font.variation.axis-clamped" })
    }

    @Test
    fun clampsBelowMinimumToMinusOneAndReports() {
        val result = VariationNormalizer.normalize(coords(50f), fvar, null)
        val success = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(result)
        assertEquals(-1f, success.value.single().value)
        assertEquals(1, success.diagnostics.size)
        assertEquals("font.variation.axis-clamped", success.diagnostics.single().code)
    }

    @Test
    fun degenerateAxisNormalizesToZero() {
        val degenerate = FvarData(
            listOf(FvarAxis("wght", 400f, 400f, 400f, hidden = false, nameId = 1)),
            emptyList(),
        )
        val result = VariationNormalizer.normalize(coords(400f), degenerate, null)
        val success = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(result)
        assertEquals(0f, success.value.single().value)
    }

    @Test
    fun interpolatesInteriorAvarSegment() {
        val avar = AvarData(listOf(listOf(
            AvarSegment(-1f, -1f), AvarSegment(0f, 0f), AvarSegment(0.5f, 0.25f), AvarSegment(1f, 1f),
        )))
        // wght 525 => (525 - 400) / (900 - 400) = 0.25 before avar; interpolates to 0.125.
        val result = VariationNormalizer.normalize(coords(525f), fvar, avar)
        val success = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(result)
        assertEquals(0.125f, success.value.single().value)
    }

    @Test
    fun clampsOutOfRangeAvarOutput() {
        val avar = AvarData(listOf(listOf(
            AvarSegment(-1f, -1f), AvarSegment(0f, 0f), AvarSegment(1f, 1.5f),
        )))
        val result = VariationNormalizer.normalize(coords(900f), fvar, avar)
        val success = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(result)
        assertTrue(success.value.single().value <= 1f)
    }

    @Test
    fun returnsTagSortedImmutableCoordinates() {
        val twoAxis = FvarData(listOf(wght, opsz), emptyList())
        val result = VariationNormalizer.normalize(
            FontVariationCoordinates(listOf(
                FontVariationCoordinate("wght", 400f),
                FontVariationCoordinate("opsz", 12f),
            )),
            twoAxis,
            null,
        )
        val success = assertIs<FontOperationResult.Success<List<FontAxisCoordinate>>>(result)
        assertEquals(listOf("opsz", "wght"), success.value.map { it.tag })
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (success.value as MutableList<FontAxisCoordinate>).add(FontAxisCoordinate("wght", 0f))
        }
    }

    private fun coords(value: Float) =
        FontVariationCoordinates(listOf(FontVariationCoordinate("wght", value)))
}
