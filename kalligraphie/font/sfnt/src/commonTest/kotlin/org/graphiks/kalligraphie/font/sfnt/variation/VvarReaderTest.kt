@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult

class VvarReaderTest {
    @Test
    fun readsAdvanceHeightDeltasWithImplicitGlyphIndices() {
        val data = success(VvarReader.read(vvarTable(advanceHeightDeltas = intArrayOf(0, -40)), expectedAxisCount = 1))

        assertEquals(0.0, data.advanceHeightDelta(0, listOf(1.0)))
        assertEquals(-40.0, data.advanceHeightDelta(1, listOf(1.0)))
        assertEquals(-20.0, data.advanceHeightDelta(1, listOf(0.5)))
    }

    @Test
    fun returnsZeroForTopSideBearingWithoutAMapping() {
        val data = success(VvarReader.read(vvarTable(advanceHeightDeltas = intArrayOf(7)), expectedAxisCount = 1))

        assertEquals(0.0, data.topSideBearingDelta(0, listOf(1.0)))
        assertEquals(0.0, data.bottomSideBearingDelta(0, listOf(1.0)))
    }

    @Test
    fun appliesTopSideBearingDeltaWithAMapping() {
        val data = success(
            VvarReader.read(
                vvarTable(
                    advanceHeightDeltas = intArrayOf(12),
                    tsbMap = deltaSetIndexMap0(outer = 0, inner = 0, mapCount = 1),
                ),
                expectedAxisCount = 1,
            ),
        )

        assertEquals(12.0, data.topSideBearingDelta(0, listOf(1.0)))
        assertEquals(0.0, data.bottomSideBearingDelta(0, listOf(1.0)))
    }

    @Test
    fun rejectsAnUnsupportedVersion() {
        val table = vvarTable(advanceHeightDeltas = intArrayOf(0)).also { it[1] = 5 }

        val failure = assertIs<FontOperationResult.Failure>(VvarReader.read(table, expectedAxisCount = 1))
        assertEquals("font.variation.unsupported-vvar-version", failure.error.code)
    }

    @Test
    fun rejectsATruncatedHeader() {
        val failure = assertIs<FontOperationResult.Failure>(VvarReader.read(ByteArray(20), expectedAxisCount = 1))
        assertEquals("font.variation.invalid-vvar", failure.error.code)
    }

    @Test
    fun rejectsAnAxisCountMismatch() {
        val failure = assertIs<FontOperationResult.Failure>(
            VvarReader.read(vvarTable(advanceHeightDeltas = intArrayOf(0)), expectedAxisCount = 2),
        )
        assertEquals("font.variation.invalid-vvar", failure.error.code)
    }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

/** Builds a VVAR 1.0 table with one implicit advance-height delta row per glyph. */
private fun vvarTable(
    advanceHeightDeltas: IntArray,
    tsbMap: ByteArray? = null,
    bsbMap: ByteArray? = null,
): ByteArray {
    val store = itemVariationStore(itemDeltas = advanceHeightDeltas.map { intArrayOf(it) })
    val headerSize = 24
    var cursor = headerSize
    val tsbOffset = if (tsbMap == null) 0 else cursor.also { cursor += tsbMap.size }
    val bsbOffset = if (bsbMap == null) 0 else cursor.also { cursor += bsbMap.size }
    val storeOffset = cursor
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    fun u32(value: Int) { u8(value shr 24); u8(value shr 16); u8(value shr 8); u8(value) }
    u16(1); u16(0)
    u32(storeOffset)
    u32(0)
    u32(tsbOffset)
    u32(bsbOffset)
    u32(0)
    tsbMap?.forEach { u8(it.toInt() and 0xFF) }
    bsbMap?.forEach { u8(it.toInt() and 0xFF) }
    store.forEach { u8(it.toInt() and 0xFF) }
    return out.toByteArray()
}
