@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class BrotliDictionaryTest {
    @Test
    fun hasTheRfcSizeAndFirstWord() {
        assertEquals(122_784, BrotliDictionary.sizeBytes)
        assertEquals("time", BrotliDictionary.word(offset = 0, length = 4).decodeToString())
    }

    @Test
    fun identityTransformLeavesTheWordUnchanged() {
        assertEquals("world", BrotliDictionaryTransforms.apply(0, "world".encodeToByteArray()).decodeToString())
    }

    @Test
    fun carriesTheRfcWordCounts() {
        assertEquals(
            listOf(0, 0, 0, 0, 10, 10, 11, 11, 10, 10, 10, 10, 10, 9, 9, 8, 7, 7, 8, 7, 7, 6, 6, 5, 5),
            BrotliDictionary.NWORDS_BITS.toList(),
        )
        assertEquals(
            listOf(0, 0, 0, 0, 1024, 1024, 2048, 2048, 1024, 1024, 1024, 1024, 1024, 512, 512, 256, 128, 128, 256, 128, 128, 64, 64, 32, 32),
            BrotliDictionary.NWORDS.toList(),
        )
        assertEquals(
            BrotliDictionary.sizeBytes,
            BrotliDictionary.NWORDS.indices.sumOf { length -> length * BrotliDictionary.NWORDS[length] },
        )
    }

    @Test
    fun eachLengthGroupStartsAtItsRfcByteOffset() {
        val firstWords = listOf(
            4 to "time",
            5 to "first",
            6 to "&quot;",
            7 to "profile",
            8 to "position",
            9 to "resources",
            10 to "categories",
            11 to "sByTagName(",
            12 to "line-height:",
            13 to "entertainment",
            14 to "\"><div class=\"",
            15 to "cursor:pointer;",
            16 to "rss+xml\" title=\"",
            17 to "robots\" content=\"",
            18 to "position:absolute;",
            19 to "keywords\" content=\"",
            20 to "%3E%3C/script%3E\"));",
            21 to "html; charset=UTF-8\" ",
            22 to "description\" content=\"",
            23 to "<!DOCTYPE html PUBLIC \"",
            24 to "<script type=\"text/javas",
        )
        for ((length, word) in firstWords) {
            assertEquals(word, BrotliDictionary.word(offset = 0, length = length).decodeToString(), "first $length-byte word")
        }
    }

    @Test
    fun resolvesWordsWithinAGroupAndWrapsTheWordIndex() {
        assertEquals("down", BrotliDictionary.word(offset = 1, length = 4).decodeToString())
        assertEquals("code", BrotliDictionary.word(offset = 5, length = 4).decodeToString())
        assertEquals("video", BrotliDictionary.word(offset = 1, length = 5).decodeToString())
        // The word id is masked to NDBITS[length], so an id one whole group past the end wraps.
        assertEquals(
            BrotliDictionary.word(offset = 0, length = 4).decodeToString(),
            BrotliDictionary.word(offset = 1024, length = 4).decodeToString(),
        )
        assertNotEquals(
            BrotliDictionary.word(offset = 0, length = 4).decodeToString(),
            BrotliDictionary.word(offset = 1023, length = 4).decodeToString(),
        )
    }

    @Test
    fun transformTableMatchesTheRfcAppendixBEncoding() {
        assertEquals(121, BrotliDictionaryTransforms.TRANSFORMS.size)
        val encoded = ArrayList<Byte>()
        for (transform in BrotliDictionaryTransforms.TRANSFORMS) {
            transform.prefix.forEach { encoded.add(it) }
            encoded.add(0)
            encoded.add(transform.type.code.toByte())
            transform.suffix.forEach { encoded.add(it) }
            encoded.add(0)
        }
        assertEquals(648, encoded.size)
        assertEquals(0x3d965f81L, crc32(encoded.toByteArray()))
    }

    @Test
    fun appliesPrefixSuffixAndIdentityTransforms() {
        assertEquals("world", BrotliDictionaryTransforms.apply(0, "world".encodeToByteArray()).decodeToString())
        assertEquals(" word ", BrotliDictionaryTransforms.apply(2, "word".encodeToByteArray()).decodeToString())
        assertEquals(" the word", BrotliDictionaryTransforms.apply(41, "word".encodeToByteArray()).decodeToString())
        assertEquals("s word ", BrotliDictionaryTransforms.apply(7, "word".encodeToByteArray()).decodeToString())
        assertEquals(".word", BrotliDictionaryTransforms.apply(32, "word".encodeToByteArray()).decodeToString())
    }

    @Test
    fun fermentationUppercasesTheFirstByteOrAllOfTheWord() {
        assertEquals("Time ", BrotliDictionaryTransforms.apply(4, "time".encodeToByteArray()).decodeToString())
        assertEquals("TIME", BrotliDictionaryTransforms.apply(44, "time".encodeToByteArray()).decodeToString())
        assertEquals(" Time", BrotliDictionaryTransforms.apply(30, "time".encodeToByteArray()).decodeToString())
        // The two-byte UTF-8 path toggles the continuation byte: U+00E9 to U+00C9 (É).
        assertContentEquals("É".encodeToByteArray(), BrotliDictionaryTransforms.apply(9, "é".encodeToByteArray()))
        // The three-byte path applies the RFC's arbitrary xor of 5 to the third byte.
        assertContentEquals(
            byteArrayOf(0xE3.toByte(), 0x81.toByte(), 0x87.toByte()),
            BrotliDictionaryTransforms.apply(9, "あ".encodeToByteArray()),
        )
    }

    @Test
    fun omissionsMayShortenOrEmptyTheBaseWord() {
        assertEquals("ord", BrotliDictionaryTransforms.apply(3, "word".encodeToByteArray()).decodeToString())
        assertEquals("wor", BrotliDictionaryTransforms.apply(12, "word".encodeToByteArray()).decodeToString())
        assertEquals("", BrotliDictionaryTransforms.apply(34, "word".encodeToByteArray()).decodeToString())
        assertEquals("", BrotliDictionaryTransforms.apply(64, "word".encodeToByteArray()).decodeToString())
        // An omission longer than the word yields the empty string, not an out-of-range slice.
        assertContentEquals(ByteArray(0), BrotliDictionaryTransforms.apply(34, "ab".encodeToByteArray()))
        // A non-breaking space prefix (transform 102) is the table's one non-ASCII byte sequence.
        assertContentEquals(
            byteArrayOf(0xC2.toByte(), 0xA0.toByte(), 'w'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'd'.code.toByte()),
            BrotliDictionaryTransforms.apply(102, "word".encodeToByteArray()),
        )
    }

    @Test
    fun rejectsOutOfRangeInputs() {
        assertFailsWith<IllegalArgumentException> { BrotliDictionary.word(offset = 0, length = 3) }
        assertFailsWith<IllegalArgumentException> { BrotliDictionary.word(offset = 0, length = 25) }
        assertFailsWith<IllegalArgumentException> { BrotliDictionary.word(offset = -1, length = 4) }
        assertFailsWith<IllegalArgumentException> { BrotliDictionaryTransforms.apply(121, "word".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { BrotliDictionaryTransforms.apply(-1, "word".encodeToByteArray()) }
    }

    private fun crc32(bytes: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (byte in bytes) {
            var c = (crc xor (byte.toLong() and 0xFF)) and 0xFF
            for (bit in 0 until 8) {
                c = if (c and 1L != 0L) 0xEDB88320L xor (c shr 1) else c shr 1
            }
            crc = c xor (crc shr 8)
        }
        return (crc xor 0xFFFFFFFFL) and 0xFFFFFFFFL
    }
}
