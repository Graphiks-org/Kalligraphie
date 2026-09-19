package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CffCharsetReaderTest {
    @Test
    fun readsFormatZeroSids() {
        val charset = success(CffCharsetReader.read(padded(0, 0, 10, 0, 20), EXPLICIT_OFFSET, glyphCount = 3))

        assertEquals(CffCharset.Explicit(listOf(10, 20)), charset)
    }

    @Test
    fun readsFormatOneRanges() {
        val charset = success(CffCharsetReader.read(padded(1, 0, 5, 2), EXPLICIT_OFFSET, glyphCount = 4))

        assertEquals(CffCharset.Explicit(listOf(5, 6, 7)), charset)
    }

    @Test
    fun readsFormatTwoRanges() {
        val charset = success(CffCharsetReader.read(padded(2, 0, 100, 0, 1), EXPLICIT_OFFSET, glyphCount = 3))

        assertEquals(CffCharset.Explicit(listOf(100, 101)), charset)
    }

    @Test
    fun returnsPredefinedCharsetsForOffsetsZeroToTwo() {
        for (offset in 0..2) {
            assertEquals(CffCharset.Predefined(offset), success(CffCharsetReader.read(byteArrayOf(9), offset, glyphCount = 5)))
        }
    }

    @Test
    fun readsAnEmptyExplicitCharsetForANotdefOnlyFace() {
        val charset = success(CffCharsetReader.read(padded(0), EXPLICIT_OFFSET, glyphCount = 1))

        assertEquals(CffCharset.Explicit(emptyList()), charset)
    }

    @Test
    fun rejectsRangesThatExceedTheGlyphCount() {
        val result = CffCharsetReader.read(padded(1, 0, 5, 5), EXPLICIT_OFFSET, glyphCount = 3)

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsAnUnknownCharsetFormat() {
        val result = CffCharsetReader.read(padded(3), EXPLICIT_OFFSET, glyphCount = 3)

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsATruncatedFormatZeroCharset() {
        val result = CffCharsetReader.read(padded(0, 0, 10), EXPLICIT_OFFSET, glyphCount = 3)

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsAGlyphCountBelowOne() {
        val result = CffCharsetReader.read(padded(0), EXPLICIT_OFFSET, glyphCount = 0)

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    private fun padded(vararg tail: Int): ByteArray = byteArrayOf(0, 0, 0) + tail.map { it.toByte() }.toByteArray()

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value

    private companion object {
        const val EXPLICIT_OFFSET = 3
    }
}
