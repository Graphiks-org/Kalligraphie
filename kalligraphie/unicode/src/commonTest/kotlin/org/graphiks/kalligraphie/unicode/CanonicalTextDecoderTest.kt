package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.graphiks.kalligraphie.api.SourceBias
import org.graphiks.kalligraphie.api.SourceIndexResult
import org.graphiks.kalligraphie.api.SourceOffset
import org.graphiks.kalligraphie.api.SourceRange
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion

class CanonicalTextDecoderTest {
    @Test
    fun utf8_and_utf16_produce_the_same_scalars_and_boundaries() {
        val version = TextVersion(7)

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
        assertEquals(
            TextRange(TextIndex(version, 0), TextIndex(version, 3)),
            utf8.snapshot.range,
        )
        assertEquals(utf8.snapshot.range, utf16.snapshot.range)
        assertEquals(SourceOffset(version, 1), utf8.snapshot.textIndexToSource(TextIndex(version, 1)))
        assertEquals(SourceOffset(version, 5), utf8.snapshot.textIndexToSource(TextIndex(version, 2)))
        assertEquals(SourceOffset(version, 1), utf16.snapshot.textIndexToSource(TextIndex(version, 1)))
        assertEquals(SourceOffset(version, 3), utf16.snapshot.textIndexToSource(TextIndex(version, 2)))
        assertEquals(
            SourceIndexResult.Exact(TextIndex(version, 2)),
            utf8.snapshot.sourceToTextIndex(SourceOffset(version, 5), SourceBias.BEFORE),
        )
        assertEquals(
            SourceIndexResult.Biased(
                index = TextIndex(version, 1),
                containingRange = SourceRange(SourceOffset(version, 1), SourceOffset(version, 5)),
            ),
            utf8.snapshot.sourceToTextIndex(SourceOffset(version, 3), SourceBias.BEFORE),
        )
        assertEquals(
            SourceIndexResult.Biased(
                index = TextIndex(version, 2),
                containingRange = SourceRange(SourceOffset(version, 1), SourceOffset(version, 5)),
            ),
            utf8.snapshot.sourceToTextIndex(SourceOffset(version, 3), SourceBias.AFTER),
        )
    }

    @Test
    fun split_utf8_sequence_and_split_surrogate_pair_do_not_change_the_snapshot() {
        val version = TextVersion(11)
        val utf8Buffers = listOf(
            byteArrayOf(0x41, 0xF0.toByte()),
            byteArrayOf(0x9F.toByte(), 0x98.toByte()),
            byteArrayOf(0x80.toByte(), 0x42),
        )
        val utf16Buffers = listOf(
            charArrayOf('A', '\uD83D'),
            charArrayOf('\uDE00', 'B'),
        )

        val utf8 = TextSnapshots.decodeUtf8(version, utf8Buffers.map(TextSlice::Utf8))
        val utf16 = TextSnapshots.decodeUtf16(version, utf16Buffers.map(TextSlice::Utf16))
        utf8Buffers.forEach { it.fill(0) }
        utf16Buffers.forEach { it.fill('\u0000') }

        assertEquals(listOf(0x41, 0x1F600, 0x42), utf8.snapshot.scalars)
        assertEquals(utf8.snapshot.scalars, utf16.snapshot.scalars)
        assertEquals(emptyList(), utf8.diagnostics)
        assertEquals(emptyList(), utf16.diagnostics)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (utf8.snapshot.scalars as MutableList<Int>).clear()
        }
        assertEquals(listOf(0x41, 0x1F600, 0x42), utf8.snapshot.scalars)
    }

    @Test
    fun malformed_utf8_and_utf16_emit_one_replacement_per_maximal_subpart() {
        val version = TextVersion(13)

        val utf8 = TextSnapshots.decodeUtf8(
            version,
            listOf(
                TextSlice.Utf8(byteArrayOf(0xE2.toByte())),
                TextSlice.Utf8(byteArrayOf(0x82.toByte(), 0x41, 0x80.toByte())),
            ),
        )
        val utf16 = TextSnapshots.decodeUtf16(
            version,
            listOf(
                TextSlice.Utf16(charArrayOf('\uD83D')),
                TextSlice.Utf16(charArrayOf('A', '\uDE00')),
            ),
        )

        assertEquals(listOf(0xFFFD, 0x41, 0xFFFD), utf8.snapshot.scalars)
        assertEquals(utf8.snapshot.scalars, utf16.snapshot.scalars)
        assertEquals(listOf("text.malformed-utf8", "text.malformed-utf8"), utf8.diagnostics.map { it.code })
        assertEquals(
            listOf(
                SourceRange(SourceOffset(version, 0), SourceOffset(version, 2)),
                SourceRange(SourceOffset(version, 3), SourceOffset(version, 4)),
            ),
            utf8.diagnostics.map { it.sourceRange },
        )
        assertEquals(listOf("text.malformed-utf16", "text.malformed-utf16"), utf16.diagnostics.map { it.code })
        assertEquals(
            listOf(
                SourceRange(SourceOffset(version, 0), SourceOffset(version, 1)),
                SourceRange(SourceOffset(version, 2), SourceOffset(version, 3)),
            ),
            utf16.diagnostics.map { it.sourceRange },
        )
        assertEquals(
            SourceIndexResult.Biased(
                index = TextIndex(version, 1),
                containingRange = SourceRange(SourceOffset(version, 0), SourceOffset(version, 2)),
            ),
            utf8.snapshot.sourceToTextIndex(SourceOffset(version, 1), SourceBias.AFTER),
        )
        assertFailsWith<IllegalArgumentException> {
            utf8.snapshot.sourceToTextIndex(SourceOffset(TextVersion(14), 0), SourceBias.BEFORE)
        }
        assertFailsWith<IllegalArgumentException> {
            utf8.snapshot.textIndexToSource(TextIndex(TextVersion(14), 0))
        }
        assertFailsWith<IllegalArgumentException> {
            TextRange(TextIndex(version, 0), TextIndex(TextVersion(14), 1))
        }
        assertFailsWith<IllegalArgumentException> {
            SourceRange(SourceOffset(version, 0), SourceOffset(TextVersion(14), 1))
        }
    }
}
