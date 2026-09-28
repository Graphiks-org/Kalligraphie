@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

class Woff2GlyfTransformTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun reconstructsASimpleOneGlyphWithShortLoca() {
        val v = Woff2GlyfVectors.SIMPLE_SHORT_LOCA
        val r = assertIs<FontOperationResult.Success<GlyfReconstruction>>(
            Woff2GlyfTransform.reconstruct(v.transformedGlyf, limits),
        ).value
        assertEquals(0, r.indexFormat)
        assertContentEquals(v.expectedLoca, r.loca)
        assertContentEquals(v.expectedGlyf, r.glyf)
        assertEquals(1, r.numGlyphs)
        assertEquals(10, r.glyphXMin(0))
    }

    @Test
    fun reconstructsACompositeWithLongLocaAndAnEmptyGlyph() {
        val v = Woff2GlyfVectors.COMPOSITE_LONG_LOCA
        val r = assertIs<FontOperationResult.Success<GlyfReconstruction>>(
            Woff2GlyfTransform.reconstruct(v.transformedGlyf, limits),
        ).value
        assertEquals(1, r.indexFormat)
        assertContentEquals(v.expectedLoca, r.loca)
        assertContentEquals(v.expectedGlyf, r.glyf)
        assertEquals(2, r.numGlyphs)
    }

    @Test
    fun reconstructsAMixedOverlapBitmap() {
        val v = Woff2GlyfVectors.MIXED_OVERLAP
        assertContentEquals(
            v.expectedGlyf,
            assertIs<FontOperationResult.Success<GlyfReconstruction>>(
                Woff2GlyfTransform.reconstruct(v.transformedGlyf, limits),
            ).value.glyf,
        )
    }

    @Test
    fun reconstructsAnOddLengthGlyphFollowedByAnother() {
        val v = Woff2GlyfVectors.ODD_LENGTH_THEN_SECOND
        val r = assertIs<FontOperationResult.Success<GlyfReconstruction>>(
            Woff2GlyfTransform.reconstruct(v.transformedGlyf, limits),
        ).value
        assertContentEquals(v.expectedLoca, r.loca)
        assertEquals(0, r.loca[2].toInt()) // second glyph starts on an even offset
    }

    @Test
    fun reconstructsARealFontToolsVectorSemantically() {
        val reconstruction = assertIs<FontOperationResult.Success<GlyfReconstruction>>(
            Woff2GlyfTransform.reconstruct(Woff2GlyfVectors.REAL_TRANSFORMED, limits),
        ).value
        val expected = GlyfOutlineParser.parse(
            Woff2GlyfVectors.REAL_REFERENCE_GLYF,
            Woff2GlyfVectors.REAL_REFERENCE_LOCA,
            Woff2GlyfVectors.REAL_INDEX_FORMAT,
        )
        val actual = GlyfOutlineParser.parse(reconstruction.glyf, reconstruction.loca, reconstruction.indexFormat)
        assertEquals(expected, actual)
        assertEquals(Woff2GlyfVectors.REAL_INDEX_FORMAT, reconstruction.indexFormat)
    }

    @Test
    fun decodesAllOneHundredTwentyEightTripletCodes() {
        val reconstruction = assertIs<FontOperationResult.Success<GlyfReconstruction>>(
            Woff2GlyfTransform.reconstruct(Woff2GlyfVectors.ALL_TRIPLET_CODES_TRANSFORMED, limits),
        ).value
        val glyphs = GlyfOutlineParser.parse(reconstruction.glyf, reconstruction.loca, reconstruction.indexFormat)
        assertEquals(128, glyphs.size)
        for (code in 0 until 128) {
            val simple = assertIs<SimpleGlyph>(glyphs[code])
            assertEquals(
                listOf(Point(Woff2GlyfVectors.ALL_TRIPLET_DELTAS[code * 2], Woff2GlyfVectors.ALL_TRIPLET_DELTAS[code * 2 + 1], true)),
                simple.points,
            )
        }
    }

    @Test
    fun rejectsANonZeroReservedField() {
        val mutated = Woff2GlyfVectors.SIMPLE_SHORT_LOCA.transformedGlyf.copyOf().also {
            it[0] = 0x12
        }
        assertEquals("font.woff2.transform-failed", failureCode(mutated, limits))
    }

    @Test
    fun rejectsAnUnknownIndexFormat() {
        val mutated = Woff2GlyfVectors.SIMPLE_SHORT_LOCA.transformedGlyf.copyOf().also {
            it[7] = 0x02
        }
        assertEquals("font.woff2.transform-failed", failureCode(mutated, limits))
    }

    @Test
    fun rejectsAnExplicitBoundingBoxOnAnEmptyGlyph() {
        // The composite/long-loca vector's second glyph is empty. Its bbox bit lives in the first
        // byte of the bbox stream (header 36 + nContour 4 + composite 6 = 46).
        val mutated = Woff2GlyfVectors.COMPOSITE_LONG_LOCA.transformedGlyf.copyOf().also {
            it[46] = (it[46].toInt() or 0x40).toByte()
        }
        assertEquals("font.woff2.transform-failed", failureCode(mutated, limits))
    }

    @Test
    fun rejectsACompositeWithoutAnExplicitBoundingBox() {
        val mutated = Woff2GlyfVectors.COMPOSITE_LONG_LOCA.transformedGlyf.copyOf().also {
            it[46] = (it[46].toInt() and 0x7F).toByte()
        }
        assertEquals("font.woff2.transform-failed", failureCode(mutated, limits))
    }

    @Test
    fun rejectsATruncatedTransform() {
        val truncated = Woff2GlyfVectors.SIMPLE_SHORT_LOCA.transformedGlyf.copyOf(
            Woff2GlyfVectors.SIMPLE_SHORT_LOCA.transformedGlyf.size - 1,
        )
        assertEquals("font.woff2.transform-failed", failureCode(truncated, limits))
    }

    @Test
    fun rejectsAStreamOverTheWorkingLimit() {
        val limited = WoffDecodeLimits(maxDecodedFontBytes = 64L * 1024 * 1024, maxWorkingBytes = 1)
        assertIs<FontError.ResourceLimitExceeded>(
            assertIs<FontOperationResult.Failure>(
                Woff2GlyfTransform.reconstruct(Woff2GlyfVectors.SIMPLE_SHORT_LOCA.transformedGlyf, limited),
            ).error,
        )
    }

    @Test
    fun rejectsAReconstructionOverTheDecodedLimit() {
        val limited = WoffDecodeLimits(maxDecodedFontBytes = 4, maxWorkingBytes = 64L * 1024 * 1024)
        assertIs<FontError.ResourceLimitExceeded>(
            assertIs<FontOperationResult.Failure>(
                Woff2GlyfTransform.reconstruct(Woff2GlyfVectors.SIMPLE_SHORT_LOCA.transformedGlyf, limited),
            ).error,
        )
    }

    @Test
    fun rejectsAPointCountOverTheLiveMemoryBound() {
        // SIMPLE_SHORT_LOCA declares one simple glyph with three points; each point is charged at the
        // documented BYTES_PER_POINT (16), so a 47-byte working limit fits every stream but not the
        // per-point arrays, which alone need 48 live bytes.
        val limited = WoffDecodeLimits(maxDecodedFontBytes = 64L * 1024 * 1024, maxWorkingBytes = 47)
        assertIs<FontError.ResourceLimitExceeded>(
            assertIs<FontOperationResult.Failure>(
                Woff2GlyfTransform.reconstruct(Woff2GlyfVectors.SIMPLE_SHORT_LOCA.transformedGlyf, limited),
            ).error,
        )
    }

    @Test
    fun rejectsAnOutOfRangeCoordinateDelta() {
        // A one-point simple glyph whose single triplet decodes to deltaX = +65535, which cannot be
        // represented in the two-byte TrueType coordinate encoding.
        assertEquals("font.woff2.transform-failed", failureCode(outOfRangeDeltaTransform(), limits))
    }

    /**
     * One transformed simple glyph with one contour and one point whose triplet code 127 encodes
     * `deltaX = 0xFFFF` (`+65535`), out of the signed 16-bit coordinate range.
     */
    private fun outOfRangeDeltaTransform(): ByteArray {
        val bytes = ArrayList<Byte>(49)
        fun u16(value: Int) { bytes += (value ushr 8 and 0xFF).toByte(); bytes += (value and 0xFF).toByte() }
        fun u32(value: Long) {
            bytes += (value ushr 24 and 0xFF).toByte(); bytes += (value ushr 16 and 0xFF).toByte()
            bytes += (value ushr 8 and 0xFF).toByte(); bytes += (value and 0xFF).toByte()
        }
        u16(0) // reserved
        u16(0) // optionFlags
        u16(1) // numGlyphs
        u16(0) // indexFormat
        u32(2) // nContour stream (one Int16)
        u32(1) // nPoints stream (one 255UInt16)
        u32(1) // flag stream
        u32(5) // glyph stream (four triplet bytes plus one instruction length)
        u32(0) // composite stream
        u32(4) // bbox stream (bitmap only)
        u32(0) // instruction stream
        u16(1) // numberOfContours
        bytes += 1 // one point
        bytes += 0x7F // triplet code 127: four coordinate bytes, on-curve
        bytes += 0xFF.toByte(); bytes += 0xFF.toByte() // deltaX = +65535
        bytes += 0x00; bytes += 0x00 // deltaY = 0
        bytes += 0x00 // instructionLength = 0
        bytes += 0x00; bytes += 0x00; bytes += 0x00; bytes += 0x00 // bboxBitmap
        return bytes.toByteArray()
    }

    @Test
    fun readerReconstructsATransformedGlyfAndLoca() {
        val font = assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2Reader.decode(Woff2GlyfVectors.WOFF2_VALID, limits),
        ).value
        val glyf = GlyfOutlineParser.sfntTable(font, "glyf")!!
        val loca = GlyfOutlineParser.sfntTable(font, "loca")!!
        val expected = GlyfOutlineParser.parse(
            Woff2GlyfVectors.REAL_REFERENCE_GLYF,
            Woff2GlyfVectors.REAL_REFERENCE_LOCA,
            Woff2GlyfVectors.REAL_INDEX_FORMAT,
        )
        assertEquals(expected, GlyfOutlineParser.parse(glyf, loca, Woff2GlyfVectors.REAL_INDEX_FORMAT))
    }

    @Test
    fun readerRejectsALocaOriginalSizeThatDoesNotMatchTheGlyphCount() {
        assertEquals(
            "font.woff2.transform-failed",
            assertIs<FontOperationResult.Failure>(
                Woff2Reader.decode(Woff2GlyfVectors.WOFF2_BAD_LOCA_LENGTH, limits),
            ).error.code,
        )
    }

    @Test
    fun readerRejectsATransformedLocaWhoseTransformModeDisagreesWithGlyf() {
        assertEquals(
            "font.woff2.transform-failed",
            assertIs<FontOperationResult.Failure>(
                Woff2Reader.decode(Woff2GlyfVectors.WOFF2_UNPAIRED_LOCA, limits),
            ).error.code,
        )
    }

    @Test
    fun readerRejectsATransformedGlyfWithoutALoca() {
        assertEquals(
            "font.woff2.transform-failed",
            assertIs<FontOperationResult.Failure>(
                Woff2Reader.decode(Woff2GlyfVectors.WOFF2_NO_LOCA, limits),
            ).error.code,
        )
    }

    @Test
    fun readerRejectsATransformedLocaWithoutATransformedGlyf() {
        assertEquals(
            "font.woff2.transform-failed",
            assertIs<FontOperationResult.Failure>(
                Woff2Reader.decode(Woff2GlyfVectors.WOFF2_GLYF_UNTRANSFORMED, limits),
            ).error.code,
        )
    }

    private fun failureCode(transformed: ByteArray, limits: WoffDecodeLimits): String =
        assertIs<FontOperationResult.Failure>(Woff2GlyfTransform.reconstruct(transformed, limits)).error.code
}
