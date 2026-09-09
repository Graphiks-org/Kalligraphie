package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.SourceBias
import org.graphiks.kalligraphie.api.SourceEncoding
import org.graphiks.kalligraphie.api.SourceIndexResult
import org.graphiks.kalligraphie.api.SourceOffset
import org.graphiks.kalligraphie.api.SourceRange
import org.graphiks.kalligraphie.api.TextDecodingFailure
import org.graphiks.kalligraphie.api.TextDecodingLimit
import org.graphiks.kalligraphie.api.TextDecodingOutcome
import org.graphiks.kalligraphie.api.TextDecodingProfile
import org.graphiks.kalligraphie.api.TextDecodingResult
import org.graphiks.kalligraphie.api.TextDiagnostic
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.Utf16Storage
import org.graphiks.kalligraphie.api.Utf8Storage

class CanonicalTextDecoderTest {
    @Test
    fun editor_source_and_scalar_limits_reject_atomically_then_exact_retry_succeeds() {
        val malformed = byteArrayOf(
            0x61,
            0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte(),
            0xE2.toByte(), 0x82.toByte(), 0x41,
        )
        val slices = listOf(
            TextSlice.Utf8(malformed.copyOfRange(0, 5)),
            TextSlice.Utf8(malformed.copyOfRange(5, malformed.size)),
        )

        val sourceLimited = TextSnapshots.decodeUtf8(
            TextVersion.create(),
            slices,
            EditorOperationProfile(maxSourceUnits = 7).textDecodingProfile,
        )
        val sourceFailure = assertIs<TextDecodingOutcome.LimitExceeded>(sourceLimited)
        assertEquals(TextDecodingLimit.SOURCE_UNITS, sourceFailure.limit)
        assertEquals(8L, sourceFailure.observed)

        val scalarLimited = TextSnapshots.decodeUtf8(
            TextVersion.create(),
            slices,
            EditorOperationProfile(maxAnalyzedScalars = 3).textDecodingProfile,
        )
        val scalarFailure = assertIs<TextDecodingOutcome.LimitExceeded>(scalarLimited)
        assertEquals(TextDecodingLimit.SCALARS, scalarFailure.limit)
        assertEquals(4L, scalarFailure.observed)

        val retry = assertIs<TextDecodingOutcome.Success>(
            TextSnapshots.decodeUtf8(
                TextVersion.create(),
                slices,
                EditorOperationProfile.unbounded.textDecodingProfile,
            ),
        ).value
        assertEquals(listOf(0x61, 0x1F600, 0xFFFD, 0x41), retry.snapshot.scalars)
        assertEquals(listOf("text.malformed-utf8"), retry.diagnostics.map(TextDiagnostic::code))
        assertEquals(
            listOf(0 to 1, 1 to 5, 5 to 7, 7 to 8),
            retry.snapshot.sourceRanges.map { it.start.value to it.endExclusive.value },
        )
    }

    @Test
    fun utf8_and_utf16_produce_the_same_scalars_and_boundaries() {
        val version = TextVersion.create()
        val utf8 = TextSnapshots.decodeUtf8(
            version,
            listOf(TextSlice.Utf8(byteArrayOf(0x41, 0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte(), 0x42))),
        )
        val utf16 = TextSnapshots.decodeUtf16(
            version,
            listOf(TextSlice.Utf16(charArrayOf('A', '\uD83D', '\uDE00', 'B'))),
        )

        assertEquals(listOf(0x41, 0x1F600, 0x42), utf8.snapshot.scalars)
        assertEquals(utf8.snapshot.scalars, utf16.snapshot.scalars)
        assertEquals(SourceEncoding.UTF8, utf8.snapshot.sourceEncoding)
        assertEquals(SourceEncoding.UTF16, utf16.snapshot.sourceEncoding)
        assertEquals(
            listOf(
                sourceRange(version, SourceEncoding.UTF8, 0, 1),
                sourceRange(version, SourceEncoding.UTF8, 1, 5),
                sourceRange(version, SourceEncoding.UTF8, 5, 6),
            ),
            utf8.snapshot.sourceRanges,
        )
        assertEquals(
            listOf(
                sourceRange(version, SourceEncoding.UTF16, 0, 1),
                sourceRange(version, SourceEncoding.UTF16, 1, 3),
                sourceRange(version, SourceEncoding.UTF16, 3, 4),
            ),
            utf16.snapshot.sourceRanges,
        )
        assertEquals(sourceOffset(version, SourceEncoding.UTF8, 0), utf8.snapshot.textIndexToSource(utf8.snapshot.range.start))
        assertEquals(sourceOffset(version, SourceEncoding.UTF8, 6), utf8.snapshot.textIndexToSource(utf8.snapshot.range.endExclusive))
        assertEquals(sourceOffset(version, SourceEncoding.UTF16, 0), utf16.snapshot.textIndexToSource(utf16.snapshot.range.start))
        assertEquals(sourceOffset(version, SourceEncoding.UTF16, 4), utf16.snapshot.textIndexToSource(utf16.snapshot.range.endExclusive))

        val emojiIndex = utf8.snapshot.sourceToTextIndex(
            sourceOffset(version, SourceEncoding.UTF8, 1),
            SourceBias.BEFORE,
        ).index
        assertEquals(sourceOffset(version, SourceEncoding.UTF16, 1), utf16.snapshot.textIndexToSource(emojiIndex))
        val crossViewRange = TextRange(emojiIndex, utf16.snapshot.range.endExclusive)
        assertEquals(emojiIndex, crossViewRange.start)
        assertEquals(utf16.snapshot.range.endExclusive, crossViewRange.endExclusive)
        assertEquals(sourceOffset(version, SourceEncoding.UTF8, 0), utf8.snapshot.textIndexToSource(utf16.snapshot.range.start))

        val utf8Interior = utf8.snapshot.sourceToTextIndex(sourceOffset(version, SourceEncoding.UTF8, 3), SourceBias.BEFORE)
        val utf8Biased = assertIs<SourceIndexResult.Biased>(utf8Interior)
        assertEquals(sourceRange(version, SourceEncoding.UTF8, 1, 5), utf8Biased.containingRange)
        assertEquals(sourceOffset(version, SourceEncoding.UTF8, 1), utf8.snapshot.textIndexToSource(utf8Biased.index))

        assertFailsWith<IllegalArgumentException> {
            utf16.snapshot.sourceToTextIndex(sourceOffset(version, SourceEncoding.UTF8, 1), SourceBias.BEFORE)
        }
        assertFailsWith<IllegalArgumentException> {
            utf16.snapshot.sourceToTextIndex(sourceOffset(TextVersion.create(), SourceEncoding.UTF16, 0), SourceBias.BEFORE)
        }
        assertFailsWith<IllegalArgumentException> {
            utf8.snapshot.sourceToTextIndex(
                sourceOffset(TextVersion.create(), SourceEncoding.UTF8, 0),
                SourceBias.BEFORE,
            )
        }
    }

    @Test
    fun scalar_boundary_factory_creates_version_compatible_indices() {
        val version = TextVersion.create()
        val utf8 = TextSnapshots.decodeUtf8(
            version,
            listOf(TextSlice.Utf8(byteArrayOf(0x41, 0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte(), 0x42))),
        )
        val utf16 = TextSnapshots.decodeUtf16(
            version,
            listOf(TextSlice.Utf16(charArrayOf('A', '\uD83D', '\uDE00', 'B'))),
        )

        val trailingBoundary = utf8.snapshot.textIndexAtScalarBoundary(2)
        assertEquals(sourceOffset(version, SourceEncoding.UTF16, 3), utf16.snapshot.textIndexToSource(trailingBoundary))
        assertFailsWith<IllegalArgumentException> {
            utf8.snapshot.textIndexAtScalarBoundary(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            utf8.snapshot.textIndexAtScalarBoundary(4)
        }
    }

    @Test
    fun owned_array_slices_detach_from_caller_mutations() {
        val version = TextVersion.create()
        val utf8CallerBuffer = byteArrayOf(0x41, 0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte(), 0x42)
        val utf16CallerBuffer = charArrayOf('A', '\uD83D', '\uDE00', 'B')
        val ownedUtf8 = TextSlice.Utf8(utf8CallerBuffer)
        val ownedUtf16 = TextSlice.Utf16(utf16CallerBuffer)
        utf8CallerBuffer.fill(0)
        utf16CallerBuffer.fill('\u0000')

        val ownedUtf8Result = TextSnapshots.decodeUtf8(version, listOf(ownedUtf8))
        val ownedUtf16Result = TextSnapshots.decodeUtf16(version, listOf(ownedUtf16))
        assertEquals(listOf(0x41, 0x1F600, 0x42), ownedUtf8Result.snapshot.scalars)
        assertEquals(ownedUtf8Result.snapshot.scalars, ownedUtf16Result.snapshot.scalars)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (ownedUtf8Result.snapshot.scalars as MutableList<Int>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (ownedUtf8Result.snapshot.sourceRanges as MutableList<SourceRange>).clear()
        }

    }

    @Test
    fun slice_seams_between_complete_unicode_units_preserve_the_snapshot() {
        val version = TextVersion.create()
        val utf8Unsplit = TextSnapshots.decodeUtf8(
            version,
            listOf(TextSlice.Utf8(byteArrayOf(0x41, 0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte(), 0x42))),
        )
        val utf8Split = TextSnapshots.decodeUtf8(
            version,
            listOf(
                TextSlice.Utf8(byteArrayOf(0x41)),
                TextSlice.Utf8(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte())),
                TextSlice.Utf8(byteArrayOf(0x42)),
            ),
        )
        val utf16Unsplit = TextSnapshots.decodeUtf16(
            version,
            listOf(TextSlice.Utf16(charArrayOf('A', '\uD83D', '\uDE00', 'B'))),
        )
        val utf16Split = TextSnapshots.decodeUtf16(
            version,
            listOf(
                TextSlice.Utf16(charArrayOf('A')),
                TextSlice.Utf16(charArrayOf('\uD83D', '\uDE00')),
                TextSlice.Utf16(charArrayOf('B')),
            ),
        )

        assertPartitionInvariant(utf8Unsplit, utf8Split, SourceEncoding.UTF8, sourceLength = 6)
        assertPartitionInvariant(utf16Unsplit, utf16Split, SourceEncoding.UTF16, sourceLength = 4)
    }

    @Test
    fun fragmented_borrowed_editor_text_has_encoding_independent_exact_semantics() {
        val version = TextVersion.create()
        val utf8Storage = ImmutableUtf8RopeStorage(
            listOf(
                byteArrayOf(0x41, 0xC3.toByte()),
                byteArrayOf(0xA9.toByte(), 0x20, 0xF0.toByte(), 0x9F.toByte(), 0x98.toByte()),
                byteArrayOf(
                    0x80.toByte(),
                    0x20,
                    0xD7.toByte(),
                    0x90.toByte(),
                    0xE2.toByte(),
                    0x82.toByte(),
                    0x5A,
                ),
            ),
        )
        val original = charArrayOf('A', '\u00E9', ' ', '\uD83D', '\uDE00', 'Z')
        val added = charArrayOf(' ', '\u05D0', '\uD83D')
        val utf16Storage = ImmutableUtf16PieceTableStorage(
            buffers = listOf(original, added),
            pieces = listOf(
                Piece(buffer = 0, start = 0, endExclusive = 5),
                Piece(buffer = 1, start = 0, endExclusive = 3),
                Piece(buffer = 0, start = 5, endExclusive = 6),
            ),
        )

        val utf8 = TextSnapshots.decodeUtf8(
            version,
            listOf(
                TextSlice.Utf8.borrow(utf8Storage, 0, 4),
                TextSlice.Utf8.borrow(utf8Storage, 4, 11),
                TextSlice.Utf8.borrow(utf8Storage, 11, 14),
            ),
        )
        val utf16 = TextSnapshots.decodeUtf16(
            version,
            listOf(
                TextSlice.Utf16.borrow(utf16Storage, 0, 3),
                TextSlice.Utf16.borrow(utf16Storage, 3, 7),
                TextSlice.Utf16.borrow(utf16Storage, 7, 9),
            ),
        )

        val expectedScalars = listOf(0x41, 0xE9, 0x20, 0x1F600, 0x20, 0x5D0, 0xFFFD, 0x5A)
        assertEquals(expectedScalars, utf8.snapshot.scalars)
        assertEquals(expectedScalars, utf16.snapshot.scalars)
        assertEquals(
            listOf(
                sourceRange(version, SourceEncoding.UTF8, 0, 1),
                sourceRange(version, SourceEncoding.UTF8, 1, 3),
                sourceRange(version, SourceEncoding.UTF8, 3, 4),
                sourceRange(version, SourceEncoding.UTF8, 4, 8),
                sourceRange(version, SourceEncoding.UTF8, 8, 9),
                sourceRange(version, SourceEncoding.UTF8, 9, 11),
                sourceRange(version, SourceEncoding.UTF8, 11, 13),
                sourceRange(version, SourceEncoding.UTF8, 13, 14),
            ),
            utf8.snapshot.sourceRanges,
        )
        assertEquals(
            listOf(
                sourceRange(version, SourceEncoding.UTF16, 0, 1),
                sourceRange(version, SourceEncoding.UTF16, 1, 2),
                sourceRange(version, SourceEncoding.UTF16, 2, 3),
                sourceRange(version, SourceEncoding.UTF16, 3, 5),
                sourceRange(version, SourceEncoding.UTF16, 5, 6),
                sourceRange(version, SourceEncoding.UTF16, 6, 7),
                sourceRange(version, SourceEncoding.UTF16, 7, 8),
                sourceRange(version, SourceEncoding.UTF16, 8, 9),
            ),
            utf16.snapshot.sourceRanges,
        )
        assertEquals(
            listOf(
                TextDiagnostic(
                    code = "text.malformed-utf8",
                    sourceRange = sourceRange(version, SourceEncoding.UTF8, 11, 13),
                    message = "Malformed UTF-8 maximal subpart was replaced with U+FFFD.",
                ),
            ),
            utf8.diagnostics,
        )
        assertEquals(
            listOf(
                TextDiagnostic(
                    code = "text.malformed-utf16",
                    sourceRange = sourceRange(version, SourceEncoding.UTF16, 7, 8),
                    message = "Unpaired UTF-16 surrogate was replaced with U+FFFD.",
                ),
            ),
            utf16.diagnostics,
        )

        assertBiasedMapping(utf8, sourceOffset(version, SourceEncoding.UTF8, 6), 4, 8)
        assertBiasedMapping(utf8, sourceOffset(version, SourceEncoding.UTF8, 12), 11, 13)
        assertBiasedMapping(utf16, sourceOffset(version, SourceEncoding.UTF16, 4), 3, 5)
    }

    @Test
    fun slice_seams_inside_unicode_decoding_units_fail_atomically() {
        val utf8Cases = listOf(
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte()) to listOf(1, 2, 3),
            byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0x41) to listOf(1),
            byteArrayOf(0xF0.toByte(), 0x90.toByte(), 0x80.toByte(), 0x41) to listOf(1, 2),
        )
        utf8Cases.forEach { (bytes, invalidBoundaries) ->
            invalidBoundaries.forEach { boundary ->
                val result = TextSnapshots.decodeUtf8(
                    TextVersion.create(),
                    listOf(
                        TextSlice.Utf8(bytes.copyOfRange(0, boundary)),
                        TextSlice.Utf8(bytes.copyOfRange(boundary, bytes.size)),
                    ),
                    TextDecodingProfile.unbounded,
                )

                val failure = assertIs<TextDecodingOutcome.Failure>(result)
                assertEquals(TextDecodingFailure.INVALID_SLICE_BOUNDARY, failure.reason)
            }
        }

        val utf16 = TextSnapshots.decodeUtf16(
            TextVersion.create(),
            listOf(
                TextSlice.Utf16(charArrayOf('\uD83D')),
                TextSlice.Utf16(charArrayOf('\uDE00')),
            ),
            TextDecodingProfile.unbounded,
        )
        val failure = assertIs<TextDecodingOutcome.Failure>(utf16)
        assertEquals(TextDecodingFailure.INVALID_SLICE_BOUNDARY, failure.reason)
    }

    @Test
    fun borrowed_storage_is_not_needed_after_decode_returns() {
        val version = TextVersion.create()
        val decoded = decodeThenReleaseBorrowedUtf8(version)

        assertEquals(listOf(0x41, 0x1F600, 0x42), decoded.snapshot.scalars)
        assertEquals(
            sourceRange(version, SourceEncoding.UTF8, 1, 5),
            decoded.snapshot.sourceRange(decoded.snapshot.textIndexAtScalarBoundary(1)),
        )
    }

    @Test
    fun cancellation_after_substantial_fragmented_traversal_is_atomic_and_retry_is_exact() {
        val bytes = ByteArray(8_192) { 0x61 }
        val signal = TraversalCancellationSignal()
        val storage = CancellingUtf8RopeStorage(
            delegate = ImmutableUtf8RopeStorage(bytes.asList().chunked(73).map { it.toByteArray() }),
            cancelAtSourceIndex = 4_096,
            signal = signal,
        )
        val slices = (0 until bytes.size step 256).map { start ->
            TextSlice.Utf8.borrow(storage, start, minOf(start + 256, bytes.size))
        }

        val cancelled = TextSnapshots.decodeUtf8(
            TextVersion.create(),
            slices,
            TextDecodingProfile(cancellationCheckInterval = 32),
            CancellationToken { signal.cancelled },
        )
        assertIs<TextDecodingOutcome.Cancelled>(cancelled)
        assertTrue(signal.cancelled)

        val retry = TextSnapshots.decodeUtf8(
            TextVersion.create(),
            slices,
            TextDecodingProfile(cancellationCheckInterval = 32),
            CancellationToken.none,
        )
        val success = assertIs<TextDecodingOutcome.Success>(retry).value
        assertEquals(8_192, success.snapshot.scalars.size)
        assertTrue(success.snapshot.scalars.all { it == 0x61 })
        assertEquals(emptyList(), success.diagnostics)
        success.snapshot.sourceRanges.forEachIndexed { index, range ->
            assertEquals(
                sourceRange(success.snapshot.version, SourceEncoding.UTF8, index, index + 1),
                range,
            )
        }
        assertEquals(
            sourceRange(success.snapshot.version, SourceEncoding.UTF8, 8_191, 8_192),
            success.snapshot.sourceRange(success.snapshot.textIndexAtScalarBoundary(8_191)),
        )
    }

    @Test
    fun malformed_utf8_and_utf16_emit_one_replacement_per_maximal_subpart() {
        val version = TextVersion.create()
        val utf8Unsplit = TextSnapshots.decodeUtf8(
            version,
            listOf(TextSlice.Utf8(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0x41, 0x80.toByte()))),
        )
        val utf8Split = TextSnapshots.decodeUtf8(
            version,
            listOf(
                TextSlice.Utf8(byteArrayOf(0xE2.toByte(), 0x82.toByte())),
                TextSlice.Utf8(byteArrayOf(0x41, 0x80.toByte())),
            ),
        )
        val utf16Unsplit = TextSnapshots.decodeUtf16(
            version,
            listOf(TextSlice.Utf16(charArrayOf('\uD83D', 'A', '\uDE00'))),
        )
        val utf16Split = TextSnapshots.decodeUtf16(
            version,
            listOf(
                TextSlice.Utf16(charArrayOf('\uD83D')),
                TextSlice.Utf16(charArrayOf('A', '\uDE00')),
            ),
        )

        assertEquals(listOf(0xFFFD, 0x41, 0xFFFD), utf8Unsplit.snapshot.scalars)
        assertEquals(utf8Unsplit.snapshot.scalars, utf16Unsplit.snapshot.scalars)
        assertEquals(
            listOf(
                sourceRange(version, SourceEncoding.UTF8, 0, 2),
                sourceRange(version, SourceEncoding.UTF8, 3, 4),
            ),
            utf8Unsplit.diagnostics.map { it.sourceRange },
        )
        assertEquals(
            listOf(
                sourceRange(version, SourceEncoding.UTF16, 0, 1),
                sourceRange(version, SourceEncoding.UTF16, 2, 3),
            ),
            utf16Unsplit.diagnostics.map { it.sourceRange },
        )

        assertPartitionInvariant(utf8Unsplit, utf8Split, SourceEncoding.UTF8, sourceLength = 4)
        assertPartitionInvariant(utf16Unsplit, utf16Split, SourceEncoding.UTF16, sourceLength = 3)
    }

    @Test
    fun decoding_result_rejects_foreign_and_out_of_snapshot_diagnostics() {
        val version = TextVersion.create()
        val decoded = TextSnapshots.decodeUtf8(version, listOf(TextSlice.Utf8(byteArrayOf(0x41))))

        assertFailsWith<IllegalArgumentException> {
            TextDecodingResult(
                snapshot = decoded.snapshot,
                diagnostics = listOf(
                    TextDiagnostic(
                        code = "text.malformed-utf8",
                        sourceRange = sourceRange(TextVersion.create(), SourceEncoding.UTF8, 0, 1),
                        message = "foreign diagnostic",
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TextDecodingResult(
                snapshot = decoded.snapshot,
                diagnostics = listOf(
                    TextDiagnostic(
                        code = "text.malformed-utf8",
                        sourceRange = sourceRange(version, SourceEncoding.UTF16, 0, 1),
                        message = "cross-encoding diagnostic",
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TextDecodingResult(
                snapshot = decoded.snapshot,
                diagnostics = listOf(
                    TextDiagnostic(
                        code = "text.malformed-utf8",
                        sourceRange = sourceRange(version, SourceEncoding.UTF8, 0, 2),
                        message = "out-of-snapshot diagnostic",
                    ),
                ),
            )
        }
    }

    private fun assertPartitionInvariant(
        unsplit: TextDecodingResult,
        split: TextDecodingResult,
        sourceEncoding: SourceEncoding,
        sourceLength: Int,
    ) {
        assertEquals(unsplit.snapshot.scalars, split.snapshot.scalars)
        assertEquals(unsplit.snapshot.sourceRanges, split.snapshot.sourceRanges)
        assertEquals(unsplit.diagnostics, split.diagnostics)

        for (sourceValue in 0..sourceLength) {
            val offset = sourceOffset(unsplit.snapshot.version, sourceEncoding, sourceValue)
            for (bias in listOf(SourceBias.BEFORE, SourceBias.AFTER)) {
                val unsplitResult = unsplit.snapshot.sourceToTextIndex(offset, bias)
                val splitResult = split.snapshot.sourceToTextIndex(offset, bias)

                assertEquals(unsplitResult::class, splitResult::class)
                assertEquals(
                    unsplit.snapshot.textIndexToSource(unsplitResult.index),
                    split.snapshot.textIndexToSource(splitResult.index),
                )
                when (unsplitResult) {
                    is SourceIndexResult.Exact -> {
                        assertEquals(offset, unsplit.snapshot.textIndexToSource(unsplitResult.index))
                    }

                    is SourceIndexResult.Biased -> {
                        val splitBiased = assertIs<SourceIndexResult.Biased>(splitResult)
                        assertEquals(unsplitResult.containingRange, splitBiased.containingRange)
                        val expectedBoundary = when (bias) {
                            SourceBias.BEFORE -> unsplitResult.containingRange.start
                            SourceBias.AFTER -> unsplitResult.containingRange.endExclusive
                        }
                        assertEquals(expectedBoundary, unsplit.snapshot.textIndexToSource(unsplitResult.index))
                    }
                }
            }
        }
    }

    private fun assertBiasedMapping(
        decoded: TextDecodingResult,
        interiorOffset: SourceOffset,
        expectedStart: Int,
        expectedEndExclusive: Int,
    ) {
        val before = assertIs<SourceIndexResult.Biased>(
            decoded.snapshot.sourceToTextIndex(interiorOffset, SourceBias.BEFORE),
        )
        val after = assertIs<SourceIndexResult.Biased>(
            decoded.snapshot.sourceToTextIndex(interiorOffset, SourceBias.AFTER),
        )
        assertEquals(
            sourceOffset(decoded.snapshot.version, decoded.snapshot.sourceEncoding, expectedStart),
            decoded.snapshot.textIndexToSource(before.index),
        )
        assertEquals(
            sourceOffset(decoded.snapshot.version, decoded.snapshot.sourceEncoding, expectedEndExclusive),
            decoded.snapshot.textIndexToSource(after.index),
        )
        assertEquals(
            sourceRange(
                decoded.snapshot.version,
                decoded.snapshot.sourceEncoding,
                expectedStart,
                expectedEndExclusive,
            ),
            before.containingRange,
        )
        assertEquals(before.containingRange, after.containingRange)
    }

    private fun decodeThenReleaseBorrowedUtf8(version: TextVersion): TextDecodingResult {
        val leased = ReleasableUtf8Storage(
            ImmutableUtf8RopeStorage(
                listOf(
                    byteArrayOf(0x41, 0xF0.toByte()),
                    byteArrayOf(0x9F.toByte(), 0x98.toByte(), 0x80.toByte(), 0x42),
                ),
            ),
        )
        val result = TextSnapshots.decodeUtf8(
            version,
            listOf(TextSlice.Utf8.borrow(leased, 0, 1), TextSlice.Utf8.borrow(leased, 1, 6)),
        )
        leased.release()
        return result
    }

    private fun sourceOffset(version: TextVersion, encoding: SourceEncoding, value: Int): SourceOffset =
        SourceOffset(version, encoding, value)

    private fun sourceRange(
        version: TextVersion,
        encoding: SourceEncoding,
        start: Int,
        endExclusive: Int,
    ): SourceRange = SourceRange(
        sourceOffset(version, encoding, start),
        sourceOffset(version, encoding, endExclusive),
    )
}

private class ImmutableUtf8RopeStorage(chunks: List<ByteArray>) : Utf8Storage {
    private val chunks: List<ByteArray> = chunks.map(ByteArray::copyOf)
    override val length: Int = chunks.sumOf(ByteArray::size)

    override fun get(index: Int): Byte {
        require(index in 0 until length)
        var remaining = index
        chunks.forEach { chunk ->
            if (remaining < chunk.size) return chunk[remaining]
            remaining -= chunk.size
        }
        error("Unreachable rope index.")
    }
}

private data class Piece(val buffer: Int, val start: Int, val endExclusive: Int)

private class ImmutableUtf16PieceTableStorage(
    buffers: List<CharArray>,
    pieces: List<Piece>,
) : Utf16Storage {
    private val buffers: List<CharArray> = buffers.map(CharArray::copyOf)
    private val pieces: List<Piece> = pieces.toList()
    override val length: Int = pieces.sumOf { it.endExclusive - it.start }

    init {
        this.pieces.forEach { piece ->
            require(piece.buffer in this.buffers.indices)
            require(piece.start in 0..piece.endExclusive)
            require(piece.endExclusive <= this.buffers[piece.buffer].size)
        }
    }

    override fun get(index: Int): Char {
        require(index in 0 until length)
        var remaining = index
        pieces.forEach { piece ->
            val pieceLength = piece.endExclusive - piece.start
            if (remaining < pieceLength) return buffers[piece.buffer][piece.start + remaining]
            remaining -= pieceLength
        }
        error("Unreachable piece-table index.")
    }
}

private class ReleasableUtf8Storage(private val delegate: Utf8Storage) : Utf8Storage {
    private var released: Boolean = false
    override val length: Int
        get() = delegate.length

    override fun get(index: Int): Byte {
        check(!released) { "Borrowed source was accessed after the decode call." }
        return delegate[index]
    }

    fun release() {
        released = true
    }
}

private class TraversalCancellationSignal {
    var cancelled: Boolean = false
}

private class CancellingUtf8RopeStorage(
    private val delegate: Utf8Storage,
    private val cancelAtSourceIndex: Int,
    private val signal: TraversalCancellationSignal,
) : Utf8Storage {
    override val length: Int
        get() = delegate.length

    override fun get(index: Int): Byte {
        if (index >= cancelAtSourceIndex) signal.cancelled = true
        return delegate[index]
    }
}
