@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import org.graphiks.kalligraphie.api.KalligraphieInternalApi

/**
 * The Brotli stream header and meta-block header (RFC 7932 §9.1 and §9.2).
 *
 * [readWbits] decodes the stream header's window-size code exactly once per stream. [read] then
 * parses one meta-block header; the decoder loops until a header reports [BrotliMetaBlockHeader.isLast],
 * which may be [BrotliLastEmptyHeader] or a non-empty `ISLAST = 1` compressed or metadata block.
 */
@KalligraphieInternalApi
internal object BrotliMetaBlock {
    /**
     * Decodes `WBITS` from the stream header (RFC 7932 §9.1).
     *
     * The window size is `(1 shl WBITS) - 16`. Returns `null` for the reserved pattern `0010001`,
     * which names no window and must reject the stream.
     */
    fun readWbits(bits: BrotliBits): Int? {
        if (bits.readBit() == 0) return 16
        val high = bits.readBits(3)
        if (high != 0) return 17 + high
        return when (val low = bits.readBits(3)) {
            0 -> 17
            1 -> null
            else -> 8 + low
        }
    }

    /**
     * Parses one meta-block header (RFC 7932 §9.2).
     *
     * `MNIBBLES` is always read once the stream has not ended at the `ISLAST`/`ISLASTEMPTY` pair,
     * independently of `ISLAST`. `MNIBBLES == 0` selects a metadata meta-block; otherwise `MLEN`
     * follows from `MNIBBLES` nibbles. `ISUNCOMPRESSED` is only read for a non-last, non-metadata
     * block.
     *
     * For [BrotliMetadataHeader] and [BrotliUncompressedHeader] the bit reader stops at the first
     * byte of the meta-block payload: the caller must consume `length` bytes before reading the
     * next header. For [BrotliCompressedHeader] the reader stops at the end of the header, right
     * before the first command.
     *
     * @throws IllegalArgumentException if a reserved field or zero-filled padding is invalid, or if
     * an embedded prefix-code description is malformed.
     */
    fun read(bits: BrotliBits): BrotliMetaBlockHeader {
        val isLast = bits.readBit() == 1
        if (isLast && bits.readBit() == 1) return BrotliLastEmptyHeader()

        val mnibbles = when (val raw = bits.readBits(2)) {
            3 -> 0
            else -> raw + 4
        }
        if (mnibbles == 0) {
            if (bits.readBit() != 0) {
                throw IllegalArgumentException("Brotli metadata meta-block reserved bit is not zero.")
            }
            val mskipBytes = bits.readBits(2)
            var mskipLenMinusOne = 0
            if (mskipBytes > 0) {
                mskipLenMinusOne = bits.readBits(8 * mskipBytes)
                if (mskipBytes > 1) {
                    val lastByte = (mskipLenMinusOne ushr (8 * (mskipBytes - 1))) and 0xff
                    if (lastByte == 0) {
                        throw IllegalArgumentException("Brotli metadata length has a redundant leading byte.")
                    }
                }
            }
            val metadataLength = if (mskipBytes == 0) 0 else mskipLenMinusOne + 1
            if (bits.alignToByte() != 0) {
                throw IllegalArgumentException("Brotli metadata meta-block has non-zero fill bits.")
            }
            return BrotliMetadataHeader(isLast, metadataLength)
        }

        val lengthMinusOne = bits.readBits(4 * mnibbles)
        if (mnibbles > 4) {
            val lastNibble = (lengthMinusOne ushr (4 * (mnibbles - 1))) and 0xf
            if (lastNibble == 0) {
                throw IllegalArgumentException("Brotli meta-block length has a redundant leading nibble.")
            }
        }
        val length = lengthMinusOne + 1

        if (isLast) return readCompressed(bits, isLast = true, length = length)
        if (bits.readBit() == 1) {
            if (bits.alignToByte() != 0) {
                throw IllegalArgumentException("Brotli uncompressed meta-block has non-zero ignored bits.")
            }
            return BrotliUncompressedHeader(isLast = false, length = length)
        }
        return readCompressed(bits, isLast = false, length = length)
    }

    /** Reads the block-type/context-map/prefix-code body of a compressed meta-block header. */
    private fun readCompressed(bits: BrotliBits, isLast: Boolean, length: Int): BrotliCompressedHeader {
        val literalCategory = readBlockCategory(bits)
        val insertCopyCategory = readBlockCategory(bits)
        val distanceCategory = readBlockCategory(bits)

        val npostfix = bits.readBits(2)
        val ndirect = bits.readBits(4) shl npostfix

        val literalContextModes = IntArray(literalCategory.blockTypes) { bits.readBits(2) }

        val numLiteralTrees = readVariableLengthUint8(bits)
        val literalContextMap = BrotliContext.readContextMap(
            bits,
            size = BrotliContext.LITERAL_CONTEXT_COUNT * literalCategory.blockTypes,
            numTrees = numLiteralTrees,
        )
        val numDistanceTrees = readVariableLengthUint8(bits)
        val distanceContextMap = BrotliContext.readContextMap(
            bits,
            size = BrotliContext.DISTANCE_CONTEXT_COUNT * distanceCategory.blockTypes,
            numTrees = numDistanceTrees,
        )

        val literalCodes =
            Array(numLiteralTrees) { BrotliHuffmanReader.read(bits, BrotliAlphabet.LITERAL_ALPHABET_SIZE) }
        val insertCopyCodes =
            Array(insertCopyCategory.blockTypes) {
                BrotliHuffmanReader.read(bits, BrotliAlphabet.INSERT_COPY_ALPHABET_SIZE)
            }
        val distanceAlphabetSize = BrotliAlphabet.distanceAlphabetSize(npostfix, ndirect)
        val distanceCodes =
            Array(numDistanceTrees) { BrotliHuffmanReader.read(bits, distanceAlphabetSize) }

        return BrotliCompressedHeader(
            isLast = isLast,
            length = length,
            literalBlockCategory = literalCategory,
            insertCopyBlockCategory = insertCopyCategory,
            distanceBlockCategory = distanceCategory,
            npostfix = npostfix,
            ndirect = ndirect,
            literalContextModes = literalContextModes,
            numLiteralTrees = numLiteralTrees,
            literalContextMap = literalContextMap,
            numDistanceTrees = numDistanceTrees,
            distanceContextMap = distanceContextMap,
            literalCodes = literalCodes,
            insertCopyCodes = insertCopyCodes,
            distanceCodes = distanceCodes,
        )
    }

    /** Reads one block category's `NBLTYPES` and, when it exceeds one, its switch codes. */
    private fun readBlockCategory(bits: BrotliBits): BrotliBlockCategory {
        val blockTypes = readVariableLengthUint8(bits)
        if (blockTypes < 2) return BrotliBlockCategory(blockTypes, null, null, firstCount = 0)
        val typeCode = BrotliHuffmanReader.read(bits, blockTypes + 2)
        val countCode = BrotliHuffmanReader.read(bits, BrotliAlphabet.BLOCK_COUNT_ALPHABET_SIZE)
        val countSymbol = countCode.readCode(bits)
        if (countSymbol !in BrotliAlphabet.BLOCK_COUNT_BASE.indices) {
            throw IllegalArgumentException("Brotli block count symbol $countSymbol is out of range.")
        }
        val firstCount = BrotliAlphabet.BLOCK_COUNT_BASE[countSymbol] +
            bits.readBits(BrotliAlphabet.BLOCK_COUNT_EXTRA_BITS[countSymbol])
        return BrotliBlockCategory(blockTypes, typeCode, countCode, firstCount)
    }

    /**
     * Reads the 1..11-bit count code shared by `NBLTYPESL`/`NBLTYPESI`/`NBLTYPESD` and
     * `NTREESL`/`NTREESD` (RFC 7932 §9.2).
     */
    private fun readVariableLengthUint8(bits: BrotliBits): Int {
        if (bits.readBit() == 0) return 1
        val nbits = bits.readBits(3)
        return (1 shl nbits) + bits.readBits(nbits) + 1
    }
}

/** A parsed Brotli meta-block header; the concrete type names the meta-block's payload. */
@KalligraphieInternalApi
internal sealed class BrotliMetaBlockHeader {
    /** True when this is the final meta-block of the stream. */
    abstract val isLast: Boolean
}

/** The terminal `ISLAST = 1`, `ISLASTEMPTY = 1` header that ends a Brotli stream. */
@KalligraphieInternalApi
internal class BrotliLastEmptyHeader : BrotliMetaBlockHeader() {
    override val isLast: Boolean get() = true
}

/**
 * A metadata meta-block (`MNIBBLES == 0`).
 *
 * The reader stops at [length] bytes of metadata, which are not part of the output; the caller must
 * consume them before reading the next header.
 */
@KalligraphieInternalApi
internal class BrotliMetadataHeader(
    override val isLast: Boolean,
    /** Number of metadata bytes that follow the header. */
    val length: Int,
) : BrotliMetaBlockHeader()

/**
 * An uncompressed meta-block.
 *
 * The reader stops at [length] literal bytes; the caller must copy them to the output before
 * reading the next header.
 */
@KalligraphieInternalApi
internal class BrotliUncompressedHeader(
    override val isLast: Boolean,
    /** Number of literal bytes that follow the header. */
    val length: Int,
) : BrotliMetaBlockHeader()

/**
 * A compressed meta-block header.
 *
 * Everything needed to decode the commands follows: the three block categories, the distance
 * parameters, the literal context modes and the context maps, and the literal, insert-and-copy and
 * distance prefix codes.
 */
@KalligraphieInternalApi
internal class BrotliCompressedHeader(
    override val isLast: Boolean,
    /** `MLEN`: the number of uncompressed bytes this meta-block produces. */
    val length: Int,
    val literalBlockCategory: BrotliBlockCategory,
    val insertCopyBlockCategory: BrotliBlockCategory,
    val distanceBlockCategory: BrotliBlockCategory,
    /** `NPOSTFIX`, the number of postfix bits for distance decoding. */
    val npostfix: Int,
    /** `NDIRECT`, the number of direct distance codes. */
    val ndirect: Int,
    /**
     * Context mode ([BrotliContext.MODE_LSB6]..[BrotliContext.MODE_SIGNED]) per literal block type.
     */
    val literalContextModes: IntArray,
    /** `NTREESL`, the number of literal prefix codes. */
    val numLiteralTrees: Int,
    /** Literal context map of `64 * NBLTYPESL` prefix-code indexes. */
    val literalContextMap: IntArray,
    /** `NTREESD`, the number of distance prefix codes. */
    val numDistanceTrees: Int,
    /** Distance context map of `4 * NBLTYPESD` prefix-code indexes. */
    val distanceContextMap: IntArray,
    val literalCodes: Array<BrotliHuffman>,
    val insertCopyCodes: Array<BrotliHuffman>,
    val distanceCodes: Array<BrotliHuffman>,
) : BrotliMetaBlockHeader()

/**
 * One block category's switch state (RFC 7932 §6).
 *
 * The first block always has type 0. When [blockTypes] is one there are no switch codes and no
 * first count; otherwise [typeCode] and [countCode] decode later switches and [firstCount] is the
 * length of the initial block.
 */
@KalligraphieInternalApi
internal class BrotliBlockCategory(
    val blockTypes: Int,
    val typeCode: BrotliHuffman?,
    val countCode: BrotliHuffman?,
    val firstCount: Int,
)
