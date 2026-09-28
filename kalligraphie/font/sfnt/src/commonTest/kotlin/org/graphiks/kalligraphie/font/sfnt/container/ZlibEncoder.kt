@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

/**
 * A minimal, portable zlib (RFC 1950) encoder over one raw DEFLATE (RFC 1951) stream.
 *
 * The container tests build the files they decode, and the web targets have no Okio `Deflater`, so
 * the fixtures are produced by pure Kotlin instead of a platform compressor. One encoder means one
 * set of bytes on every target, and the tests that consume it run wherever the decoders do —
 * including `js` and `wasmJs`. It is correct rather than fast: fixed Huffman codes and a greedy
 * three-byte LZ77 match, which is far more than the fixtures need.
 *
 * The stream is validated against an independent decoder on the JVM (`OkioInflateSupport`, Okio's
 * `InflaterSource`) and against the portable one on web, so a defect in this encoder fails the
 * round-trip rather than producing a stream only this code can read.
 */
internal object ZlibEncoder {
    private const val END_OF_BLOCK = 256
    private const val MIN_MATCH = 3
    private const val MAX_MATCH = 258
    private const val WINDOW_SIZE = 32 * 1024
    private const val HASH_BITS = 15
    private const val HASH_SIZE = 1 shl HASH_BITS
    private const val MAX_CHAIN = 64
    private const val ADLER_MODULUS = 65521

    /** Length codes 257..285: base length and extra-bit count, in order. */
    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27,
        31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2,
        2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )

    /** Distance codes 0..29: base distance and extra-bit count, in order. */
    private val DISTANCE_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129,
        193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DISTANCE_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6,
        6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )

    /** Encodes [source] as one zlib stream. */
    fun encode(source: ByteArray): ByteArray {
        val writer = BitWriter()
        // CM = 8 (deflate), CINFO = 7 (32 KiB window), FLEVEL = 0; 0x7801 is a multiple of 31.
        writer.writeBits(0x78, 8)
        writer.writeBits(0x01, 8)
        writer.writeBits(1, 1) // BFINAL
        writer.writeBits(1, 2) // BTYPE = 01, fixed Huffman
        emitTokens(writer, source)
        writeLiteralOrLength(writer, END_OF_BLOCK)
        writer.alignToByte()
        writeAdler32(writer, source)
        return writer.toByteArray()
    }

    private fun emitTokens(writer: BitWriter, source: ByteArray) {
        if (source.size < MIN_MATCH) {
            for (byte in source) writeLiteralOrLength(writer, byte.toInt() and 0xFF)
            return
        }

        val heads = IntArray(HASH_SIZE) { -1 }
        val previous = IntArray(source.size) { -1 }
        var position = 0
        while (position < source.size) {
            var bestLength = 0
            var bestDistance = 0
            val hashed = position + MIN_MATCH <= source.size
            if (hashed) {
                val hash = hashAt(source, position)
                var candidate = heads[hash]
                var chain = 0
                while (candidate >= 0 && chain < MAX_CHAIN && position - candidate <= WINDOW_SIZE) {
                    val length = matchLength(source, candidate, position)
                    if (length > bestLength) {
                        bestLength = length
                        bestDistance = position - candidate
                        if (length == MAX_MATCH) break
                    }
                    candidate = previous[candidate]
                    chain++
                }
                previous[position] = heads[hash]
                heads[hash] = position
            }

            if (bestLength >= MIN_MATCH) {
                writeMatch(writer, bestLength, bestDistance)
                for (offset in 1 until bestLength) {
                    val index = position + offset
                    if (index + MIN_MATCH <= source.size) {
                        val hash = hashAt(source, index)
                        previous[index] = heads[hash]
                        heads[hash] = index
                    }
                }
                position += bestLength
            } else {
                writeLiteralOrLength(writer, source[position].toInt() and 0xFF)
                position++
            }
        }
    }

    private fun writeMatch(writer: BitWriter, length: Int, distance: Int) {
        val lengthIndex = lengthCodeIndex(length)
        writeLiteralOrLength(writer, 257 + lengthIndex)
        val lengthExtra = LENGTH_EXTRA[lengthIndex]
        if (lengthExtra > 0) writer.writeBits(length - LENGTH_BASE[lengthIndex], lengthExtra)

        val distanceIndex = distanceCodeIndex(distance)
        // Fixed-Huffman distance codes are five bits, written most significant bit first like every
        // Huffman code.
        writer.writeCode(distanceIndex, 5)
        val distanceExtra = DISTANCE_EXTRA[distanceIndex]
        if (distanceExtra > 0) writer.writeBits(distance - DISTANCE_BASE[distanceIndex], distanceExtra)
    }

    private fun writeLiteralOrLength(writer: BitWriter, symbol: Int) {
        when {
            symbol <= 143 -> writer.writeCode(0x30 + symbol, 8)
            symbol <= 255 -> writer.writeCode(0x190 + symbol - 144, 9)
            symbol <= 279 -> writer.writeCode(symbol - 256, 7)
            else -> writer.writeCode(0xC0 + symbol - 280, 8)
        }
    }

    private fun writeAdler32(writer: BitWriter, source: ByteArray) {
        var low = 1
        var high = 0
        for (byte in source) {
            low = (low + (byte.toInt() and 0xFF)) % ADLER_MODULUS
            high = (high + low) % ADLER_MODULUS
        }
        val adler = (high shl 16) or low
        writer.writeBits((adler ushr 24) and 0xFF, 8)
        writer.writeBits((adler ushr 16) and 0xFF, 8)
        writer.writeBits((adler ushr 8) and 0xFF, 8)
        writer.writeBits(adler and 0xFF, 8)
    }

    private fun lengthCodeIndex(length: Int): Int {
        for (index in LENGTH_BASE.indices.reversed()) {
            if (length >= LENGTH_BASE[index]) return index
        }
        error("A DEFLATE length of $length has no code.")
    }

    private fun distanceCodeIndex(distance: Int): Int {
        for (index in DISTANCE_BASE.indices.reversed()) {
            if (distance >= DISTANCE_BASE[index]) return index
        }
        error("A DEFLATE distance of $distance has no code.")
    }

    private fun matchLength(source: ByteArray, candidate: Int, position: Int): Int {
        val maximum = minOf(MAX_MATCH, source.size - position)
        var length = 0
        while (length < maximum && source[candidate + length] == source[position + length]) length++
        return length
    }

    private fun hashAt(source: ByteArray, position: Int): Int {
        val first = source[position].toInt() and 0xFF
        val second = source[position + 1].toInt() and 0xFF
        val third = source[position + 2].toInt() and 0xFF
        val mixed = ((first shl 16) or (second shl 8) or third) * 0x9E3779B1.toInt()
        return (mixed ushr (32 - HASH_BITS)) and (HASH_SIZE - 1)
    }

    /**
     * Packs bits least significant first within a byte — DEFLATE's convention for every field except
     * Huffman codes, which [writeCode] reverses so they come out most significant bit first.
     */
    private class BitWriter {
        private var bytes = ByteArray(64)
        private var size = 0
        private var buffer = 0
        private var bits = 0

        fun writeBits(value: Int, count: Int) {
            var remaining = count
            var source = value
            while (remaining > 0) {
                buffer = buffer or ((source and 1) shl bits)
                source = source ushr 1
                bits++
                remaining--
                if (bits == 8) flush()
            }
        }

        fun writeCode(code: Int, count: Int) {
            var reversed = 0
            var source = code
            repeat(count) {
                reversed = (reversed shl 1) or (source and 1)
                source = source ushr 1
            }
            writeBits(reversed, count)
        }

        fun alignToByte() {
            while (bits != 0) writeBits(0, 1)
        }

        fun toByteArray(): ByteArray {
            if (bits != 0) flush()
            return bytes.copyOf(size)
        }

        private fun flush() {
            if (size == bytes.size) bytes = bytes.copyOf(size * 2)
            bytes[size++] = (buffer and 0xFF).toByte()
            buffer = 0
            bits = 0
        }
    }
}
