@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.*

/** @suppress Assembly record preserving an original collection index, including rejected faces. */
@KalligraphieInternalApi
public data class TrueTypeCollectionFace(public val faceIndex: Int, public val metadata: FontOperationResult<ParsedTrueTypeFont>)

/** @suppress Bounded collection addressing reusing the standalone TrueType metadata validator. */
@KalligraphieInternalApi
public object TrueTypeCollectionReader {
    /**
     * Validates the header before examining bounded directories. Unsafe directory ranges are
     * source-fatal; safely addressed metadata failures remain individual face results.
     * [onFaceExamined] accounts every attempted directory, including source-fatal rejection.
     */
    public fun readMetadata(source: FontSource, maxFaces: Int, cancellationToken: CancellationToken = CancellationToken.none, onFaceExamined: () -> Unit = {}): FontOperationResult<List<TrueTypeCollectionFace>> {
        require(maxFaces > 0)
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        val bytes = source.copyBytes()
        fun invalid(message: String): FontOperationResult.Failure {
            val error = FontError.InvalidFontData(message)
            return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
        }
        if (bytes.size < 12 || bytes.decodeAsciiTag(0) != "ttcf") return invalid("Collection header is truncated or invalid.")
        val version = readUInt32(bytes, 4)
        if (version != 0x00010000u && version != 0x00020000u) {
            val error = FontError.UnsupportedContainer("Unsupported collection version.")
            return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
        }
        val count = readUInt32(bytes, 8)!!.toLong()
        val headerEnd = 12L + 4L * count
        val completeHeaderEnd = headerEnd + if (version == 0x00020000u) 12L else 0L
        if (count == 0L || completeHeaderEnd > bytes.size.toLong()) return invalid("Collection offset array exceeds source length.")
        if (version == 0x00020000u) {
            val position = headerEnd.toInt()
            val tag = readUInt32(bytes, position)!!
            val length = readUInt32(bytes, position + 4)!!.toLong()
            val offset = readUInt32(bytes, position + 8)!!.toLong()
            if (tag == 0u) {
                if (length != 0L || offset != 0L) return invalid("Absent collection DSIG has nonzero fields.")
            } else if (tag != 0x44534947u || length == 0L || offset < completeHeaderEnd || offset + length > bytes.size.toLong()) {
                return invalid("Collection DSIG range is invalid.")
            }
        }
        val faces = mutableListOf<TrueTypeCollectionFace>()
        repeat(minOf(count, maxFaces.toLong()).toInt()) { index ->
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            onFaceExamined()
            val offset = readUInt32(bytes, 12 + 4 * index)!!.toLong()
            if (offset < completeHeaderEnd || offset + 12L > bytes.size.toLong()) {
                val error = FontError.OutOfBounds("Collection face directory is out of bounds.", FontDiagnosticLocation.Face(index))
                return FontOperationResult.Failure(error, listOf(error.toDiagnostic(FontDiagnosticData(offset = offset, length = 12L, observedValue = offset + 12L, limit = bytes.size.toLong()))))
            }
            val directoryLength = readUInt16(bytes, offset.toInt() + 4)!!.toLong() * 16L
            if (offset + 12L + directoryLength > bytes.size.toLong()) {
                val error = FontError.OutOfBounds("Collection face directory exceeds source length.", FontDiagnosticLocation.Face(index))
                return FontOperationResult.Failure(error, listOf(error.toDiagnostic(FontDiagnosticData(offset = offset + 12L, length = directoryLength, observedValue = offset + 12L + directoryLength, limit = bytes.size.toLong()))))
            }
            val parsed = SfntReader.readMetadataAt(bytes, offset.toInt())
            faces += TrueTypeCollectionFace(index, parsed)
        }
        val diagnostics = if (count > maxFaces) listOf(FontError.ResourceLimitExceeded("Collection face examination limit reached.", FontDiagnosticLocation.Source).toDiagnostic()) else emptyList()
        return FontOperationResult.Success(faces, diagnostics)
    }
}
