@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.KalligraphieInternalApi

/**
 * An RFC 7932 Brotli decoder for one complete stream, with independent output and working bounds.
 *
 * [decode] never throws: every malformed stream, reserved field, truncation, or bound breach is
 * returned as a [FontOperationResult.Failure]. [maxOutputBytes] caps the decoded output length;
 * [maxWorkingBytes] caps the sliding-window reach, so a back-reference farther than the bound fails.
 * The window is the only decoder buffer whose size scales with the streamed data; the Huffman code
 * tables and context maps are a-priori bounded by the format's fixed alphabet maxima. Breaching
 * either bound is [FontError.ResourceLimitExceeded]; a malformed stream is
 * [FontError.FontDataFailure] with code `font.woff2.brotli-failed`.
 *
 * The prefix-code, meta-block, context and dictionary pieces are implemented by the sibling files
 * of this package; this object is the state machine that consumes them.
 */
@KalligraphieInternalApi
internal object BrotliDecoder {
    /** Diagnostic code every malformed-stream failure carries (design §6.4). */
    private const val FAILURE_CODE: String = "font.woff2.brotli-failed"

    /**
     * Decodes one Brotli [input] stream, bounded by [maxOutputBytes] and [maxWorkingBytes].
     *
     * The output length is whatever the stream decodes to, capped by [maxOutputBytes]; the caller
     * compares it to the expected length. Neither bound may be negative; a negative bound is a
     * [FontError.ResourceLimitExceeded].
     */
    fun decode(
        input: ByteArray,
        maxOutputBytes: Long,
        maxWorkingBytes: Long,
    ): FontOperationResult<ByteArray> {
        if (maxOutputBytes < 0L) {
            return limitExceeded("Brotli output limit $maxOutputBytes must be non-negative.")
        }
        if (maxWorkingBytes < 0L) {
            return limitExceeded("Brotli working limit $maxWorkingBytes must be non-negative.")
        }
        return try {
            FontOperationResult.Success(BrotliStream(input, maxOutputBytes, maxWorkingBytes).decode())
        } catch (limited: BrotliLimitException) {
            limitExceeded(limited.message ?: "Brotli resource limit exceeded.")
        } catch (invalid: Exception) {
            FontOperationResult.Failure(
                FontError.FontDataFailure(
                    FAILURE_CODE,
                    invalid.message ?: "Brotli stream is malformed.",
                    FontDiagnosticLocation.Source,
                ),
            )
        }
    }

    private fun limitExceeded(message: String): FontOperationResult<ByteArray> =
        FontOperationResult.Failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Source))
}

/** Internal signal that an independent [BrotliDecoder] bound was breached; never escapes. */
private class BrotliLimitException(message: String) : Exception(message)

/**
 * The mutable decoding state of one Brotli stream.
 *
 * Bytes are appended to a growing output array that doubles as the back-reference window, matching
 * the RFC's requirement that a reference may reach into any earlier meta-block. The four-entry
 * distance ring buffer is initialised to `16, 15, 11, 4` and only advanced by a real (non-zero,
 * non-dictionary) distance.
 */
private class BrotliStream(
    input: ByteArray,
    maxOutputBytes: Long,
    private val maxWorkingBytes: Long,
) {
    private val maxOutput: Int = maxOutputBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private val bits = BrotliBits(input)
    private var windowSize: Int = 0
    private var output: ByteArray = ByteArray(0)
    private var outputSize: Int = 0
    private val distanceRing: IntArray = intArrayOf(16, 15, 11, 4)
    private var previousByte: Int = 0
    private var secondPreviousByte: Int = 0

    fun decode(): ByteArray {
        val wbits = BrotliMetaBlock.readWbits(bits)
            ?: throw IllegalArgumentException("Brotli stream uses the reserved window-size encoding.")
        failIfOverran()
        windowSize = (1 shl wbits) - 16

        var last = false
        while (!last) {
            val header = BrotliMetaBlock.read(bits)
            failIfOverran()
            when (header) {
                is BrotliLastEmptyHeader -> last = true
                is BrotliMetadataHeader -> {
                    skipMetadata(header)
                    failIfOverran()
                    last = header.isLast
                }
                is BrotliUncompressedHeader -> {
                    copyUncompressed(header)
                    failIfOverran()
                    last = header.isLast
                }
                is BrotliCompressedHeader -> {
                    decodeCompressed(header)
                    failIfOverran()
                    last = header.isLast
                }
            }
        }
        finishStream()
        return output.copyOf(outputSize)
    }

    /** The final byte's unused bits must be zero and nothing may follow the final meta-block. */
    private fun finishStream() {
        failIfOverran()
        if (bits.alignToByte() != 0) {
            throw IllegalArgumentException("Brotli final byte has non-zero fill bits.")
        }
        failIfOverran()
        if (bits.hasMore()) {
            throw IllegalArgumentException("Brotli stream has trailing bytes after the final meta-block.")
        }
    }

    /** Metadata bytes are not output and do not enter the window; they are only consumed. */
    private fun skipMetadata(header: BrotliMetadataHeader) {
        repeat(header.length) {
            bits.readBits(8)
            failIfOverran()
        }
    }

    private fun copyUncompressed(header: BrotliUncompressedHeader) {
        repeat(header.length) {
            writeByte(bits.readBits(8))
            failIfOverran()
        }
    }

    /**
     * Decodes one compressed meta-block into exactly `header.length` bytes.
     *
     * The loop reads insert-and-copy symbols from the current insert-and-copy block type, inserts
     * literals from the context-selected literal code, and either copies a back-reference, emits a
     * static-dictionary word, or ends the meta-block after a final literal-only command.
     */
    private fun decodeCompressed(header: BrotliCompressedHeader) {
        val literalBlocks = BlockState(header.literalBlockCategory)
        val insertCopyBlocks = BlockState(header.insertCopyBlockCategory)
        val distanceBlocks = BlockState(header.distanceBlockCategory)
        val metaBlockLength = header.length
        var produced = 0
        var commands = 0L
        val commandBudget = 2L * metaBlockLength + 16L

        while (produced < metaBlockLength) {
            if (++commands > commandBudget) {
                throw IllegalArgumentException("Brotli meta-block did not converge on its declared length.")
            }
            val insertCopyCode =
                header.insertCopyCodes[insertCopyBlocks.currentType()].readCode(bits)
            failIfOverran()
            if (insertCopyCode !in 0 until BrotliAlphabet.INSERT_COPY_ALPHABET_SIZE) {
                throw IllegalArgumentException("Brotli insert-and-copy symbol $insertCopyCode is out of range.")
            }
            val insertCode = BrotliAlphabet.insertLengthCode(insertCopyCode)
            val copyCode = BrotliAlphabet.copyLengthCode(insertCopyCode)
            val insertLength = BrotliAlphabet.INSERT_LENGTH_BASE[insertCode] +
                bits.readBits(BrotliAlphabet.INSERT_LENGTH_EXTRA_BITS[insertCode])
            val copyLength = BrotliAlphabet.COPY_LENGTH_BASE[copyCode] +
                bits.readBits(BrotliAlphabet.COPY_LENGTH_EXTRA_BITS[copyCode])
            failIfOverran()
            if (insertLength > metaBlockLength - produced) {
                throw IllegalArgumentException("Brotli insert length $insertLength exceeds the meta-block.")
            }

            repeat(insertLength) {
                val literalType = literalBlocks.currentType()
                val contextMode = header.literalContextModes[literalType]
                val contextId = BrotliContext.literalContextId(contextMode, previousByte, secondPreviousByte)
                val literalTree = header.literalCodes[header.literalContextMap[64 * literalType + contextId]]
                val literal = literalTree.readCode(bits)
                failIfOverran()
                writeByte(literal)
                produced++
            }
            if (produced == metaBlockLength) break

            val implicitDistance = BrotliAlphabet.usesImplicitDistanceZero(insertCopyCode)
            var distanceCode = -1
            val distance: Int
            if (implicitDistance) {
                distance = distanceRing[3]
            } else {
                val distanceType = distanceBlocks.currentType()
                val contextId = BrotliContext.distanceContextId(copyLength)
                val distanceTree =
                    header.distanceCodes[header.distanceContextMap[4 * distanceType + contextId]]
                distanceCode = distanceTree.readCode(bits)
                failIfOverran()
                distance = decodeDistance(distanceCode, header.npostfix, header.ndirect)
            }

            val maxAllowedDistance = minOf(windowSize, outputSize)
            if (distance > maxAllowedDistance) {
                produced += copyDictionary(distance, copyLength, maxAllowedDistance, metaBlockLength - produced)
            } else {
                if (distance.toLong() > maxWorkingBytes) {
                    throw BrotliLimitException(
                        "Brotli back-reference distance $distance exceeds the $maxWorkingBytes byte working limit.",
                    )
                }
                if (copyLength > metaBlockLength - produced) {
                    throw IllegalArgumentException("Brotli copy length $copyLength exceeds the meta-block.")
                }
                copyFromWindow(distance, copyLength)
                produced += copyLength
                if (!implicitDistance && distanceCode != 0) pushDistance(distance)
            }
        }
        failIfOverran()
    }

    /**
     * Emits the transformed static-dictionary word for a distance past the window.
     *
     * @return the number of bytes appended, which may be shorter than [copyLength] when a
     * omission transform shortens the base word.
     */
    private fun copyDictionary(
        distance: Int,
        copyLength: Int,
        maxAllowedDistance: Int,
        remaining: Int,
    ): Int {
        if (copyLength !in 4..24) {
            throw IllegalArgumentException("Brotli dictionary copy length $copyLength is out of range.")
        }
        val wordId = distance - (maxAllowedDistance + 1)
        val transformId = wordId ushr BrotliDictionary.NWORDS_BITS[copyLength]
        if (transformId > 120) {
            throw IllegalArgumentException("Brotli dictionary transform $transformId is out of range.")
        }
        val word = BrotliDictionaryTransforms.apply(transformId, BrotliDictionary.word(wordId, copyLength))
        if (word.size > remaining) {
            throw IllegalArgumentException("Brotli dictionary word exceeds the meta-block.")
        }
        for (byte in word) writeByte(byte.toInt() and 0xff)
        return word.size
    }

    /**
     * Resolves a distance symbol to a backward distance (RFC 7932 §4).
     *
     * Distance symbols 0..15 are the small ring-buffer references; 16..15+NDIRECT are direct
     * distances 1..NDIRECT; the rest carry extra bits.
     */
    private fun decodeDistance(code: Int, npostfix: Int, ndirect: Int): Int {
        if (code < 0 || code >= BrotliAlphabet.distanceAlphabetSize(npostfix, ndirect)) {
            throw IllegalArgumentException("Brotli distance symbol $code is out of range.")
        }
        if (code < 16) {
            val index = BrotliAlphabet.DISTANCE_SHORT_CODE_INDEX[code]
            val offset = BrotliAlphabet.DISTANCE_SHORT_CODE_OFFSET[code]
            val distance = distanceRing[index] + offset
            if (distance <= 0) {
                throw IllegalArgumentException("Brotli short distance symbol $code resolves to $distance.")
            }
            return distance
        }
        if (code < 16 + ndirect) return code - 16 + 1
        val adjusted = code - ndirect - 16
        val postfixMask = (1 shl npostfix) - 1
        val extraBits = 1 + (adjusted ushr (npostfix + 1))
        if (extraBits > 24) {
            throw IllegalArgumentException("Brotli distance symbol $code needs $extraBits extra bits.")
        }
        val highCode = adjusted ushr npostfix
        val lowCode = adjusted and postfixMask
        val extra = bits.readBits(extraBits)
        val offset = ((2 + (highCode and 1)) shl extraBits) - 4
        return ((offset + extra) shl npostfix) + lowCode + ndirect + 1
    }

    private fun pushDistance(distance: Int) {
        distanceRing[0] = distanceRing[1]
        distanceRing[1] = distanceRing[2]
        distanceRing[2] = distanceRing[3]
        distanceRing[3] = distance
    }

    /**
     * Appends [length] bytes from [distance] bytes back in the output.
     *
     * The copy is byte-by-byte so that an overlapping reference (distance less than length) repeats
     * freshly appended bytes, the LZ77 behaviour RFC 7932 §10 mandates.
     */
    private fun copyFromWindow(distance: Int, length: Int) {
        if (distance <= 0) throw IllegalArgumentException("Brotli backward distance $distance is not positive.")
        var readIndex = outputSize - distance
        repeat(length) {
            writeByte(output[readIndex].toInt() and 0xff)
            readIndex++
        }
    }

    private fun writeByte(value: Int) {
        val byte = value and 0xff
        if (outputSize == maxOutput) {
            throw BrotliLimitException("Brotli output exceeds the $maxOutput byte limit.")
        }
        if (outputSize == output.size) grow()
        output[outputSize] = byte.toByte()
        outputSize++
        secondPreviousByte = previousByte
        previousByte = byte
    }

    private fun grow() {
        val doubled = (output.size.toLong() * 2).coerceAtLeast(16L)
        val capped = doubled.coerceAtMost(maxOutput.toLong())
        output = output.copyOf(capped.coerceAtLeast(outputSize + 1L).toInt())
    }

    private fun failIfOverran() {
        if (bits.overran) {
            throw IllegalArgumentException("Brotli stream ended before the meta-block was complete.")
        }
    }

    /** One block category's block type and remaining block count (RFC 7932 §6). */
    private inner class BlockState(private val category: BrotliBlockCategory) {
        private val blockTypes = category.blockTypes
        private val typeCode = category.typeCode
        private val countCode = category.countCode
        private var blockType = 0
        private var previousBlockType = 1
        private var blockCount = category.firstCount

        /** Consumes one element of this category, reading a new block switch first when due. */
        fun currentType(): Int {
            if (blockTypes < 2) return 0
            if (blockCount == 0) refill()
            blockCount--
            return blockType
        }

        private fun refill() {
            val symbol = typeCode!!.readCode(bits)
            failIfOverran()
            val newType = when (symbol) {
                0 -> previousBlockType
                1 -> if (blockType + 1 == blockTypes) 0 else blockType + 1
                else -> symbol - 2
            }
            if (newType !in 0 until blockTypes) {
                throw IllegalArgumentException("Brotli block type symbol $symbol names block type $newType.")
            }
            previousBlockType = blockType
            blockType = newType
            val countSymbol = countCode!!.readCode(bits)
            failIfOverran()
            if (countSymbol !in BrotliAlphabet.BLOCK_COUNT_BASE.indices) {
                throw IllegalArgumentException("Brotli block count symbol $countSymbol is out of range.")
            }
            blockCount = BrotliAlphabet.BLOCK_COUNT_BASE[countSymbol] +
                bits.readBits(BrotliAlphabet.BLOCK_COUNT_EXTRA_BITS[countSymbol])
        }
    }
}
