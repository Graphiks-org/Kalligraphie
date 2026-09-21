@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult

class MetricVariationSupportTest {
    @Test
    fun returnsNullForAnAbsentMap() {
        val map = success(
            readDeltaSetIndexMap(byteArrayOf(), 0, "HVAR", MetricVariationLimits(), CancellationToken.none),
        )

        assertNull(map)
    }

    @Test
    fun decodesAFormatOneMap() {
        val bytes = deltaSetIndexMap1(entryFormat = 0x13, entries = listOf(2 to 5, 3 to 1))

        val map = requireNotNull(
            success(readDeltaSetIndexMap(bytes, MAP_OFFSET, "HVAR", MetricVariationLimits(), CancellationToken.none)),
        )

        assertEquals(2, map.entryCount)
        assertEquals(2, map.outerIndex(0))
        assertEquals(5, map.innerIndex(0))
        assertEquals(3, map.outerIndex(1))
        assertEquals(1, map.innerIndex(1))
    }

    @Test
    fun clampsATargetPastTheMapCountToTheLastEntry() {
        val bytes = deltaSetIndexMap1(entryFormat = 0x13, entries = listOf(2 to 5, 3 to 1))

        val map = requireNotNull(
            success(readDeltaSetIndexMap(bytes, MAP_OFFSET, "HVAR", MetricVariationLimits(), CancellationToken.none)),
        )

        assertEquals(3, map.outerIndex(9))
        assertEquals(1, map.innerIndex(9))
    }

    @Test
    fun treatsAnEmptyMapAsTheImplicitIdentityMapping() {
        val bytes = deltaSetIndexMap1(entryFormat = 0x13, entries = emptyList())

        val map = requireNotNull(
            success(readDeltaSetIndexMap(bytes, MAP_OFFSET, "HVAR", MetricVariationLimits(), CancellationToken.none)),
        )

        assertEquals(0, map.entryCount)
        assertEquals(0, map.outerIndex(7))
        assertEquals(7, map.innerIndex(7))
    }

    @Test
    fun rejectsAFormatOneMapAboveTheEntryLimit() {
        val bytes = deltaSetIndexMap1(entryFormat = 0x13, entries = listOf(0 to 0, 0 to 0, 0 to 0))

        val failure = assertIs<FontOperationResult.Failure>(
            readDeltaSetIndexMap(
                bytes,
                MAP_OFFSET,
                "HVAR",
                MetricVariationLimits(maxDeltaSetIndexEntries = 2),
                CancellationToken.none,
            ),
        )
        assertEquals("font.resource-limit-exceeded", failure.error.code)
    }

    private companion object {
        /** Maps live at a non-zero offset because offset `0` is the absent sentinel. */
        const val MAP_OFFSET = 1
    }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

/**
 * DeltaSetIndexMap format 1 with the given [entries], preceded by a one-byte pad so the map starts at
 * offset `1` (offset `0` is the absent sentinel). [entryFormat] `0x13` selects a two-byte entry with
 * a four-bit inner index, so each entry packs `(outer shl 4) or inner`.
 */
private fun deltaSetIndexMap1(entryFormat: Int, entries: List<Pair<Int, Int>>): ByteArray {
    val entrySize = ((entryFormat and 0x30) shr 4) + 1
    val innerBitCount = (entryFormat and 0x0F) + 1
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u32(value: Int) { u8(value ushr 24); u8(value ushr 16); u8(value ushr 8); u8(value) }
    u8(0x00)
    u8(1)
    u8(entryFormat)
    u32(entries.size)
    for ((outer, inner) in entries) {
        val entry = (outer shl innerBitCount) or inner
        for (shift in entrySize - 1 downTo 0) u8(entry ushr (shift * 8))
    }
    return out.toByteArray()
}
