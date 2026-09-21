@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult

class HvarReaderTest {
    /** Glyphs 0..2 with deltas `[0, 86, 0]` and no advance-width mapping. */
    @Test
    fun readsAdvanceWidthDeltasWithImplicitGlyphIndices() {
        val table = hvarTable(advanceDeltas = intArrayOf(0, 86, 0))

        val data = success(HvarReader.read(table, expectedAxisCount = 1))

        assertEquals(1, data.axisCount)
        assertEquals(0.0, data.advanceWidthDelta(0, listOf(1.0)))
        assertEquals(86.0, data.advanceWidthDelta(1, listOf(1.0)))
        assertEquals(43.0, data.advanceWidthDelta(1, listOf(0.5)))
        assertEquals(0.0, data.advanceWidthDelta(2, listOf(1.0)))
    }

    @Test
    fun returnsZeroForSideBearingsWithoutMappingSubtables() {
        val data = success(HvarReader.read(hvarTable(advanceDeltas = intArrayOf(10)), expectedAxisCount = 1))

        assertEquals(0.0, data.leftSideBearingDelta(0, listOf(1.0)))
        assertEquals(0.0, data.rightSideBearingDelta(0, listOf(1.0)))
    }

    /**
     * The LSB map references delta-set `(outer 0, inner 0)` which carries delta 25; the advance map
     * references the same row via glyph id 0.
     */
    @Test
    fun appliesSideBearingDeltaWithAMapping() {
        val table = hvarTable(
            advanceDeltas = intArrayOf(25),
            lsbMap = deltaSetIndexMap0(outer = 0, inner = 0, mapCount = 1),
        )

        val data = success(HvarReader.read(table, expectedAxisCount = 1))

        assertEquals(25.0, data.advanceWidthDelta(0, listOf(1.0)))
        assertEquals(25.0, data.leftSideBearingDelta(0, listOf(1.0)))
        assertEquals(0.0, data.rightSideBearingDelta(0, listOf(1.0)))
    }

    @Test
    fun rejectsAnUnsupportedVersion() {
        val table = hvarTable(advanceDeltas = intArrayOf(0)).also { it[1] = 2 }

        val failure = assertIs<FontOperationResult.Failure>(HvarReader.read(table, expectedAxisCount = 1))
        assertEquals("font.variation.unsupported-hvar-version", failure.error.code)
    }

    @Test
    fun rejectsATruncatedHeader() {
        val failure = assertIs<FontOperationResult.Failure>(HvarReader.read(ByteArray(12), expectedAxisCount = 1))
        assertEquals("font.variation.invalid-hvar", failure.error.code)
    }

    @Test
    fun rejectsAnAxisCountMismatch() {
        val failure = assertIs<FontOperationResult.Failure>(
            HvarReader.read(hvarTable(advanceDeltas = intArrayOf(0)), expectedAxisCount = 2),
        )
        assertEquals("font.variation.invalid-hvar", failure.error.code)
    }

    @Test
    fun rejectsAMissingVariationStoreWhenTheLimitForbidsOne() {
        val failure = assertIs<FontOperationResult.Failure>(
            HvarReader.read(
                hvarTable(advanceDeltas = intArrayOf(0)),
                expectedAxisCount = 1,
                limits = MetricVariationLimits(maxVariationStores = 0),
            ),
        )
        assertEquals("font.resource-limit-exceeded", failure.error.code)
    }

    @Test
    fun rejectsAMapAboveTheEntryLimit() {
        val failure = assertIs<FontOperationResult.Failure>(
            HvarReader.read(
                hvarTable(advanceDeltas = intArrayOf(0), lsbMap = deltaSetIndexMap0(0, 0, mapCount = 4)),
                expectedAxisCount = 1,
                limits = MetricVariationLimits(maxDeltaSetIndexEntries = 2),
            ),
        )
        assertEquals("font.resource-limit-exceeded", failure.error.code)
    }

    /**
     * The store offset points 4 bytes into the table; rather than move the
     * `itemVariationStoreOffset` into the delta-set map area, corrupt the first store word of a
     * hand-built table with a bad store offset.
     */
    @Test
    fun propagatesStoreFailures() {
        val table = hvarTable(advanceDeltas = intArrayOf(0)).also { bytes ->
            bytes.writeUInt32(4, 20)
            bytes[20] = 0
            bytes[21] = 3
        }

        val failure = assertIs<FontOperationResult.Failure>(HvarReader.read(table, expectedAxisCount = 1))
        assertEquals("font.variation.unsupported-store-format", failure.error.code)
    }

    @Test
    fun honoursCancellation() {
        val result = HvarReader.read(
            hvarTable(advanceDeltas = intArrayOf(0)),
            expectedAxisCount = 1,
            cancellationToken = CancellationToken.cancelled,
        )

        assertIs<FontOperationResult.Cancelled>(result)
    }

    /**
     * A present advance-width map with zero entries resolves to the implicit identity mapping
     * (`outer = 0, inner = glyphId`), exactly like an absent advance map: `DeltaSetIndexMap` passes
     * the glyph id straight through when it holds no entries. Glyph 0 therefore reads the single
     * store row while glyph 7 addresses a row the store does not carry.
     */
    @Test
    fun treatsAnEmptyAdvanceWidthMapAsTheImplicitGlyphIndex() {
        val table = hvarTable(
            advanceDeltas = intArrayOf(25),
            advanceMap = deltaSetIndexMap0(outer = 0, inner = 0, mapCount = 0),
        )

        val data = success(HvarReader.read(table, expectedAxisCount = 1))

        assertEquals(25.0, data.advanceWidthDelta(0, listOf(1.0)))
        assertEquals(0.0, data.advanceWidthDelta(7, listOf(1.0)))
    }

    /**
     * An implicit glyph-id inner index past the store's delta rows (`itemCount < glyphCount`) has no
     * row to interpolate and yields `0.0` without touching out-of-range storage.
     */
    @Test
    fun returnsZeroWhenTheGlyphExceedsTheDeltaRows() {
        val table = hvarTable(advanceDeltas = intArrayOf(15))

        val data = success(HvarReader.read(table, expectedAxisCount = 1))

        assertEquals(15.0, data.advanceWidthDelta(0, listOf(1.0)))
        assertEquals(0.0, data.advanceWidthDelta(7, listOf(1.0)))
    }

    @Test
    fun appliesANegativeAdvanceWidthDelta() {
        val table = hvarTable(advanceDeltas = intArrayOf(-40))

        val data = success(HvarReader.read(table, expectedAxisCount = 1))

        assertEquals(-40.0, data.advanceWidthDelta(0, listOf(1.0)))
        assertEquals(-20.0, data.advanceWidthDelta(0, listOf(0.5)))
    }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

/** Builds an HVAR 1.0 table with one implicit advance-width delta row per glyph. */
internal fun hvarTable(
    advanceDeltas: IntArray,
    advanceMap: ByteArray? = null,
    lsbMap: ByteArray? = null,
    rsbMap: ByteArray? = null,
): ByteArray {
    val store = itemVariationStore(itemDeltas = advanceDeltas.map { intArrayOf(it) })
    val headerSize = 20
    var cursor = headerSize
    val advanceOffset = if (advanceMap == null) 0 else cursor.also { cursor += advanceMap.size }
    val lsbOffset = if (lsbMap == null) 0 else cursor.also { cursor += lsbMap.size }
    val rsbOffset = if (rsbMap == null) 0 else cursor.also { cursor += rsbMap.size }
    val storeOffset = cursor
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    fun u32(value: Int) { u8(value shr 24); u8(value shr 16); u8(value shr 8); u8(value) }
    u16(1); u16(0)
    u32(storeOffset)
    u32(advanceOffset)
    u32(lsbOffset)
    u32(rsbOffset)
    advanceMap?.forEach { u8(it.toInt() and 0xFF) }
    lsbMap?.forEach { u8(it.toInt() and 0xFF) }
    rsbMap?.forEach { u8(it.toInt() and 0xFF) }
    store.forEach { u8(it.toInt() and 0xFF) }
    return out.toByteArray()
}

/** DeltaSetIndexMap format 0 with `mapCount` identical `(outer, inner)` entries. */
internal fun deltaSetIndexMap0(outer: Int, inner: Int, mapCount: Int): ByteArray {
    val innerBitCount = 8
    val entryFormat = 0x00 or (innerBitCount - 1)
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    u8(0)
    u8(entryFormat)
    u16(mapCount)
    repeat(mapCount) { u8((outer shl innerBitCount) or inner) }
    return out.toByteArray()
}

/**
 * Builds a one-axis format-1 ItemVariationStore with region `(0, 1, 1)` and one item variation data
 * subtable. [itemDeltas] is the list of rows; each row is one int16 delta per region.
 */
internal fun itemVariationStore(itemDeltas: List<IntArray>): ByteArray {
    val regionListOffset = 12
    val regionListSize = 4 + 6
    val itemDataOffset = regionListOffset + regionListSize
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    fun u32(value: Int) { u8(value shr 24); u8(value shr 16); u8(value shr 8); u8(value) }
    u16(1)
    u32(regionListOffset)
    u16(1)
    u32(itemDataOffset)
    u16(1)
    u16(1)
    u16(0x0000); u16(0x4000); u16(0x4000)
    u16(itemDeltas.size)
    u16(itemDeltas.firstOrNull()?.size ?: 0)
    u16(1)
    u16(0)
    itemDeltas.forEach { row -> row.forEach { u16(it and 0xFFFF) } }
    return out.toByteArray()
}

private fun ByteArray.writeUInt32(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}
