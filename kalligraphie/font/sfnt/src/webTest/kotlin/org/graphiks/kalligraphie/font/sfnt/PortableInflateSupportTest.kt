package org.graphiks.kalligraphie.font.sfnt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PortableInflateSupportTest {
    private val support = platformInflateSupport()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // Vectors produced by CPython 3.x zlib/gzip level 9 — see the plan's generation note.
    private val helloZlib = bytes(
        0x78, 0xDA, 0xCB, 0x48, 0xCD, 0xC9, 0xC9, 0x07, 0x00, 0x06, 0x2C, 0x02, 0x15,
    )
    private val helloGzip = bytes(
        0x1F, 0x8B, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xFF,
        0xCB, 0x48, 0xCD, 0xC9, 0xC9, 0x07, 0x00,
        0x86, 0xA6, 0x10, 0x36, 0x05, 0x00, 0x00, 0x00,
    )

    @Test
    fun inflatesAZlibStream() {
        val outcome = assertIs<InflateOutcome.Success>(support.inflateZlib(helloZlib, 1024))
        assertEquals("hello", outcome.bytes.decodeToString())
    }

    @Test
    fun inflatesAGzipMember() {
        val outcome = assertIs<InflateOutcome.Success>(support.gunzip(helloGzip, 1024))
        assertEquals("hello", outcome.bytes.decodeToString())
    }

    @Test
    fun refusesAStreamThatExceedsTheBound() {
        val outcome = assertIs<InflateOutcome.LimitExceeded>(support.inflateZlib(helloZlib, 4))
        assertEquals(5L, outcome.observed)
    }

    @Test
    fun rejectsATruncatedStream() {
        val truncated = helloZlib.copyOf(6)
        assertIs<InflateOutcome.Malformed>(support.inflateZlib(truncated, 1024))
    }

    @Test
    fun rejectsABadAdlerChecksum() {
        val bad = helloZlib.copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte()
        assertIs<InflateOutcome.Malformed>(support.inflateZlib(bad, 1024))
    }

    @Test
    fun rejectsTrailingDataAfterAGzipMember() {
        val withTrailer = helloGzip + helloGzip
        assertIs<InflateOutcome.Malformed>(support.gunzip(withTrailer, 1024))
    }

    @Test
    fun inflatesAStoredDeflateBlock() {
        // Raw DEFLATE stored block, then a zlib wrapper around it. 0x01 = BFINAL=1, BTYPE=00.
        val rawStored = bytes(0x01, 0x05, 0x00, 0xFA, 0xFF) + "hello".encodeToByteArray()
        val wrapped = wrapZlibStored(rawStored)
        val outcome = assertIs<InflateOutcome.Success>(support.inflateZlib(wrapped, 1024))
        assertEquals("hello", outcome.bytes.decodeToString())
    }

    // The brief's fixed `hello` vector is BTYPE 1 (fixed Huffman). RFC 1951 also requires block
    // types 2 (dynamic Huffman) and multi-block streams; these supplementary vectors, also produced
    // by CPython 3.x zlib level 9, cover the dynamic alphabet (including repeat codes and an absent
    // distance table), a stream of more than one block, and overlapping 258-byte back-references.

    @Test
    fun inflatesADynamicHuffmanBlock() {
        // btype == 2: dynamic literal/length and distance tables with LZ77 back-references.
        val outcome = assertIs<InflateOutcome.Success>(support.inflateZlib(dynamicWithMatches, 8192))
        assertEquals("abcdefghij".repeat(400), outcome.bytes.decodeToString())
    }

    @Test
    fun inflatesADynamicBlockWithoutMatches() {
        // btype == 2 whose declared distance table is [1,1]: the block emits literals and
        // end-of-block only, so the distance codes are never used. The null-distance-table branch
        // (distanceLengths.all { it == 0 }) remains untested.
        val outcome = assertIs<InflateOutcome.Success>(support.inflateZlib(dynamicWithoutMatches, 256))
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWX", outcome.bytes.decodeToString())
    }

    @Test
    fun inflatesASequenceOfBlocks() {
        // A non-final block followed by the final one; must continue rather than stop at BFINAL=0.
        val outcome = assertIs<InflateOutcome.Success>(support.inflateZlib(multiBlock, 1024))
        assertEquals("hello ".repeat(20) + "world ".repeat(20), outcome.bytes.decodeToString())
    }

    @Test
    fun inflatesOverlappingBackReferences() {
        // A single 1000-byte run of one symbol: length 258 copies that overlap the output window.
        val outcome = assertIs<InflateOutcome.Success>(support.inflateZlib(longRun, 4096))
        assertEquals("A".repeat(1000), outcome.bytes.decodeToString())
    }

    private val dynamicWithMatches = hex(
        "78 da ed c6 49 01 00 20 08 00 b0 ac 78 20 da 3f 80 31 f8 6c af c5 98 6b e7 a9 fb c2 cc cc cc cc cc cc cc 9a f6 01 a0 f5 32 4b",
    )
    private val dynamicWithoutMatches = hex(
        "78 01 05 c1 85 01 00 20 08 00 b0 db c4 00 15 13 fb ff 43 dc 14 68 63 1d 92 0f 91 53 2e b5 75 19 73 ed 73 9f 02 6d ac 43 f2 21 72 ca a5 b6 2e 63 ae 7d 3e 76 dd 0f 0c",
    )
    private val multiBlock = hex(
        "78 da ca 48 cd c9 c9 57 c8 a0 3b 09 00 00 00 ff ff 2b cf 2f ca 49 19 00 12 00 fc 57 59 b1",
    )
    private val longRun = hex("78 da 73 74 1c 05 a3 60 14 0c 77 00 00 89 0c fd e9")

    private fun hex(value: String) = value.trim().split(' ')
        .let { parts -> ByteArray(parts.size) { parts[it].toInt(16).toByte() } }

    /** Builds a zlib wrapper around an already-DEFLATE-compressed payload (already byte-aligned). */
    private fun wrapZlibStored(rawDeflate: ByteArray): ByteArray {
        // zlib's ADLER32 covers the *uncompressed* data, which this stored block carries verbatim.
        val adler = Adler32.of("hello".encodeToByteArray())
        val header = byteArrayOf(0x78, 0x01)
        return header + rawDeflate + byteArrayOf(
            (adler ushr 24).toByte(), (adler ushr 16).toByte(),
            (adler ushr 8).toByte(), adler.toByte(),
        )
    }
}
