package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CffIndexTest {
    @Test
    fun readsOffsetsAndDataForCountTwo() {
        val bytes = byteArrayOf(
            0, 2,
            1,
            1, 3, 5,
            'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 'D'.code.toByte(),
        )

        val index = success(CffIndex.read(bytes, 0))

        assertEquals(2, index.itemCount)
        assertContentEquals("AB".encodeToByteArray(), index.item(0))
        assertContentEquals("CD".encodeToByteArray(), index.item(1))
        assertEquals(10, index.endOffset)
    }

    @Test
    fun emptyIndexOccupiesExactlyTwoBytes() {
        val index = success(CffIndex.read(byteArrayOf(0, 0, 0xAA.toByte()), 0))

        assertEquals(0, index.itemCount)
        assertEquals(2, index.endOffset)
    }

    @Test
    fun usesFourByteOffsets() {
        val bytes = byteArrayOf(
            0, 1,
            4,
            0, 0, 0, 1,
            0, 0, 0, 3,
            'X'.code.toByte(), 'Y'.code.toByte(),
        )

        val index = success(CffIndex.read(bytes, 0))

        assertEquals(1, index.itemCount)
        assertContentEquals("XY".encodeToByteArray(), index.item(0))
        assertEquals(2, index.itemSize(0))
        assertEquals(13, index.endOffset)
    }

    @Test
    fun rejectsNonMonotonicOffsets() {
        val result = CffIndex.read(byteArrayOf(0, 2, 1, 1, 5, 3, 0, 0, 0, 0), 0)

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsDataBeyondTheSource() {
        val result = CffIndex.read(byteArrayOf(0, 1, 1, 1, 9, 0), 0)

        assertIs<FontError.OutOfBounds>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsOffSizeOutsideOneToFour() {
        for (offSize in listOf(0, 5)) {
            val result = CffIndex.read(byteArrayOf(0, 1, offSize.toByte(), 0, 0, 0, 0, 0), 0)
            assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
        }
    }

    @Test
    fun rejectsATruncatedHeader() {
        val result = CffIndex.read(byteArrayOf(0), 0)

        assertIs<FontError.OutOfBounds>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun readsA32BitCountForCff2() {
        val bytes = byteArrayOf(
            0, 0, 0, 2,
            1,
            1, 3, 5,
            'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 'D'.code.toByte(),
        )

        val index = success(CffIndex.read(bytes, 0, countSize = 4))

        assertEquals(2, index.itemCount)
        assertContentEquals("AB".encodeToByteArray(), index.item(0))
        assertEquals(12, index.endOffset)
    }

    @Test
    fun anEmptyCff2IndexOccupiesFourBytes() {
        val index = success(CffIndex.read(byteArrayOf(0, 0, 0, 0, 0xAA.toByte()), 0, countSize = 4))

        assertEquals(0, index.itemCount)
        assertEquals(4, index.endOffset)
    }

    private fun success(result: FontOperationResult<CffIndex>): CffIndex =
        assertIs<FontOperationResult.Success<CffIndex>>(result).value
}
