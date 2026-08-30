package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.SourceOffset
import org.graphiks.kalligraphie.api.SourceRange
import org.graphiks.kalligraphie.api.TextDecodingResult
import org.graphiks.kalligraphie.api.TextDiagnostic
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion

/** Creates canonical Unicode scalar snapshots from sliced UTF-8 or UTF-16 source. */
public object TextSnapshots {
    /**
     * Decodes copied UTF-8 slices without treating slice boundaries as scalar boundaries.
     *
     * Ill-formed input contributes one U+FFFD scalar and one diagnostic for each
     * Unicode maximal subpart.
     */
    public fun decodeUtf8(version: TextVersion, slices: List<TextSlice.Utf8>): TextDecodingResult {
        val bytes = joinUtf8Slices(slices)
        val scalars = mutableListOf<Int>()
        val sourceRanges = mutableListOf<SourceRange>()
        val diagnostics = mutableListOf<TextDiagnostic>()
        var offset = 0
        while (offset < bytes.size) {
            val decoded = decodeUtf8Scalar(bytes, offset)
            val range = sourceRange(version, offset, offset + decoded.length)
            scalars += decoded.scalar
            sourceRanges += range
            if (decoded.malformed) {
                diagnostics += TextDiagnostic(
                    code = "text.malformed-utf8",
                    sourceRange = range,
                    message = "Malformed UTF-8 maximal subpart was replaced with U+FFFD.",
                )
            }
            offset += decoded.length
        }
        return TextDecodingResult(TextSnapshot(version, scalars, sourceRanges), diagnostics)
    }

    /**
     * Decodes copied UTF-16 slices without treating slice boundaries as scalar boundaries.
     *
     * Each unpaired surrogate contributes one U+FFFD scalar and a diagnostic
     * retaining its source range.
     */
    public fun decodeUtf16(version: TextVersion, slices: List<TextSlice.Utf16>): TextDecodingResult {
        val codeUnits = joinUtf16Slices(slices)
        val scalars = mutableListOf<Int>()
        val sourceRanges = mutableListOf<SourceRange>()
        val diagnostics = mutableListOf<TextDiagnostic>()
        var offset = 0
        while (offset < codeUnits.size) {
            val first = codeUnits[offset].code
            val hasPair = first in HIGH_SURROGATE_RANGE &&
                offset + 1 < codeUnits.size &&
                codeUnits[offset + 1].code in LOW_SURROGATE_RANGE
            val length = if (hasPair) 2 else 1
            val range = sourceRange(version, offset, offset + length)
            when {
                hasPair -> {
                    val second = codeUnits[offset + 1].code
                    scalars += 0x10000 + ((first - HIGH_SURROGATE_RANGE.first) shl 10) +
                        (second - LOW_SURROGATE_RANGE.first)
                }

                first in SURROGATE_RANGE -> {
                    scalars += REPLACEMENT_SCALAR
                    diagnostics += TextDiagnostic(
                        code = "text.malformed-utf16",
                        sourceRange = range,
                        message = "Unpaired UTF-16 surrogate was replaced with U+FFFD.",
                    )
                }

                else -> scalars += first
            }
            sourceRanges += range
            offset += length
        }
        return TextDecodingResult(TextSnapshot(version, scalars, sourceRanges), diagnostics)
    }
}

private data class DecodedUtf8Scalar(
    val scalar: Int,
    val length: Int,
    val malformed: Boolean,
)

private fun decodeUtf8Scalar(bytes: ByteArray, offset: Int): DecodedUtf8Scalar {
    val first = bytes[offset].unsigned()
    if (first <= 0x7F) return DecodedUtf8Scalar(first, 1, malformed = false)

    val expectedLength = when (first) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> return malformedUtf8(length = 1)
    }
    if (offset + 1 >= bytes.size) return malformedUtf8(length = 1)

    val second = bytes[offset + 1].unsigned()
    if (!validSecondByte(first, second)) return malformedUtf8(length = 1)
    if (expectedLength == 2) {
        return DecodedUtf8Scalar(((first and 0x1F) shl 6) or (second and 0x3F), 2, malformed = false)
    }
    if (offset + 2 >= bytes.size) return malformedUtf8(length = 2)

    val third = bytes[offset + 2].unsigned()
    if (third !in CONTINUATION_RANGE) return malformedUtf8(length = 2)
    if (expectedLength == 3) {
        val scalar = ((first and 0x0F) shl 12) or ((second and 0x3F) shl 6) or (third and 0x3F)
        return DecodedUtf8Scalar(scalar, 3, malformed = false)
    }
    if (offset + 3 >= bytes.size) return malformedUtf8(length = 3)

    val fourth = bytes[offset + 3].unsigned()
    if (fourth !in CONTINUATION_RANGE) return malformedUtf8(length = 3)
    val scalar = ((first and 0x07) shl 18) or ((second and 0x3F) shl 12) or
        ((third and 0x3F) shl 6) or (fourth and 0x3F)
    return DecodedUtf8Scalar(scalar, 4, malformed = false)
}

private fun validSecondByte(first: Int, second: Int): Boolean = when (first) {
    0xE0 -> second in 0xA0..0xBF
    0xED -> second in 0x80..0x9F
    0xF0 -> second in 0x90..0xBF
    0xF4 -> second in 0x80..0x8F
    else -> second in CONTINUATION_RANGE
}

private fun malformedUtf8(length: Int): DecodedUtf8Scalar =
    DecodedUtf8Scalar(REPLACEMENT_SCALAR, length, malformed = true)

private fun joinUtf8Slices(slices: List<TextSlice.Utf8>): ByteArray {
    val copies = slices.map(TextSlice.Utf8::copyBytes)
    val result = ByteArray(checkedTotalSize(copies.map(ByteArray::size)))
    var destination = 0
    copies.forEach { bytes ->
        bytes.copyInto(result, destinationOffset = destination)
        destination += bytes.size
    }
    return result
}

private fun joinUtf16Slices(slices: List<TextSlice.Utf16>): CharArray {
    val copies = slices.map(TextSlice.Utf16::copyCodeUnits)
    val result = CharArray(checkedTotalSize(copies.map(CharArray::size)))
    var destination = 0
    copies.forEach { codeUnits ->
        codeUnits.copyInto(result, destinationOffset = destination)
        destination += codeUnits.size
    }
    return result
}

private fun checkedTotalSize(sizes: List<Int>): Int {
    var total = 0
    sizes.forEach { size ->
        require(size <= Int.MAX_VALUE - total) { "Combined text slices exceed the supported source size." }
        total += size
    }
    return total
}

private fun sourceRange(version: TextVersion, start: Int, endExclusive: Int): SourceRange =
    SourceRange(SourceOffset(version, start), SourceOffset(version, endExclusive))

private fun Byte.unsigned(): Int = toInt() and 0xFF

private const val REPLACEMENT_SCALAR: Int = 0xFFFD
private val CONTINUATION_RANGE: IntRange = 0x80..0xBF
private val HIGH_SURROGATE_RANGE: IntRange = 0xD800..0xDBFF
private val LOW_SURROGATE_RANGE: IntRange = 0xDC00..0xDFFF
private val SURROGATE_RANGE: IntRange = 0xD800..0xDFFF
