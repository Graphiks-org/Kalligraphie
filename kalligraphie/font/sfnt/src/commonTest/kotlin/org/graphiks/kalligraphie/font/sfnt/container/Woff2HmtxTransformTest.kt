@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

class Woff2HmtxTransformTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun flagsOneReconstructsProportionalBearingsFromXMin() {
        val v = Woff2HmtxVectors.FLAGS_ONE
        assertContentEquals(
            v.expected,
            assertIs<FontOperationResult.Success<ByteArray>>(
                Woff2HmtxTransform.reconstruct(v.transformed, numberOfHMetrics = 2, numGlyphs = 2, xMinByGlyph = intArrayOf(3, -4), limits),
            ).value,
        )
    }

    @Test
    fun flagsTwoReconstructsTheTrailingBearingsFromXMin() {
        val v = Woff2HmtxVectors.FLAGS_TWO
        assertContentEquals(
            v.expected,
            assertIs<FontOperationResult.Success<ByteArray>>(
                Woff2HmtxTransform.reconstruct(v.transformed, numberOfHMetrics = 1, numGlyphs = 3, xMinByGlyph = intArrayOf(0, 5, 0), limits),
            ).value,
        )
    }

    @Test
    fun flagsThreeOmitsBothArraysAndTheTrailingArrayIsEmptyWhenCountsMatch() {
        val v = Woff2HmtxVectors.FLAGS_THREE_FLAT
        val hmtx = assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2HmtxTransform.reconstruct(v.transformed, numberOfHMetrics = 2, numGlyphs = 2, xMinByGlyph = intArrayOf(3, -4), limits),
        ).value
        assertEquals(2 * 4, hmtx.size)
        assertContentEquals(v.expected, hmtx)
    }

    @Test
    fun flagsTwoReadsTheProportionalArrayWhenTheCountsMatch() {
        val v = Woff2HmtxVectors.FLAGS_TWO_EQUAL
        assertContentEquals(v.expected, reconstruct(v))
    }

    @Test
    fun flagsThreeDerivesBothArraysWhenTheCountsDiffer() {
        val v = Woff2HmtxVectors.FLAGS_THREE_UNEQUAL
        assertContentEquals(v.expected, reconstruct(v))
    }

    @Test
    fun flagsThreeUsesZeroForAnEmptyGlyph() {
        val v = Woff2HmtxVectors.FLAGS_THREE_UNEQUAL
        val hmtx = reconstruct(v)
        // Glyph 2 is empty (xMin 0); it is the first trailing bearing, at offsets 8 and 9.
        assertEquals(12, hmtx.size)
        assertEquals(0, hmtx[8].toInt())
        assertEquals(0, hmtx[9].toInt())
    }

    @Test
    fun flagsOneReadsTheTrailingArrayExplicitly() {
        val v = Woff2HmtxVectors.FLAGS_ONE_UNEQUAL
        assertContentEquals(v.expected, reconstruct(v))
    }

    @Test
    fun flagsTwoDerivesTheEmptyTrailingGlyphBearingAsZero() {
        val v = Woff2HmtxVectors.FLAGS_TWO
        val hmtx = reconstruct(v)
        assertEquals(8, hmtx.size)
        assertEquals(0, hmtx[6].toInt())
        assertEquals(0, hmtx[7].toInt())
    }

    @Test
    fun reconstructsRealFontToolsFlagsTwo() {
        val v = Woff2HmtxVectors.REAL_FLAGS_TWO
        assertContentEquals(Woff2HmtxVectors.REAL_REFERENCE_HMTX_FLAGS_TWO, reconstruct(v))
    }

    @Test
    fun reconstructsRealFontToolsFlagsThree() {
        val v = Woff2HmtxVectors.REAL_FLAGS_THREE
        assertContentEquals(Woff2HmtxVectors.REAL_REFERENCE_HMTX_FLAGS_THREE, reconstruct(v))
    }

    @Test
    fun rejectsFlagsWithNeitherArrayOmitted() {
        assertEquals(
            "font.woff2.transform-failed",
            failureCode(byteArrayOf(0x00), numberOfHMetrics = 2, numGlyphs = 2, xMins = intArrayOf(3, -4)),
        )
    }

    @Test
    fun rejectsReservedFlagBits() {
        val v = Woff2HmtxVectors.FLAGS_THREE_FLAT
        val mutated = v.transformed.copyOf().also { it[0] = 0x07 }
        assertEquals("font.woff2.transform-failed", failureCode(mutated, v.numberOfHMetrics, v.numGlyphs, v.xMinByGlyph))
    }

    @Test
    fun rejectsAnEmptyTransform() {
        assertEquals(
            "font.woff2.transform-failed",
            failureCode(ByteArray(0), numberOfHMetrics = 2, numGlyphs = 2, xMins = intArrayOf(3, -4)),
        )
    }

    @Test
    fun rejectsATruncatedAdvanceWidthArray() {
        val v = Woff2HmtxVectors.FLAGS_ONE
        val truncated = v.transformed.copyOf(v.transformed.size - 1)
        assertEquals("font.woff2.transform-failed", failureCode(truncated, v.numberOfHMetrics, v.numGlyphs, v.xMinByGlyph))
    }

    @Test
    fun rejectsTrailingBytesAfterTheDeclaredArrays() {
        val v = Woff2HmtxVectors.FLAGS_THREE_FLAT
        val padded = v.transformed + byteArrayOf(0, 0)
        assertEquals("font.woff2.transform-failed", failureCode(padded, v.numberOfHMetrics, v.numGlyphs, v.xMinByGlyph))
    }

    @Test
    fun rejectsNumberOfHMetricsAboveNumGlyphs() {
        assertEquals(
            "font.woff2.transform-failed",
            failureCode(Woff2HmtxVectors.FLAGS_ONE.transformed, numberOfHMetrics = 3, numGlyphs = 2, xMins = intArrayOf(3, -4)),
        )
    }

    @Test
    fun rejectsAZeroNumberOfHMetrics() {
        assertEquals(
            "font.woff2.transform-failed",
            failureCode(Woff2HmtxVectors.FLAGS_TWO.transformed, numberOfHMetrics = 0, numGlyphs = 3, xMins = intArrayOf(0, 5, 0)),
        )
    }

    @Test
    fun rejectsAnXMinArrayShorterThanNumGlyphs() {
        assertEquals(
            "font.woff2.transform-failed",
            failureCode(Woff2HmtxVectors.FLAGS_THREE_UNEQUAL.transformed, numberOfHMetrics = 2, numGlyphs = 4, xMins = intArrayOf(0)),
        )
    }

    @Test
    fun rejectsAReconstructionOverTheDecodedLimit() {
        val limited = WoffDecodeLimits(maxDecodedFontBytes = 4, maxWorkingBytes = 64L * 1024 * 1024)
        val v = Woff2HmtxVectors.FLAGS_THREE_UNEQUAL
        assertIs<FontError.ResourceLimitExceeded>(
            assertIs<FontOperationResult.Failure>(
                Woff2HmtxTransform.reconstruct(v.transformed, v.numberOfHMetrics, v.numGlyphs, v.xMinByGlyph, limited),
            ).error,
        )
    }

    @Test
    fun readerReconstructsARealTransformedGlyfAndHmtx() {
        val font = assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2Reader.decode(Woff2HmtxVectors.REAL_WOFF2_FLAGS_TWO, limits),
        ).value
        assertContentEquals(
            Woff2HmtxVectors.REAL_REFERENCE_HMTX_FLAGS_TWO,
            GlyfOutlineParser.sfntTable(font, "hmtx"),
        )
    }

    @Test
    fun readerReconstructsHmtxForANullTransformGlyf() {
        val font = assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2Reader.decode(Woff2HmtxVectors.REAL_WOFF2_NULL_GLYF_FLAGS_THREE, limits),
        ).value
        assertContentEquals(
            Woff2HmtxVectors.REAL_REFERENCE_HMTX_FLAGS_THREE,
            GlyfOutlineParser.sfntTable(font, "hmtx"),
        )
    }

    @Test
    fun readerRejectsAReconstructedHmtxWhoseSizeMismatchesTheDirectory() {
        assertEquals(
            "font.woff2.transform-failed",
            assertIs<FontOperationResult.Failure>(
                Woff2Reader.decode(Woff2HmtxVectors.REAL_WOFF2_BAD_HMTX_LENGTH, limits),
            ).error.code,
        )
    }

    private fun reconstruct(v: Woff2HmtxVector): ByteArray =
        assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2HmtxTransform.reconstruct(v.transformed, v.numberOfHMetrics, v.numGlyphs, v.xMinByGlyph, limits),
        ).value

    private fun failureCode(
        transformed: ByteArray,
        numberOfHMetrics: Int,
        numGlyphs: Int,
        xMins: IntArray,
    ): String = assertIs<FontOperationResult.Failure>(
        Woff2HmtxTransform.reconstruct(transformed, numberOfHMetrics, numGlyphs, xMins, limits),
    ).error.code
}
