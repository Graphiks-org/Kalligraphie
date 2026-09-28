@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import org.graphiks.kalligraphie.api.KalligraphieInternalApi

/**
 * A canonical prefix (Huffman) code used by the Brotli format (RFC 7932 §3.2).
 *
 * Prefix codes are packed starting at the most significant bit of the code (RFC 7932 §1.5.1), which
 * is the opposite of the least-significant-bit-first integer order [BrotliBits] reads. [readCode]
 * therefore walks the canonical code from its most significant bit.
 */
@KalligraphieInternalApi
internal class BrotliHuffman private constructor(
    private val childZero: IntArray,
    private val childOne: IntArray,
    private val leafSymbol: IntArray,
    private val singleSymbol: Int,
) {
    /**
     * Decodes one symbol, consuming between zero and the code's length bits.
     *
     * A single-symbol code consumes no bits. Decoding a truncated stream may return a symbol while
     * [BrotliBits.overran] is set; the caller must treat that as a stream failure.
     */
    fun readCode(bits: BrotliBits): Int {
        if (singleSymbol >= 0) return singleSymbol
        var node = 0
        while (leafSymbol[node] < 0) {
            val next = if (bits.readBit() == 0) childZero[node] else childOne[node]
            if (next < 0) return 0
            node = next
        }
        return leafSymbol[node]
    }

    companion object {
        /**
         * Builds the canonical code described by [codeLengths], where entry `i` is the bit length of
         * symbol `i` and zero means the symbol is absent.
         *
         * A single non-zero length denotes a code whose only symbol is emitted with no bits
         * (RFC 7932 §3.4 and §3.5). Any other code must be complete: its lengths must satisfy the
         * Kraft equality for [maxBits].
         *
         * @throws IllegalArgumentException if [codeLengths] has no symbols, a length exceeds
         * [maxBits], or the lengths do not form a complete canonical code.
         */
        fun fromCodeLengths(codeLengths: IntArray, maxBits: Int): BrotliHuffman {
            val counts = IntArray(maxBits + 1)
            var nonZero = 0
            var onlySymbol = -1
            for (symbol in codeLengths.indices) {
                val length = codeLengths[symbol]
                require(length in 0..maxBits) { "Brotli code length $length exceeds $maxBits." }
                if (length != 0) {
                    counts[length]++
                    nonZero++
                    onlySymbol = symbol
                }
            }
            require(nonZero != 0) { "Brotli code has no symbols." }
            if (nonZero == 1) return fromSingleSymbol(onlySymbol)

            var slots = 0
            for (length in 1..maxBits) slots += counts[length] shl (maxBits - length)
            require(slots == (1 shl maxBits)) { "Brotli code lengths are not a complete canonical code." }

            val nextCode = IntArray(maxBits + 1)
            var code = 0
            for (bits in 1..maxBits) {
                code = (code + counts[bits - 1]) shl 1
                nextCode[bits] = code
            }

            val maxNodes = 2 * codeLengths.size + 1
            val childZero = IntArray(maxNodes) { -1 }
            val childOne = IntArray(maxNodes) { -1 }
            val leafSymbol = IntArray(maxNodes) { -1 }
            var nodes = 1
            for (symbol in codeLengths.indices) {
                val length = codeLengths[symbol]
                if (length == 0) continue
                val value = nextCode[length]
                nextCode[length] = value + 1
                var node = 0
                for (shift in length - 1 downTo 0) {
                    val bit = (value ushr shift) and 1
                    val existing = if (bit == 0) childZero[node] else childOne[node]
                    node = if (existing < 0) {
                        val created = nodes++
                        if (bit == 0) childZero[node] = created else childOne[node] = created
                        created
                    } else {
                        existing
                    }
                    if (shift == 0) leafSymbol[node] = symbol
                }
            }
            return BrotliHuffman(childZero, childOne, leafSymbol, singleSymbol = -1)
        }

        /** Builds a code whose one symbol is decoded without reading any bits (RFC 7932 §3.4). */
        fun fromSingleSymbol(symbol: Int): BrotliHuffman {
            require(symbol >= 0) { "Brotli single symbol $symbol must be non-negative." }
            return BrotliHuffman(IntArray(0), IntArray(0), IntArray(0), singleSymbol = symbol)
        }
    }
}

/**
 * Reads the compressed description of one Brotli prefix code (RFC 7932 §3.4 and §3.5).
 *
 * The description is either a simple code naming one to four symbols directly or a complex code
 * whose lengths are themselves Huffman-coded. Malformed descriptions are rejected by throwing
 * [IllegalArgumentException]; the caller maps that to a stream failure. Truncation is instead
 * reported through [BrotliBits.overran], because the underlying reads return zero bits.
 */
@KalligraphieInternalApi
internal object BrotliHuffmanReader {
    private const val MAX_CODE_LENGTH = 15
    private const val MAX_CODE_LENGTH_CODE_LENGTH = 5
    private const val REPEAT_PREVIOUS_CODE_LENGTH = 16
    private const val INITIAL_REPEATED_CODE_LENGTH = 8

    /** The 18 code-length symbols, in the order their lengths appear (RFC 7932 §3.5). */
    private val CODE_LENGTH_CODE_ORDER =
        intArrayOf(1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15)

    /** Reads one prefix code description for an alphabet of [alphabetSize] symbols. */
    fun read(bits: BrotliBits, alphabetSize: Int): BrotliHuffman {
        val simpleOrSkip = bits.readBits(2)
        if (simpleOrSkip == 1) return readSimple(bits, alphabetSize)
        return readComplex(bits, alphabetSize, simpleOrSkip)
    }

    private fun readSimple(bits: BrotliBits, alphabetSize: Int): BrotliHuffman {
        val symbolCount = bits.readBits(2) + 1
        val symbolBits = bitWidth(alphabetSize)
        val symbols = IntArray(symbolCount)
        for (i in 0 until symbolCount) {
            val symbol = bits.readBits(symbolBits)
            if (symbol >= alphabetSize) {
                throw IllegalArgumentException("Brotli simple code symbol $symbol is outside the alphabet.")
            }
            symbols[i] = symbol
        }
        for (i in 0 until symbolCount) {
            for (j in i + 1 until symbolCount) {
                if (symbols[i] == symbols[j]) {
                    throw IllegalArgumentException("Brotli simple code repeats symbol ${symbols[i]}.")
                }
            }
        }
        if (symbolCount == 1) return BrotliHuffman.fromSingleSymbol(symbols[0])

        val codeLengths = IntArray(alphabetSize)
        when (symbolCount) {
            2 -> {
                codeLengths[symbols[0]] = 1
                codeLengths[symbols[1]] = 1
            }
            3 -> {
                codeLengths[symbols[0]] = 1
                codeLengths[symbols[1]] = 2
                codeLengths[symbols[2]] = 2
            }
            else -> {
                if (bits.readBit() == 0) {
                    for (symbol in symbols) codeLengths[symbol] = 2
                } else {
                    codeLengths[symbols[0]] = 1
                    codeLengths[symbols[1]] = 2
                    codeLengths[symbols[2]] = 3
                    codeLengths[symbols[3]] = 3
                }
            }
        }
        return BrotliHuffman.fromCodeLengths(codeLengths, MAX_CODE_LENGTH)
    }

    private fun readComplex(bits: BrotliBits, alphabetSize: Int, hskip: Int): BrotliHuffman {
        val codeLengthCodeLengths = IntArray(CODE_LENGTH_CODE_ORDER.size)
        var space = 32
        var codeCount = 0
        var index = hskip
        while (index < CODE_LENGTH_CODE_ORDER.size) {
            val length = readCodeLengthCodeLength(bits)
            codeLengthCodeLengths[CODE_LENGTH_CODE_ORDER[index]] = length
            index++
            if (length != 0) {
                codeCount++
                space -= 32 shr length
                if (space <= 0) break
            }
        }
        if (codeCount != 1 && space != 0) {
            throw IllegalArgumentException("Brotli code-length code does not form a complete code.")
        }
        val codeLengthCode =
            BrotliHuffman.fromCodeLengths(codeLengthCodeLengths, MAX_CODE_LENGTH_CODE_LENGTH)

        val codeLengths = IntArray(alphabetSize)
        var symbol = 0
        var repeat = 0
        var repeatCodeLength = 0
        var previousCodeLength = INITIAL_REPEATED_CODE_LENGTH
        var alphabetSpace = 32768
        while (symbol < alphabetSize && alphabetSpace > 0) {
            val codeLength = codeLengthCode.readCode(bits)
            if (codeLength < REPEAT_PREVIOUS_CODE_LENGTH) {
                repeat = 0
                codeLengths[symbol] = codeLength
                symbol++
                if (codeLength != 0) {
                    previousCodeLength = codeLength
                    alphabetSpace -= 32768 shr codeLength
                }
            } else {
                val extraBits = if (codeLength == REPEAT_PREVIOUS_CODE_LENGTH) 2 else 3
                val repeatDelta = bits.readBits(extraBits)
                val repeatedLength =
                    if (codeLength == REPEAT_PREVIOUS_CODE_LENGTH) previousCodeLength else 0
                if (repeatCodeLength != repeatedLength) {
                    repeat = 0
                    repeatCodeLength = repeatedLength
                }
                val previousRepeat = repeat
                if (repeat > 0) {
                    repeat -= 2
                    repeat = repeat shl extraBits
                }
                repeat += repeatDelta + 3
                val count = repeat - previousRepeat
                if (symbol + count > alphabetSize) {
                    throw IllegalArgumentException("Brotli repeat exceeds the alphabet.")
                }
                if (repeatCodeLength != 0) {
                    var remaining = count
                    while (remaining > 0) {
                        codeLengths[symbol] = repeatCodeLength
                        symbol++
                        remaining--
                    }
                    alphabetSpace -= count shl (MAX_CODE_LENGTH - repeatCodeLength)
                } else {
                    symbol += count
                }
            }
        }
        if (alphabetSpace != 0) {
            throw IllegalArgumentException("Brotli code lengths do not form a complete code.")
        }
        return BrotliHuffman.fromCodeLengths(codeLengths, MAX_CODE_LENGTH)
    }

    /**
     * Reads one code length from the fixed six-symbol variable-length code of RFC 7932 §3.5.
     *
     * The compressed-data table in the RFC is printed right to left, so the canonical
     * most-significant-bit-first codes are its mirrors: `0 = 00`, `3 = 01`, `4 = 10`, `2 = 110`,
     * `1 = 1110`, `5 = 1111`.
     */
    private fun readCodeLengthCodeLength(bits: BrotliBits): Int =
        if (bits.readBit() == 0) {
            if (bits.readBit() == 0) 0 else 3
        } else if (bits.readBit() == 0) {
            4
        } else if (bits.readBit() == 0) {
            2
        } else if (bits.readBit() == 0) {
            1
        } else {
            5
        }

    /** The smallest number of bits that can represent every symbol of [alphabetSize] (RFC 7932 §3.4). */
    private fun bitWidth(alphabetSize: Int): Int {
        var counter = alphabetSize - 1
        var width = 0
        while (counter != 0) {
            counter = counter ushr 1
            width++
        }
        return width
    }
}
