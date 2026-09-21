@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.variation.MetricVariationLimits
import org.graphiks.kalligraphie.font.sfnt.variation.VariationStore
import org.graphiks.kalligraphie.font.sfnt.variation.VariationStoreEvaluator
import org.graphiks.kalligraphie.font.sfnt.variation.VariationStoreLimits
import org.graphiks.kalligraphie.font.sfnt.variation.deltaSetIndexMap0
import org.graphiks.kalligraphie.font.sfnt.variation.itemVariationStore
import org.graphiks.kalligraphie.font.sfnt.variation.readDeltaSetIndexMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ColrV1VariationTest {
    @Test
    fun usesTheIdentityMappingWhenNoIndexMapIsPresent() {
        val variation = ColrV1Variation(store(listOf(intArrayOf(100), intArrayOf(-200))), null)

        assertEquals(100.0, variation.delta(0, 0, listOf(1.0)))
        assertEquals(-200.0, variation.delta(1, 0, listOf(1.0)))
    }

    /**
     * The map bytes are preceded by a one-byte pad: offset `0` is the shared reader's "absent map"
     * sentinel, so a map can only be read from a non-zero offset.
     */
    @Test
    fun addressesTheDeltaSetThroughTheIndexMap() {
        val mapBytes = deltaSetIndexMap0(outer = 0, inner = 1, mapCount = 1)
        val table = ByteArray(1 + mapBytes.size)
        mapBytes.copyInto(table, 1)
        val map = checkNotNull(
            success(
                readDeltaSetIndexMap(
                    table,
                    1,
                    "COLR",
                    MetricVariationLimits(),
                    CancellationToken.none,
                ),
            ),
        )
        val variation = ColrV1Variation(store(listOf(intArrayOf(100), intArrayOf(-200))), map)

        assertEquals(-200.0, variation.delta(0, 0, listOf(1.0)))
    }

    @Test
    fun scalesTheDeltaAtAnIntermediateLocation() {
        val variation = ColrV1Variation(store(listOf(intArrayOf(100))), null)

        assertEquals(50.0, variation.delta(0, 0, listOf(0.5)))
        assertEquals(100.0, variation.delta(0, 0, listOf(1.0)))
    }

    @Test
    fun returnsZeroForTheNoVariationIndex() {
        val variation = variationWithMap(
            rows = listOf(intArrayOf(100), intArrayOf(-200)),
            entries = listOf(0 to 1),
        )

        assertEquals(0.0, variation.delta(NO_VARIATION_INDEX, 0, listOf(1.0)))
    }

    @Test
    fun returnsZeroForATargetBeyondTheThirtyTwoBitRange() {
        val variation = variationWithMap(
            rows = listOf(intArrayOf(100), intArrayOf(-200)),
            entries = listOf(0 to 1),
        )

        assertEquals(0.0, variation.delta(0x1_0000_0000L, 0, listOf(1.0)))
    }

    @Test
    fun reusesTheLastIndexMapEntryBeyondItsEntryCount() {
        val variation = variationWithMap(
            rows = listOf(intArrayOf(100), intArrayOf(-200)),
            entries = listOf(0 to 0, 0 to 1),
        )

        assertEquals(100.0, variation.delta(0L, 0, listOf(1.0)))
        assertEquals(-200.0, variation.delta(1L, 0, listOf(1.0)))
        assertEquals(-200.0, variation.delta(5L, 0, listOf(1.0)))
    }

    /**
     * `VarIndexBase` `0x10000` with no map: the specification's implicit mapping is outer = high
     * word (1), inner = low word (0). The flat "outer = 0, inner = target" reading would address
     * row `0x10000` in item data 0 (which has one row) and yield `0.0`.
     */
    @Test
    fun splitsTheTargetIntoTheSpecSixteenBitWordsWhenNoIndexMapIsPresent() {
        val variation = ColrV1Variation(store(twoSubtableStore(firstDelta = 11, secondDelta = 22)), null)

        assertEquals(11.0, variation.delta(0L, 0, listOf(1.0)))
        assertEquals(22.0, variation.delta(0x1_0000L, 0, listOf(1.0)))
    }

    @Test
    fun parsesTheStoreAndIndexMapFromTheHeaderOffsets() {
        val store = itemVariationStore(listOf(intArrayOf(-8192)))
        val table = ByteArray(2 + store.size)
        store.copyInto(table, 2)
        val indexes = indexes(varIndexMapOffset = 0L, varStoreOffset = 2L)

        val variation = checkNotNull(
            success(readColrVariation(table, indexes, 1, MetricVariationLimits(), VariationStoreLimits(), CancellationToken.none)),
        )

        assertEquals(-8192.0, variation.delta(0, 0, listOf(1.0)))
    }

    @Test
    fun rejectsAnAxisCountMismatch() {
        val store = itemVariationStore(listOf(intArrayOf(1)))
        val table = ByteArray(1 + store.size)
        store.copyInto(table, 1)
        val indexes = indexes(varIndexMapOffset = 0L, varStoreOffset = 1L)

        val failure = assertIs<FontOperationResult.Failure>(
            readColrVariation(table, indexes, expectedAxisCount = 2, MetricVariationLimits(), VariationStoreLimits(), CancellationToken.none),
        )
        assertEquals("font.variation.invalid-colr", failure.error.code)
    }

    @Test
    fun rejectsAnUnsupportedStoreFormat() {
        val table = byteArrayOf(0, 0, 2, 0, 0, 0, 0, 0, 0)
        val indexes = indexes(varIndexMapOffset = 0L, varStoreOffset = 1L)

        val failure = assertIs<FontOperationResult.Failure>(
            readColrVariation(table, indexes, 1, MetricVariationLimits(), VariationStoreLimits(), CancellationToken.none),
        )
        assertEquals("font.variation.unsupported-store-format", failure.error.code)
    }

    @Test
    fun rejectsATruncatedStore() {
        val table = byteArrayOf(0, 1, 0)
        val indexes = indexes(varIndexMapOffset = 0L, varStoreOffset = 1L)

        val failure = assertIs<FontOperationResult.Failure>(
            readColrVariation(table, indexes, 1, MetricVariationLimits(), VariationStoreLimits(), CancellationToken.none),
        )
        assertEquals("font.variation.truncated-store", failure.error.code)
    }

    @Test
    fun rejectsAStoreWhenTheVariationStoreLimitForbidsOne() {
        val store = itemVariationStore(listOf(intArrayOf(1)))
        val table = ByteArray(1 + store.size)
        store.copyInto(table, 1)
        val indexes = indexes(varIndexMapOffset = 0L, varStoreOffset = 1L)

        val failure = assertIs<FontOperationResult.Failure>(
            readColrVariation(table, indexes, 1, MetricVariationLimits(maxVariationStores = 0), VariationStoreLimits(), CancellationToken.none),
        )
        assertEquals("font.resource-limit-exceeded", failure.error.code)
    }

    @Test
    fun returnsNullWhenTheStoreOffsetIsAbsent() {
        val table = byteArrayOf(0, 0)
        val indexes = indexes(varIndexMapOffset = 0L, varStoreOffset = 0L)

        assertNull(success(readColrVariation(table, indexes, 1, MetricVariationLimits(), VariationStoreLimits(), CancellationToken.none)))
    }

    @Test
    fun cancelsBeforeParsing() {
        val store = itemVariationStore(listOf(intArrayOf(1)))
        val table = ByteArray(1 + store.size)
        store.copyInto(table, 1)
        val indexes = indexes(varIndexMapOffset = 0L, varStoreOffset = 1L)

        assertIs<FontOperationResult.Cancelled>(
            readColrVariation(table, indexes, 1, MetricVariationLimits(), VariationStoreLimits(), CancellationToken.cancelled),
        )
    }

    private fun store(rows: List<IntArray>): VariationStore =
        success(VariationStoreEvaluator.read(itemVariationStore(rows), 0, "COLR", includeDeltas = true))

    private fun store(bytes: ByteArray): VariationStore =
        success(VariationStoreEvaluator.read(bytes, 0, "COLR", includeDeltas = true))

    /**
     * A format-0 `DeltaSetIndexMap` built from [entries] and read through the shared reader. The map
     * bytes are preceded by a one-byte pad because offset `0` is the reader's "absent map" sentinel.
     */
    private fun variationWithMap(rows: List<IntArray>, entries: List<Pair<Int, Int>>): ColrV1Variation {
        val innerBitCount = 8
        val entryFormat = 0x00 or (innerBitCount - 1)
        val out = ArrayList<Byte>()
        fun u8(value: Int) { out += (value and 0xFF).toByte() }
        fun u16(value: Int) { u8(value shr 8); u8(value) }
        u8(0)
        u8(entryFormat)
        u16(entries.size)
        entries.forEach { (outer, inner) -> u8((outer shl innerBitCount) or inner) }
        val encoded = out.toByteArray()
        val table = ByteArray(1 + encoded.size)
        encoded.copyInto(table, 1)
        val map = checkNotNull(
            success(readDeltaSetIndexMap(table, 1, "COLR", MetricVariationLimits(), CancellationToken.none)),
        )
        return ColrV1Variation(store(rows), map)
    }

    /**
     * One-axis format-1 `ItemVariationStore` with two item variation data subtables, each holding a
     * single one-region row. Used to discriminate the implicit `outer`/`inner` 16-bit split from a
     * flat `(outer = 0, inner = target)` reading.
     */
    private fun twoSubtableStore(firstDelta: Int, secondDelta: Int): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(value: Int) { out += (value and 0xFF).toByte() }
        fun u16(value: Int) { u8(value shr 8); u8(value) }
        fun u32(value: Int) { u8(value shr 24); u8(value shr 16); u8(value shr 8); u8(value) }
        val regionListOffset = 16
        val firstOffset = regionListOffset + 4 + 6
        val secondOffset = firstOffset + 10
        u16(1)
        u32(regionListOffset)
        u16(2)
        u32(firstOffset)
        u32(secondOffset)
        u16(1); u16(1)
        u16(0x0000); u16(0x4000); u16(0x4000)
        u16(1); u16(1); u16(1)
        u16(0)
        u16(firstDelta and 0xFFFF)
        u16(1); u16(1); u16(1)
        u16(0)
        u16(secondDelta and 0xFFFF)
        return out.toByteArray()
    }

    private fun indexes(varIndexMapOffset: Long, varStoreOffset: Long): ColrV1Indexes = ColrV1Indexes(
        glyphs = IntArray(0),
        paints = IntArray(0),
        layers = IntArray(0),
        clipStarts = IntArray(0),
        clipEnds = IntArray(0),
        clipOffsets = IntArray(0),
        legacyGlyphs = IntArray(0),
        legacyFirsts = IntArray(0),
        legacyCounts = IntArray(0),
        legacyLayerOffset = 0,
        varIndexMapOffset = varIndexMapOffset,
        varStoreOffset = varStoreOffset,
    )

    private fun <T> success(result: FontOperationResult<T>): T = assertIs<FontOperationResult.Success<T>>(result).value
}
