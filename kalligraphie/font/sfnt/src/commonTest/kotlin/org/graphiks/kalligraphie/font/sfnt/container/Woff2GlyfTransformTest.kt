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
