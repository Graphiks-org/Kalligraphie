package org.graphiks.kalligraphie.font.sfnt

/**
 * Bounded, synchronous RFC 1951 raw DEFLATE decoder.
 *
 * Structural requirements (all are exercised by `PortableInflateSupportTest`):
 *  - bit order is least-significant-bit first;
 *  - block types 0 (stored), 1 (fixed Huffman), 2 (dynamic Huffman) are supported;
 *  - the 15-bit literal/length and distance Huffman alphabets are decoded bit-by-bit using the
 *    canonical-code counts/offsets method;
 *  - length codes 257..285 and distance codes 0..29 use the RFC 1951 base/extra tables;
 *  - the copy window is 32 KiB and overlapping copies are byte-by-byte;
 *  - [maxOutputBytes] is checked after every output byte; exceeding it throws [TooLarge].
 */
internal class RawInflate(
    private val input: ByteArray,
    private val start: Int,
    private val end: Int,
    private val maxOutputBytes: Long,
) {
    internal sealed interface Result {
        class Success(val bytes: ByteArray) : Result
        class Malformed(val detail: String) : Result
        class TooLarge(val observed: Long, val maximum: Long) : Result
    }

    fun inflate(): Result = try {
        Result.Success(decodeAll())
    } catch (malformed: MalformedInput) {
        Result.Malformed(malformed.message ?: "malformed deflate stream")
    } catch (tooLarge: OutputTooLarge) {
        Result.TooLarge(tooLarge.observed, tooLarge.maximum)
    }

    private val reader = BitReader(input, start, end)
    private var output = ByteArray(INITIAL_OUTPUT_CAPACITY)
    private var outputSize = 0

    private fun decodeAll(): ByteArray {
        while (true) {
            val isFinal = reader.readBit()
            when (reader.readBits(2)) {
                STORED_BLOCK -> decodeStoredBlock()
                FIXED_BLOCK -> decodeCompressedBlock(FIXED_LITERAL_LENGTHS, FIXED_DISTANCE_LENGTHS)
                DYNAMIC_BLOCK -> {
                    val (literalLengths, distanceLengths) = readDynamicLengths()
                    decodeCompressedBlock(literalLengths, distanceLengths)
                }
                else -> throw MalformedInput("deflate block type 3 is reserved")
            }
            if (isFinal == 1) break
        }
        // A single RFC 1951 stream ends inside the last byte of the slice. A whole untouched byte
        // left over means trailing data (for example a second concatenated gzip member).
        val lastByte = start + ((reader.bitPosition - 1) ushr 3).toInt()
        if (lastByte != end - 1) throw MalformedInput("trailing bytes after the deflate stream")
        return output.copyOf(outputSize)
    }

    private fun decodeStoredBlock() {
        reader.alignToByte()
        val length = reader.readBits(16)
        val complement = reader.readBits(16)
        if (length xor complement != 0xFFFF) throw MalformedInput("stored block length check failed")
        repeat(length) { emit(reader.readBits(8)) }
    }

    private fun decodeCompressedBlock(literalLengths: IntArray, distanceLengths: IntArray) {
        val literal = Huffman.build(literalLengths)
        val distance = if (distanceLengths.any { it != 0 }) Huffman.build(distanceLengths) else null
        while (true) {
            val symbol = literal.decode(reader)
            when {
                symbol < 256 -> emit(symbol)
                symbol == 256 -> return
                else -> {
                    val lengthIndex = symbol - 257
                    if (lengthIndex >= LENGTH_BASE.size) throw MalformedInput("invalid length code")
                    val length = LENGTH_BASE[lengthIndex] + reader.readBits(LENGTH_EXTRA[lengthIndex])
                    val distanceSymbol = distance?.decode(reader)
                        ?: throw MalformedInput("distance code used without a distance table")
                    if (distanceSymbol >= DIST_BASE.size) throw MalformedInput("invalid distance code")
                    val distanceValue = DIST_BASE[distanceSymbol] + reader.readBits(DIST_EXTRA[distanceSymbol])
                    if (distanceValue > outputSize) throw MalformedInput("distance exceeds the produced output")
                    repeat(length) { emit(output[outputSize - distanceValue].toInt() and 0xFF) }
                }
            }
        }
    }

    private fun readDynamicLengths(): Pair<IntArray, IntArray> {
        val literalCount = reader.readBits(5) + 257
        val distanceCount = reader.readBits(5) + 1
        val codeLengthCount = reader.readBits(4) + 4
        val codeLengthLengths = IntArray(19)
        for (index in 0 until codeLengthCount) {
            codeLengthLengths[CODE_LENGTH_ORDER[index]] = reader.readBits(3)
        }
        val codeLengthTable = Huffman.build(codeLengthLengths)
        val lengths = IntArray(literalCount + distanceCount)
        var index = 0
        while (index < lengths.size) {
            val symbol = codeLengthTable.decode(reader)
            when {
                symbol < 16 -> lengths[index++] = symbol
                symbol == 16 -> {
                    if (index == 0) throw MalformedInput("repeat code has no preceding length")
                    val previous = lengths[index - 1]
                    val repeat = 3 + reader.readBits(2)
                    if (index + repeat > lengths.size) throw MalformedInput("repeat exceeds the code-length table")
                    repeat(repeat) { lengths[index++] = previous }
                }
                symbol == 17 -> {
                    val repeat = 3 + reader.readBits(3)
                    if (index + repeat > lengths.size) throw MalformedInput("repeat exceeds the code-length table")
                    repeat(repeat) { lengths[index++] = 0 }
                }
                symbol == 18 -> {
                    val repeat = 11 + reader.readBits(7)
                    if (index + repeat > lengths.size) throw MalformedInput("repeat exceeds the code-length table")
                    repeat(repeat) { lengths[index++] = 0 }
                }
                else -> throw MalformedInput("invalid code-length symbol")
            }
        }
        return lengths.copyOfRange(0, literalCount) to lengths.copyOfRange(literalCount, lengths.size)
    }

    private fun emit(byte: Int) {
        if (outputSize == output.size) output = output.copyOf(grownCapacity())
        output[outputSize++] = byte.toByte()
        if (outputSize.toLong() > maxOutputBytes) {
            throw OutputTooLarge(outputSize.toLong(), maxOutputBytes)
        }
    }

    private fun grownCapacity(): Int {
        val doubled = output.size.toLong() * 2
        return if (doubled >= MAXIMUM_OUTPUT_CAPACITY) MAXIMUM_OUTPUT_CAPACITY else doubled.toInt()
    }

    private companion object {
        const val STORED_BLOCK = 0
        const val FIXED_BLOCK = 1
        const val DYNAMIC_BLOCK = 2
    }
}

private class MalformedInput(message: String) : Exception(message)

private class OutputTooLarge(val observed: Long, val maximum: Long) : Exception()

/** LSB-first bit reader over `input[start, end)`. */
private class BitReader(
    private val input: ByteArray,
    private val start: Int,
    private val end: Int,
) {
    var bitPosition: Long = 0L
        private set

    fun readBit(): Int {
        val byteIndex = start + (bitPosition ushr 3).toInt()
        if (byteIndex >= end) throw MalformedInput("deflate stream is truncated")
        val bit = (input[byteIndex].toInt() ushr (bitPosition.toInt() and 7)) and 1
        bitPosition++
        return bit
    }

    fun readBits(count: Int): Int {
        var value = 0
        var index = 0
        while (index < count) {
            value = value or (readBit() shl index)
            index++
        }
        return value
    }

    fun alignToByte() {
        bitPosition = (bitPosition + 7) and 7L.inv()
    }
}

/**
 * Canonical Huffman decoder built from per-symbol code lengths. Decoding walks one bit at a time,
 * matching the "counts/offsets" method used by RFC 1951 reference decoders.
 */
private class Huffman private constructor(
    private val counts: IntArray,
    private val symbols: IntArray,
) {
    fun decode(reader: BitReader): Int {
        var code = 0
        var first = 0
        var index = 0
        for (length in 1..15) {
            code = code or reader.readBit()
            val count = counts[length]
            if (code - first < count) return symbols[index + (code - first)]
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        throw MalformedInput("invalid Huffman code")
    }

    companion object {
        fun build(lengths: IntArray): Huffman {
            val counts = IntArray(16)
            for (length in lengths) {
                if (length < 0 || length > 15) throw MalformedInput("invalid Huffman code length")
                counts[length]++
            }
            var remaining = 1
            for (length in 1..15) {
                remaining = (remaining shl 1) - counts[length]
                if (remaining < 0) throw MalformedInput("over-subscribed Huffman code")
            }
            val offsets = IntArray(16)
            for (length in 1 until 15) offsets[length + 1] = offsets[length] + counts[length]
            val symbols = IntArray(counts.sum() - counts[0])
            val next = offsets.copyOf()
            for (symbol in lengths.indices) {
                val length = lengths[symbol]
                if (length != 0) symbols[next[length]++] = symbol
            }
            return Huffman(counts, symbols)
        }
    }
}

private val LENGTH_BASE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
    67, 83, 99, 115, 131, 163, 195, 227, 258,
)
private val LENGTH_EXTRA = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
    4, 4, 4, 4, 5, 5, 5, 5, 0,
)
private val DIST_BASE = intArrayOf(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
    1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
)
private val DIST_EXTRA = intArrayOf(
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8,
    9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
)
private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

private val FIXED_LITERAL_LENGTHS = IntArray(288) { symbol ->
    when (symbol) {
        in 0..143 -> 8
        in 144..255 -> 9
        in 256..279 -> 7
        else -> 8
    }
}
private val FIXED_DISTANCE_LENGTHS = IntArray(32) { 5 }

private const val INITIAL_OUTPUT_CAPACITY = 64
private const val MAXIMUM_OUTPUT_CAPACITY = Int.MAX_VALUE - 8
