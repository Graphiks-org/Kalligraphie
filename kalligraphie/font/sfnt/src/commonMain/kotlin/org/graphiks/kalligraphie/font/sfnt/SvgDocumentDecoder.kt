package org.graphiks.kalligraphie.font.sfnt

import okio.Buffer
import okio.GzipSource
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.PaintGraphLimits

internal fun decodeSvgDocument(
    encoded: ByteArray,
    limits: PaintGraphLimits,
    remainingTotalDecodedBytes: Long,
): FontOperationResult<ByteArray> {
    val hasGzipMagic = encoded.size >= 2 && encoded[0] == GZIP_MAGIC_0 && encoded[1] == GZIP_MAGIC_1
    if (!hasGzipMagic) {
        return if (
            encoded.size > limits.maxSvgDecodedDocumentBytes ||
            encoded.size.toLong() > remainingTotalDecodedBytes
        ) {
            svgLimit("SVG decoded document-byte limit exceeded.")
        } else {
            FontOperationResult.Success(encoded)
        }
    }
    if (encoded.getOrNull(GZIP_COMPRESSION_METHOD_OFFSET) != GZIP_DEFLATE_METHOD) {
        return invalidGzip("SVG gzip document does not use the DEFLATE compression method.")
    }
    val flags = encoded.getOrNull(GZIP_FLAGS_OFFSET)?.toInt()
        ?: return invalidGzip("SVG gzip document header is truncated.")
    if (flags and GZIP_RESERVED_FLAGS_MASK != 0) {
        return invalidGzip("SVG gzip document uses reserved header flags.")
    }
    if (encoded.size > limits.maxSvgCompressedDocumentBytes) {
        return svgLimit("SVG compressed document-byte limit exceeded.")
    }

    return try {
        val compressed = Buffer().write(encoded)
        val gzip = GzipSource(compressed)
        val decoded = Buffer()
        val chunk = Buffer()
        var decodedBytes = 0L
        try {
            while (true) {
                val read = gzip.read(chunk, SVG_DECODE_CHUNK_BYTES)
                if (read == -1L) break
                if (
                    read > limits.maxSvgDecodedDocumentBytes.toLong() - decodedBytes ||
                    read > remainingTotalDecodedBytes - decodedBytes
                ) {
                    return svgLimit("SVG decoded document-byte limit exceeded.")
                }
                decoded.write(chunk, read)
                decodedBytes += read
            }
            FontOperationResult.Success(decoded.readByteArray())
        } finally {
            gzip.close()
            decoded.close()
            chunk.close()
        }
    } catch (_: Exception) {
        invalidGzip("SVG gzip document is malformed or fails its integrity checks.")
    }
}

private fun invalidGzip(message: String): FontOperationResult.Failure =
    FontOperationResult.Failure(
        FontError.FontDataFailure(
            code = "font.svg.invalid-gzip",
            message = message,
            location = FontDiagnosticLocation.Table("SVG "),
        ),
    )

private fun svgLimit(message: String): FontOperationResult.Failure =
    FontOperationResult.Failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Table("SVG ")))

private const val SVG_DECODE_CHUNK_BYTES: Long = 8_192L
private const val GZIP_COMPRESSION_METHOD_OFFSET: Int = 2
private const val GZIP_DEFLATE_METHOD: Byte = 8
private const val GZIP_FLAGS_OFFSET: Int = 3
private const val GZIP_RESERVED_FLAGS_MASK: Int = 0xE0
private const val GZIP_MAGIC_0: Byte = 0x1F
private const val GZIP_MAGIC_1: Byte = 0x8B.toByte()
