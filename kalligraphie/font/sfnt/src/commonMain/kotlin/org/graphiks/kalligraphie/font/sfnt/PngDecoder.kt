package org.graphiks.kalligraphie.font.sfnt

import okio.Buffer
import okio.IOException
import okio.Inflater
import okio.InflaterSource
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapResourceLimit
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

/** Straight RGBA_8888 pixels decoded from one bounded PNG image. */
internal class DecodedPng(
    val width: Int,
    val height: Int,
    pixels: ByteArray,
) {
    private val captured: ByteArray = pixels.copyOf()

    init {
        require(width > 0 && height > 0) { "PNG dimensions must be positive." }
        require(captured.size == width * height * RGBA_BYTES_PER_PIXEL) { "PNG pixel buffer does not match its dimensions." }
    }

    /** Returns a caller-owned copy of the straight, non-premultiplied RGBA pixels. */
    fun copyPixels(): ByteArray = captured.copyOf()
}

/** Declared header of one bounded PNG image, validated without reading image data. */
internal class PngHeader(
    val width: Int,
    val height: Int,
    val pixelFormat: BitmapPixelFormat,
)

/**
 * Decodes one embedded PNG image into bounded straight RGBA_8888 pixels.
 *
 * Accepted subset: eight-bit truecolor (color type 2) and eight-bit truecolor with alpha (color
 * type 6), non-interlaced, every chunk CRC-verified. The accepted critical chunk set is IHDR,
 * PLTE (the suggested palette, ignored in truecolor), IDAT, and IEND; ancillary chunks are
 * skipped and every other critical chunk is refused. PLTE placement is not enforced once the
 * header is accepted, so a later PLTE remains ignored. Declared dimensions are validated against
 * the profile bounds before any inflation and the inflate stream is capped at the exact declared
 * scanline total, so a decompression bomb is refused before any pixels are allocated.
 */
internal object PngDecoder {
    /** Validates only the declared IHDR, with no chunk walk, no CRC checks beyond the header, and no inflation. */
    fun inspectHeader(
        encoded: ByteArray,
        limits: BitmapLimits,
        table: String,
    ): FontOperationResult<PngHeader> = parseHeader(encoded, 0, encoded.size, limits, table)

    /** Validates the declared IHDR of one bounded slice without copying its payload bytes. */
    fun inspectHeader(
        encoded: ByteArray,
        start: Int,
        end: Int,
        limits: BitmapLimits,
        table: String,
    ): FontOperationResult<PngHeader> = parseHeader(encoded, start, end, limits, table)

    fun decode(
        encoded: ByteArray,
        limits: BitmapLimits,
        table: String,
    ): FontOperationResult<DecodedPng> {
        val header = when (val parsed = parseHeader(encoded, 0, encoded.size, limits, table)) {
            is FontOperationResult.Success -> parsed.value
            is FontOperationResult.Failure -> return parsed
            is FontOperationResult.Cancelled -> return parsed
        }
        val width = header.width
        val height = header.height
        val compressed = Buffer()
        var offset = PNG_SIGNATURE_LENGTH
        var colorType = 0
        var sawHeader = false
        var sawData = false
        var sawEnd = false
        var compressedBytes = 0L
        while (offset < encoded.size) {
            if (sawEnd) return invalid("font.png.trailing-data", "PNG data follows the IEND chunk.", table)
            if (encoded.size - offset < CHUNK_HEADER_BYTES) {
                return invalid("font.png.truncated", "PNG chunk header is truncated.", table)
            }
            val length = readUInt32(encoded, offset)
                ?: return invalid("font.png.truncated", "PNG chunk length is truncated.", table)
            val dataStart = offset + CHUNK_HEADER_BYTES
            val dataEnd = dataStart + length
            if (length > Int.MAX_VALUE.toLong() || dataEnd > encoded.size.toLong() - CHUNK_CRC_BYTES) {
                return invalid("font.png.truncated", "PNG chunk data is truncated.", table)
            }
            if (readUInt32(encoded, dataEnd.toInt()) != (crc32(encoded, offset + 4, dataEnd.toInt()).toLong() and UINT_MASK)) {
                return invalid("font.png.invalid-crc", "PNG chunk CRC is invalid.", table)
            }
            when (val type = encoded.decodeToString(offset + 4, offset + 8)) {
                "IHDR" -> {
                    if (sawHeader) {
                        return invalid("font.png.invalid-chunk-order", "PNG IHDR must be the first chunk.", table)
                    }
                    colorType = encoded[dataStart + IHDR_COLOR_TYPE_INDEX].toInt() and 0xFF
                    sawHeader = true
                }

                "PLTE" -> Unit

                "IDAT" -> {
                    compressedBytes += length
                    if (compressedBytes > limits.maxCompressedBytes.toLong()) {
                        return limit(BitmapResourceLimit.COMPRESSED_BYTES, compressedBytes, limits.maxCompressedBytes, table)
                    }
                    compressed.write(encoded, dataStart, length.toInt())
                    sawData = true
                }

                "IEND" -> {
                    sawEnd = true
                }

                else -> if (type.isNotEmpty() && type[0] in 'A'..'Z') {
                    return invalid("font.png.unsupported-format", "PNG critical chunk $type is not supported.", table)
                }
            }
            offset = dataEnd.toInt() + CHUNK_CRC_BYTES
        }
        if (!sawData) return invalid("font.png.truncated", "PNG has no image data.", table)
        if (!sawEnd) return invalid("font.png.truncated", "PNG IEND chunk is missing.", table)

        val bytesPerPixel = if (colorType == PNG_COLOR_TYPE_TRUECOLOR_ALPHA) 4 else 3
        val rowStride = 1L + width.toLong() * bytesPerPixel.toLong()
        val expectedRawBytes = height.toLong() * rowStride
        if (expectedRawBytes > Int.MAX_VALUE.toLong()) {
            return limit(BitmapResourceLimit.DECODED_BYTES, expectedRawBytes, Int.MAX_VALUE, table)
        }
        val raw = try {
            val decoded = Buffer()
            val inflater = InflaterSource(compressed, Inflater())
            try {
                while (true) {
                    val read = inflater.read(decoded, PNG_INFLATE_CHUNK_BYTES)
                    if (read == -1L) break
                    if (decoded.size > expectedRawBytes) {
                        return limit(BitmapResourceLimit.DECODED_BYTES, decoded.size, expectedRawBytes, table)
                    }
                }
            } finally {
                inflater.close()
            }
            decoded.readByteArray()
        } catch (_: IOException) {
            return invalid("font.png.invalid-deflate", "PNG image data is malformed or fails its integrity checks.", table)
        }
        if (raw.size.toLong() != expectedRawBytes) {
            return invalid("font.png.truncated", "PNG image data is truncated.", table)
        }
        return unfilter(raw, width, height, bytesPerPixel, colorType, table)
    }

    private fun parseHeader(
        encoded: ByteArray,
        start: Int,
        end: Int,
        limits: BitmapLimits,
        table: String,
    ): FontOperationResult<PngHeader> {
        if (!hasSignature(encoded, start, end)) {
            return invalid("font.png.invalid-signature", "PNG signature is missing.", table)
        }
        if (end - start - PNG_SIGNATURE_LENGTH < CHUNK_HEADER_BYTES) {
            return invalid("font.png.truncated", "PNG chunk header is truncated.", table)
        }
        val length = readUInt32(encoded, start + PNG_SIGNATURE_LENGTH)
            ?: return invalid("font.png.truncated", "PNG chunk length is truncated.", table)
        val dataStart = start + PNG_SIGNATURE_LENGTH + CHUNK_HEADER_BYTES
        val dataEnd = dataStart + length
        if (length > Int.MAX_VALUE.toLong() || dataEnd > end.toLong() - CHUNK_CRC_BYTES) {
            return invalid("font.png.truncated", "PNG chunk data is truncated.", table)
        }
        if (readUInt32(encoded, dataEnd.toInt()) != (crc32(encoded, start + PNG_SIGNATURE_LENGTH + 4, dataEnd.toInt()).toLong() and UINT_MASK)) {
            return invalid("font.png.invalid-crc", "PNG chunk CRC is invalid.", table)
        }
        val type = encoded.decodeToString(start + PNG_SIGNATURE_LENGTH + 4, start + PNG_SIGNATURE_LENGTH + 8)
        if (type != "IHDR") {
            return firstChunkFailure(type, table)
        }
        if (length != IHDR_LENGTH) {
            return invalid("font.png.invalid-header", "PNG IHDR is not thirteen bytes.", table)
        }
        val declaredWidth = readUInt32(encoded, dataStart)
            ?: return invalid("font.png.truncated", "PNG IHDR is truncated.", table)
        val declaredHeight = readUInt32(encoded, dataStart + 4)
            ?: return invalid("font.png.truncated", "PNG IHDR is truncated.", table)
        val bitDepth = encoded[dataStart + 8].toInt() and 0xFF
        val colorType = encoded[dataStart + IHDR_COLOR_TYPE_INDEX].toInt() and 0xFF
        val compression = encoded[dataStart + 10].toInt() and 0xFF
        val filterMethod = encoded[dataStart + 11].toInt() and 0xFF
        val interlace = encoded[dataStart + 12].toInt() and 0xFF
        if (declaredWidth == 0L || declaredHeight == 0L ||
            declaredWidth > Int.MAX_VALUE.toLong() || declaredHeight > Int.MAX_VALUE.toLong()
        ) {
            return invalid("font.png.invalid-dimensions", "PNG dimensions are invalid.", table)
        }
        if (bitDepth != 8 ||
            (colorType != PNG_COLOR_TYPE_TRUECOLOR && colorType != PNG_COLOR_TYPE_TRUECOLOR_ALPHA) ||
            interlace != 0
        ) {
            return invalid("font.png.unsupported-format", "PNG image is not eight-bit non-interlaced truecolor.", table)
        }
        if (compression != 0 || filterMethod != 0) {
            return invalid("font.png.invalid-header", "PNG compression or filter method is invalid.", table)
        }
        val width = declaredWidth.toInt()
        val height = declaredHeight.toInt()
        if (width > limits.maxWidth) {
            return limit(BitmapResourceLimit.WIDTH, width.toLong(), limits.maxWidth, table)
        }
        if (height > limits.maxHeight) {
            return limit(BitmapResourceLimit.HEIGHT, height.toLong(), limits.maxHeight, table)
        }
        val declaredPixels = width.toLong() * height.toLong()
        if (declaredPixels > limits.maxPixels.toLong()) {
            return limit(BitmapResourceLimit.PIXELS, declaredPixels, limits.maxPixels, table)
        }
        val decodedBytes = declaredPixels * RGBA_BYTES_PER_PIXEL.toLong()
        if (decodedBytes > limits.maxDecodedBytes.toLong()) {
            return limit(BitmapResourceLimit.DECODED_BYTES, decodedBytes, limits.maxDecodedBytes, table)
        }
        return FontOperationResult.Success(PngHeader(width, height, BitmapPixelFormat.RGBA_8888))
    }

    private fun firstChunkFailure(type: String, table: String): FontOperationResult.Failure = when {
        type == "IDAT" -> invalid("font.png.invalid-chunk-order", "PNG IDAT precedes IHDR.", table)
        type == "IEND" -> invalid("font.png.invalid-chunk-order", "PNG IEND precedes IHDR.", table)
        type == "PLTE" -> invalid("font.png.invalid-chunk-order", "PNG PLTE precedes IHDR.", table)
        type.isNotEmpty() && type[0] in 'A'..'Z' ->
            invalid("font.png.unsupported-format", "PNG critical chunk $type is not supported.", table)
        else -> invalid("font.png.invalid-chunk-order", "PNG IHDR must be the first chunk.", table)
    }

    private fun unfilter(
        raw: ByteArray,
        width: Int,
        height: Int,
        bytesPerPixel: Int,
        colorType: Int,
        table: String,
    ): FontOperationResult<DecodedPng> {
        val pixels = ByteArray(width * height * RGBA_BYTES_PER_PIXEL)
        val rowBytes = width * bytesPerPixel
        val current = ByteArray(rowBytes)
        val previous = ByteArray(rowBytes)
        var rawOffset = 0
        var pixelOffset = 0
        for (row in 0 until height) {
            val filterType = raw[rawOffset++].toInt() and 0xFF
            if (filterType > PNG_FILTER_PAETH) {
                return invalid("font.png.unsupported-filter", "PNG filter type $filterType is not supported.", table)
            }
            raw.copyInto(current, 0, rawOffset, rawOffset + rowBytes)
            rawOffset += rowBytes
            unfilterRow(filterType, current, previous, bytesPerPixel)
            if (colorType == PNG_COLOR_TYPE_TRUECOLOR_ALPHA) {
                current.copyInto(pixels, pixelOffset)
                pixelOffset += rowBytes
            } else {
                var index = 0
                while (index < rowBytes) {
                    pixels[pixelOffset++] = current[index++]
                    pixels[pixelOffset++] = current[index++]
                    pixels[pixelOffset++] = current[index++]
                    pixels[pixelOffset++] = 0xFF.toByte()
                }
            }
            current.copyInto(previous)
        }
        return FontOperationResult.Success(DecodedPng(width, height, pixels))
    }

    private fun unfilterRow(type: Int, row: ByteArray, previous: ByteArray, bytesPerPixel: Int) {
        when (type) {
            0 -> Unit
            1 -> for (index in bytesPerPixel until row.size) {
                row[index] = (sample(row[index]) + sample(row[index - bytesPerPixel])).toByte()
            }
            2 -> for (index in row.indices) {
                row[index] = (sample(row[index]) + sample(previous[index])).toByte()
            }
            3 -> for (index in row.indices) {
                val left = if (index >= bytesPerPixel) sample(row[index - bytesPerPixel]) else 0
                row[index] = (sample(row[index]) + (left + sample(previous[index])) / 2).toByte()
            }
            4 -> for (index in row.indices) {
                val left = if (index >= bytesPerPixel) sample(row[index - bytesPerPixel]) else 0
                val up = sample(previous[index])
                val upLeft = if (index >= bytesPerPixel) sample(previous[index - bytesPerPixel]) else 0
                row[index] = (sample(row[index]) + paeth(left, up, upLeft)).toByte()
            }
            else -> throw IllegalArgumentException("PNG filter type $type is not supported.")
        }
    }

    private fun paeth(left: Int, up: Int, upLeft: Int): Int {
        val estimate = left + up - upLeft
        val leftDistance = kotlin.math.abs(estimate - left)
        val upDistance = kotlin.math.abs(estimate - up)
        val upLeftDistance = kotlin.math.abs(estimate - upLeft)
        return when {
            leftDistance <= upDistance && leftDistance <= upLeftDistance -> left
            upDistance <= upLeftDistance -> up
            else -> upLeft
        }
    }

    private fun sample(value: Byte): Int = value.toInt() and 0xFF

    private fun invalid(code: String, message: String, table: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Table(table)))

    private fun limit(dimension: BitmapResourceLimit, observed: Long, maximum: Int, table: String): FontOperationResult.Failure =
        limit(dimension, observed, maximum.toLong(), table)

    private fun limit(dimension: BitmapResourceLimit, observed: Long, maximum: Long, table: String): FontOperationResult.Failure =
        FontOperationResult.Failure(
            FontError.BitmapResourceLimitExceeded(
                limit = dimension,
                observed = observed,
                maximum = maximum,
                location = FontDiagnosticLocation.Table(table),
            ),
        )

    private fun hasSignature(encoded: ByteArray, start: Int, end: Int): Boolean =
        end - start >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { index -> encoded[start + index] == PNG_SIGNATURE[index] }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset > bytes.size - 4) return null
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    private fun crc32(bytes: ByteArray, start: Int, end: Int): Int {
        var crc = -1
        for (index in start until end) {
            crc = CRC32_TABLE[(crc xor bytes[index].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv()
    }
}

private val PNG_SIGNATURE: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private val CRC32_TABLE: IntArray = IntArray(256).also { table ->
    for (index in 0 until 256) {
        var value = index
        repeat(8) {
            value = if (value and 1 != 0) CRC32_POLYNOMIAL xor (value ushr 1) else value ushr 1
        }
        table[index] = value
    }
}

private const val CRC32_POLYNOMIAL: Int = 0xEDB88320.toInt()
private const val PNG_SIGNATURE_LENGTH: Int = 8
private const val CHUNK_HEADER_BYTES: Int = 8
private const val CHUNK_CRC_BYTES: Int = 4
private const val IHDR_LENGTH: Long = 13
private const val IHDR_COLOR_TYPE_INDEX: Int = 9
private const val RGBA_BYTES_PER_PIXEL: Int = 4
private const val PNG_COLOR_TYPE_TRUECOLOR: Int = 2
private const val PNG_COLOR_TYPE_TRUECOLOR_ALPHA: Int = 6
private const val PNG_FILTER_PAETH: Int = 4
private const val PNG_INFLATE_CHUNK_BYTES: Long = 8_192L
private const val UINT_MASK: Long = 0xFFFFFFFFL
