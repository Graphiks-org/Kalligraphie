package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CffDictTest {
    @Test
    fun readsEveryIntegerOperandEncoding() {
        assertEquals(-107.0, firstOperand(byteArrayOf(32, 5)))
        assertEquals(0.0, firstOperand(byteArrayOf(139.toByte(), 5)))
        assertEquals(107.0, firstOperand(byteArrayOf(246.toByte(), 5)))
        assertEquals(108.0, firstOperand(byteArrayOf(247.toByte(), 0, 5)))
        assertEquals(1131.0, firstOperand(byteArrayOf(250.toByte(), 0xFF.toByte(), 5)))
        assertEquals(-108.0, firstOperand(byteArrayOf(251.toByte(), 0, 5)))
        assertEquals(-1131.0, firstOperand(byteArrayOf(254.toByte(), 0xFF.toByte(), 5)))
        assertEquals(-200.0, firstOperand(byteArrayOf(28, 0xFF.toByte(), 0x38, 5)))
        assertEquals(-1000.0, firstOperand(byteArrayOf(29, 0xFF.toByte(), 0xFF.toByte(), 0xFC.toByte(), 0x18, 5)))
    }

    @Test
    fun readsARealOperand() {
        val dict = success(CffDict.read(byteArrayOf(30, 0x1A, 0x5F, 5), 0, 4))

        assertEquals(listOf(1.5), dict.operands(5))
    }

    @Test
    fun keepsOperandsWithThePrecedingOperator() {
        val dict = success(CffDict.read(byteArrayOf(140.toByte(), 141.toByte(), 5, 142.toByte(), 12, 7), 0, 6))

        assertEquals(listOf(1.0, 2.0), dict.operands(5))
        assertEquals(listOf(3.0), dict.operands(CffDict.escaped(7)))
        assertNull(dict.operands(6))
    }

    @Test
    fun integerAccessorRejectsFractionalAndNonFiniteOperands() {
        val dict = success(CffDict.read(byteArrayOf(30, 0x1A, 0x5F, 5), 0, 4))

        assertEquals(1.5, dict.operand(5))
        assertNull(dict.integer(5))
    }

    @Test
    fun rejectsAReservedDictByte() {
        val result = CffDict.read(byteArrayOf(0xFF.toByte()), 0, 1)

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun acceptsCff2DictOperatorsTwentyTwoToTwentyFour() {
        // vsindex (22), blend (23), vstore (24) are CFF2 operators, not reserved.
        val dict = success(CffDict.read(byteArrayOf(140.toByte(), 22, 141.toByte(), 23, 142.toByte(), 24), 0, 6))

        assertEquals(listOf(1.0), dict.operands(22))
        assertEquals(listOf(2.0), dict.operands(23))
        assertEquals(listOf(3.0), dict.operands(24))
    }

    @Test
    fun rejectsTrailingOperandsWithoutAnOperator() {
        val result = CffDict.read(byteArrayOf(140.toByte()), 0, 1)

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsTruncatedShortintAndLongintOperands() {
        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(CffDict.read(byteArrayOf(28, 1), 0, 2)).error)
        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(CffDict.read(byteArrayOf(29, 1, 2, 3), 0, 4)).error)
    }

    @Test
    fun rejectsARealOperandWithoutATerminator() {
        val result = CffDict.read(byteArrayOf(30, 0x11, 5), 0, 3)

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsAReservedNibbleInARealOperand() {
        val result = CffDict.read(byteArrayOf(30, 0xD1.toByte(), 0xF0.toByte(), 5), 0, 4)

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsAnEmptyRangeStartPastEnd() {
        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(CffDict.read(byteArrayOf(5), 2, 1)).error)
    }

    private fun firstOperand(bytes: ByteArray): Double? =
        success(CffDict.read(bytes, 0, bytes.size)).operand(5)

    private fun success(result: FontOperationResult<CffDict>): CffDict =
        assertIs<FontOperationResult.Success<CffDict>>(result).value
}
