@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

class BrotliDecoderTest {
    @Test
    fun decodesAnEmptyStreamToAnEmptyArray() {
        assertContentEquals(
            ByteArray(0),
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.EMPTY, maxOutputBytes = 16, maxWorkingBytes = 16),
            ).value,
        )
    }

    @Test
    fun decodesTextSuccessfullyAndByteForByte() {
        assertContentEquals(
            BrotliVectors.TEXT_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.TEXT, maxOutputBytes = 1_024, maxWorkingBytes = 1_024),
            ).value,
        )
    }

    @Test
    fun decodesALiteralOnlyCompressedMetaBlock() {
        assertContentEquals(
            BrotliVectors.LITERAL_COMPRESSED_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.LITERAL_COMPRESSED, maxOutputBytes = 64, maxWorkingBytes = 64),
            ).value,
        )
    }

    @Test
    fun decodesAStreamThatUsesTheStaticDictionary() {
        assertContentEquals(
            BrotliVectors.DICTIONARY_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.DICTIONARY_USER, maxOutputBytes = 4_096, maxWorkingBytes = 4_096),
            ).value,
        )
    }

    @Test
    fun overExpansionPastTheIndependentOutputLimitFails() {
        val failure = assertIs<FontOperationResult.Failure>(
            BrotliDecoder.decode(BrotliVectors.MULTI_BLOCK, maxOutputBytes = 4, maxWorkingBytes = 1_024),
        )
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
    }

    @Test
    fun refusesATruncatedStream() {
        val truncated = BrotliVectors.TEXT.copyOf(BrotliVectors.TEXT.size / 2)
        assertIs<FontOperationResult.Failure>(
            BrotliDecoder.decode(truncated, maxOutputBytes = 1_024, maxWorkingBytes = 1_024),
        )
    }

    @Test
    fun decodesAMultiBlockStreamAcrossMetaBlockBoundaries() {
        assertContentEquals(
            BrotliVectors.MULTI_BLOCK_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.MULTI_BLOCK, maxOutputBytes = 64, maxWorkingBytes = 64),
            ).value,
        )
    }

    @Test
    fun skipsAMetadataMetaBlockAndEndsOnTheEmptyFinalBlock() {
        assertContentEquals(
            ByteArray(0),
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(
                    BrotliVectors.METADATA_THEN_LAST,
                    maxOutputBytes = 64,
                    maxWorkingBytes = 64,
                ),
            ).value,
        )
    }

    @Test
    fun decodesAHandBuiltDictionaryReference() {
        assertContentEquals(
            BrotliVectors.DICTIONARY_HAND_BUILT_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(
                    BrotliVectors.DICTIONARY_HAND_BUILT,
                    maxOutputBytes = 64,
                    maxWorkingBytes = 64,
                ),
            ).value,
        )
    }

    @Test
    fun decodesACompressedBlockFollowedByTheEmptyFinalBlock() {
        assertContentEquals(
            BrotliVectors.COMPRESSED_THEN_LAST_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.COMPRESSED_THEN_LAST, maxOutputBytes = 64, maxWorkingBytes = 64),
            ).value,
        )
    }

    @Test
    fun decodesAnOverlappingBackReference() {
        assertContentEquals(
            BrotliVectors.OVERLAPPING_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.OVERLAPPING, maxOutputBytes = 64, maxWorkingBytes = 64),
            ).value,
        )
    }

    @Test
    fun rejectsANonZeroMetadataFillBit() {
        assertIs<FontError.FontDataFailure>(
            assertIs<FontOperationResult.Failure>(
                BrotliDecoder.decode(BrotliVectors.METADATA_NONZERO_FILL, maxOutputBytes = 64, maxWorkingBytes = 64),
            ).error,
        )
    }

    @Test
    fun rejectsNonZeroIgnoredBitsInAnUncompressedMetaBlock() {
        assertIs<FontError.FontDataFailure>(
            assertIs<FontOperationResult.Failure>(
                BrotliDecoder.decode(
                    BrotliVectors.UNCOMPRESSED_NONZERO_FILL,
                    maxOutputBytes = 64,
                    maxWorkingBytes = 64,
                ),
            ).error,
        )
    }

    @Test
    fun rejectsABadBlockTypeDescription() {
        assertIs<FontError.FontDataFailure>(
            assertIs<FontOperationResult.Failure>(
                BrotliDecoder.decode(BrotliVectors.BAD_BLOCK_TYPE, maxOutputBytes = 64, maxWorkingBytes = 64),
            ).error,
        )
    }

    @Test
    fun rejectsTrailingBytesAfterTheFinalMetaBlock() {
        val withTrailingByte = BrotliVectors.TEXT + byteArrayOf(0)
        assertIs<FontError.FontDataFailure>(
            assertIs<FontOperationResult.Failure>(
                BrotliDecoder.decode(withTrailingByte, maxOutputBytes = 1_024, maxWorkingBytes = 1_024),
            ).error,
        )
    }

    @Test
    fun rejectsTheReservedWindowSizeEncoding() {
        assertIs<FontError.FontDataFailure>(
            assertIs<FontOperationResult.Failure>(
                BrotliDecoder.decode(BrotliVectors.RESERVED_WBITS, maxOutputBytes = 64, maxWorkingBytes = 64),
            ).error,
        )
    }

    @Test
    fun rejectsAnEmptyInput() {
        assertIs<FontError.FontDataFailure>(
            assertIs<FontOperationResult.Failure>(
                BrotliDecoder.decode(ByteArray(0), maxOutputBytes = 64, maxWorkingBytes = 64),
            ).error,
        )
    }

    @Test
    fun theWorkingSetLimitIsIndependentOfTheOutputLimit() {
        assertContentEquals(
            BrotliVectors.LONG_DISTANCE_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.LONG_DISTANCE, maxOutputBytes = 64, maxWorkingBytes = 64),
            ).value,
        )
        val failure = assertIs<FontOperationResult.Failure>(
            BrotliDecoder.decode(BrotliVectors.LONG_DISTANCE, maxOutputBytes = 64, maxWorkingBytes = 4),
        )
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
    }

    @Test
    fun aNegativeBoundIsRejectedAsAResourceLimitBreach() {
        assertIs<FontError.ResourceLimitExceeded>(
            assertIs<FontOperationResult.Failure>(
                BrotliDecoder.decode(BrotliVectors.EMPTY, maxOutputBytes = -1, maxWorkingBytes = 64),
            ).error,
        )
        assertIs<FontError.ResourceLimitExceeded>(
            assertIs<FontOperationResult.Failure>(
                BrotliDecoder.decode(BrotliVectors.EMPTY, maxOutputBytes = 64, maxWorkingBytes = -1),
            ).error,
        )
    }
}
