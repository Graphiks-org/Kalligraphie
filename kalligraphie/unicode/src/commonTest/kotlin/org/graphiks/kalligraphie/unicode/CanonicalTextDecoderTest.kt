package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.SourceBias
import org.graphiks.kalligraphie.api.SourceEncoding
import org.graphiks.kalligraphie.api.SourceIndexResult
import org.graphiks.kalligraphie.api.SourceOffset
import org.graphiks.kalligraphie.api.SourceRange
import org.graphiks.kalligraphie.api.TextDecodingResult
import org.graphiks.kalligraphie.api.TextDiagnostic
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion

class CanonicalTextDecoderTest {
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

        val utf8Interior = utf8.snapshot.sourceToTextIndex(sourceOffset(version, SourceEncoding.UTF8, 3), SourceBias.BEFORE)
        val utf8Biased = assertIs<SourceIndexResult.Biased>(utf8Interior)
        assertEquals(sourceRange(version, SourceEncoding.UTF8, 1, 5), utf8Biased.containingRange)
        assertEquals(sourceOffset(version, SourceEncoding.UTF8, 1), utf8.snapshot.textIndexToSource(utf8Biased.index))

        assertFailsWith<IllegalArgumentException> {
            utf16.snapshot.sourceToTextIndex(sourceOffset(version, SourceEncoding.UTF8, 1), SourceBias.BEFORE)
        }
        assertFailsWith<IllegalArgumentException> {
            TextRange(utf8.snapshot.range.start, utf16.snapshot.range.endExclusive)
        }
        assertFailsWith<IllegalArgumentException> {
            utf8.snapshot.textIndexToSource(utf16.snapshot.range.start)
        }
        assertFailsWith<IllegalArgumentException> {
            utf8.snapshot.sourceToTextIndex(
                sourceOffset(TextVersion.create(), SourceEncoding.UTF8, 0),
                SourceBias.BEFORE,
            )
        }
    }

    @Test
    fun split_utf8_sequence_and_split_surrogate_pair_do_not_change_the_snapshot() {
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

        val utf8Unsplit = TextSnapshots.decodeUtf8(
            version,
            listOf(TextSlice.Utf8(byteArrayOf(0x41, 0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte(), 0x42))),
        )
        val utf8Split = TextSnapshots.decodeUtf8(
            version,
            listOf(
                TextSlice.Utf8(byteArrayOf(0x41, 0xF0.toByte())),
                TextSlice.Utf8(byteArrayOf(0x9F.toByte(), 0x98.toByte())),
                TextSlice.Utf8(byteArrayOf(0x80.toByte(), 0x42)),
            ),
        )
        val utf16Unsplit = TextSnapshots.decodeUtf16(
            version,
            listOf(TextSlice.Utf16(charArrayOf('A', '\uD83D', '\uDE00', 'B'))),
        )
        val utf16Split = TextSnapshots.decodeUtf16(
            version,
            listOf(
                TextSlice.Utf16(charArrayOf('A', '\uD83D')),
                TextSlice.Utf16(charArrayOf('\uDE00', 'B')),
            ),
        )

        assertPartitionInvariant(utf8Unsplit, utf8Split, SourceEncoding.UTF8, sourceLength = 6)
        assertPartitionInvariant(utf16Unsplit, utf16Split, SourceEncoding.UTF16, sourceLength = 4)
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
                TextSlice.Utf8(byteArrayOf(0xE2.toByte())),
                TextSlice.Utf8(byteArrayOf(0x82.toByte(), 0x41, 0x80.toByte())),
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
