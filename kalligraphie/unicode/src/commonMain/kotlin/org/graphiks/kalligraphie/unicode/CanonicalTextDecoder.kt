package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.KalligraphieInternalApi
import org.graphiks.kalligraphie.api.SourceOffset
import org.graphiks.kalligraphie.api.SourceEncoding
import org.graphiks.kalligraphie.api.SourceRange
import org.graphiks.kalligraphie.api.TextDecodingFailure
import org.graphiks.kalligraphie.api.TextDecodingLimit
import org.graphiks.kalligraphie.api.TextDecodingOutcome
import org.graphiks.kalligraphie.api.TextDecodingProfile
import org.graphiks.kalligraphie.api.TextDecodingResult
import org.graphiks.kalligraphie.api.TextDiagnostic
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion

/** Creates canonical Unicode scalar snapshots from sliced UTF-8 or UTF-16 source. */
@OptIn(KalligraphieInternalApi::class)
public object TextSnapshots {
    /**
     * Decodes UTF-8 slices whose seams lie between complete Unicode decoding units.
     *
     * Ill-formed input contributes one U+FFFD scalar and one diagnostic for each
     * Unicode maximal subpart. Owned and borrowed slices are read directly without a joined
     * source buffer. An invalid seam throws [IllegalArgumentException]; use the profiled overload
     * to receive that source failure as a typed outcome.
     */
    public fun decodeUtf8(version: TextVersion, slices: List<TextSlice.Utf8>): TextDecodingResult =
        requireComplete(decodeUtf8(version, slices, TextDecodingProfile.unbounded, CancellationToken.none))

    /**
     * Decodes UTF-8 slices while enforcing [profile] and observing [cancellationToken].
     *
     * A source failure, limit failure, or cancellation discards every provisional scalar and
     * diagnostic: callers receive no partial snapshot. Slices are traversed directly through a
     * cursor and no joined byte buffer is created. Scalar limits are checked before the next
     * scalar is retained.
     */
    public fun decodeUtf8(
        version: TextVersion,
        slices: List<TextSlice.Utf8>,
        profile: TextDecodingProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): TextDecodingOutcome {
        val capturedSlices = slices.toList()
        sourceUnitLimit(capturedSlices, TextSlice.Utf8::size, profile)?.let { observed ->
            return TextDecodingOutcome.LimitExceeded(TextDecodingLimit.SOURCE_UNITS, observed)
        }
        if (cancellationToken.isCancellationRequested()) return TextDecodingOutcome.Cancelled
        val cursor = Utf8SliceCursor(capturedSlices)
        val scalars = PrimitiveIntBuffer()
        val sourceBoundaries = PrimitiveIntBuffer().apply { add(0) }
        val diagnostics = mutableListOf<TextDiagnostic>()
        while (!cursor.atEnd) {
            if (scalars.size % profile.cancellationCheckInterval == 0 && cancellationToken.isCancellationRequested()) {
                return TextDecodingOutcome.Cancelled
            }
            val decoded = decodeUtf8Scalar(cursor)
            if (cursor.crossesSliceBoundary(decoded.length)) {
                return TextDecodingOutcome.Failure(TextDecodingFailure.INVALID_SLICE_BOUNDARY)
            }
            val scalarCount = scalars.size.toLong() + 1
            if (scalarCount > profile.maxScalars) {
                return TextDecodingOutcome.LimitExceeded(TextDecodingLimit.SCALARS, scalarCount)
            }
            val start = cursor.sourceOffset
            val endExclusive = start + decoded.length
            scalars.add(decoded.scalar)
            sourceBoundaries.add(endExclusive)
            if (decoded.malformed) {
                diagnostics += TextDiagnostic(
                    code = "text.malformed-utf8",
                    sourceRange = sourceRange(version, SourceEncoding.UTF8, start, endExclusive),
                    message = "Malformed UTF-8 maximal subpart was replaced with U+FFFD.",
                )
            }
            cursor.advance(decoded.length)
        }
        if (cancellationToken.isCancellationRequested()) return TextDecodingOutcome.Cancelled
        return TextDecodingOutcome.Success(
            TextDecodingResult(
                TextSnapshot.fromPrimitiveTables(
                    version,
                    SourceEncoding.UTF8,
                    scalars.toIntArray(),
                    sourceBoundaries.toIntArray(),
                ),
                diagnostics,
            ),
        )
    }

    /**
     * Decodes UTF-16 slices whose seams lie between complete Unicode decoding units.
     *
     * Each unpaired surrogate contributes one U+FFFD scalar and a diagnostic
     * retaining its source range. Owned and borrowed slices are read directly without a joined
     * source buffer. An invalid seam throws [IllegalArgumentException]; use the profiled overload
     * to receive that source failure as a typed outcome.
     */
    public fun decodeUtf16(version: TextVersion, slices: List<TextSlice.Utf16>): TextDecodingResult =
        requireComplete(decodeUtf16(version, slices, TextDecodingProfile.unbounded, CancellationToken.none))

    /**
     * Decodes UTF-16 slices while enforcing [profile] and observing [cancellationToken].
     *
     * A source failure, limit failure, or cancellation discards every provisional scalar and
     * diagnostic: callers receive no partial snapshot. Slices are traversed directly through a
     * cursor and no joined code-unit buffer is created. Scalar limits are checked before the next
     * scalar is retained.
     */
    public fun decodeUtf16(
        version: TextVersion,
        slices: List<TextSlice.Utf16>,
        profile: TextDecodingProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): TextDecodingOutcome {
        val capturedSlices = slices.toList()
        sourceUnitLimit(capturedSlices, TextSlice.Utf16::size, profile)?.let { observed ->
            return TextDecodingOutcome.LimitExceeded(TextDecodingLimit.SOURCE_UNITS, observed)
        }
        if (cancellationToken.isCancellationRequested()) return TextDecodingOutcome.Cancelled
        val cursor = Utf16SliceCursor(capturedSlices)
        val scalars = PrimitiveIntBuffer()
        val sourceBoundaries = PrimitiveIntBuffer().apply { add(0) }
        val diagnostics = mutableListOf<TextDiagnostic>()
        while (!cursor.atEnd) {
            if (scalars.size % profile.cancellationCheckInterval == 0 && cancellationToken.isCancellationRequested()) {
                return TextDecodingOutcome.Cancelled
            }
            val first = cursor.peek(0).code
            val hasPair = first in HIGH_SURROGATE_RANGE &&
                cursor.peekOrNull(1)?.code in LOW_SURROGATE_RANGE
            val length = if (hasPair) 2 else 1
            if (cursor.crossesSliceBoundary(length)) {
                return TextDecodingOutcome.Failure(TextDecodingFailure.INVALID_SLICE_BOUNDARY)
            }
            val scalarCount = scalars.size.toLong() + 1
            if (scalarCount > profile.maxScalars) {
                return TextDecodingOutcome.LimitExceeded(TextDecodingLimit.SCALARS, scalarCount)
            }
            val start = cursor.sourceOffset
            val endExclusive = start + length
            when {
                hasPair -> {
                    val second = cursor.peek(1).code
                    scalars.add(0x10000 + ((first - HIGH_SURROGATE_RANGE.first) shl 10) +
                        (second - LOW_SURROGATE_RANGE.first)
                    )
                }

                first in SURROGATE_RANGE -> {
                    scalars.add(REPLACEMENT_SCALAR)
                    diagnostics += TextDiagnostic(
                        code = "text.malformed-utf16",
                        sourceRange = sourceRange(version, SourceEncoding.UTF16, start, endExclusive),
                        message = "Unpaired UTF-16 surrogate was replaced with U+FFFD.",
                    )
                }

                else -> scalars.add(first)
            }
            sourceBoundaries.add(endExclusive)
            cursor.advance(length)
        }
        if (cancellationToken.isCancellationRequested()) return TextDecodingOutcome.Cancelled
        return TextDecodingOutcome.Success(
            TextDecodingResult(
                TextSnapshot.fromPrimitiveTables(
                    version,
                    SourceEncoding.UTF16,
                    scalars.toIntArray(),
                    sourceBoundaries.toIntArray(),
                ),
                diagnostics,
            ),
        )
    }
}

private fun requireComplete(outcome: TextDecodingOutcome): TextDecodingResult = when (outcome) {
    is TextDecodingOutcome.Success -> outcome.value
    is TextDecodingOutcome.LimitExceeded -> error("The unbounded decoder exceeded ${outcome.limit}.")
    is TextDecodingOutcome.Failure -> throw IllegalArgumentException("Text decoding failed with ${outcome.reason}.")
    TextDecodingOutcome.Cancelled -> error("The non-cancellable decoder was cancelled.")
}

private inline fun <Slice> sourceUnitLimit(
    slices: List<Slice>,
    size: (Slice) -> Int,
    profile: TextDecodingProfile,
): Long? {
    var total = 0L
    val maximum = profile.maxSourceUnits.toLong()
    slices.forEach { slice ->
        total += size(slice).toLong()
        if (total > maximum) return total
    }
    return null
}

private data class DecodedUtf8Scalar(
    val scalar: Int,
    val length: Int,
    val malformed: Boolean,
)

private fun decodeUtf8Scalar(cursor: Utf8SliceCursor): DecodedUtf8Scalar {
    val first = cursor.peek(0).unsigned()
    if (first <= 0x7F) return DecodedUtf8Scalar(first, 1, malformed = false)

    val expectedLength = when (first) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> return malformedUtf8(length = 1)
    }
    val second = cursor.peekOrNull(1)?.unsigned() ?: return malformedUtf8(length = 1)

    if (!validSecondByte(first, second)) return malformedUtf8(length = 1)
    if (expectedLength == 2) {
        return DecodedUtf8Scalar(((first and 0x1F) shl 6) or (second and 0x3F), 2, malformed = false)
    }
    val third = cursor.peekOrNull(2)?.unsigned() ?: return malformedUtf8(length = 2)

    if (third !in CONTINUATION_RANGE) return malformedUtf8(length = 2)
    if (expectedLength == 3) {
        val scalar = ((first and 0x0F) shl 12) or ((second and 0x3F) shl 6) or (third and 0x3F)
        return DecodedUtf8Scalar(scalar, 3, malformed = false)
    }
    val fourth = cursor.peekOrNull(3)?.unsigned() ?: return malformedUtf8(length = 3)

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

private class Utf8SliceCursor(private val slices: List<TextSlice.Utf8>) {
    private var sliceIndex: Int = 0
    private var indexInSlice: Int = 0
    var sourceOffset: Int = 0
        private set

    init {
        skipEmptySlices()
    }

    val atEnd: Boolean
        get() = sliceIndex == slices.size

    fun peek(relativeIndex: Int): Byte = requireNotNull(peekOrNull(relativeIndex))

    fun peekOrNull(relativeIndex: Int): Byte? {
        require(relativeIndex >= 0)
        var candidateSlice = sliceIndex
        var candidateIndex = indexInSlice
        var remaining = relativeIndex
        while (candidateSlice < slices.size) {
            val slice = slices[candidateSlice]
            val available = slice.size - candidateIndex
            if (remaining < available) return slice[candidateIndex + remaining]
            remaining -= available
            candidateSlice++
            candidateIndex = 0
        }
        return null
    }

    fun crossesSliceBoundary(length: Int): Boolean = length > slices[sliceIndex].size - indexInSlice

    fun advance(length: Int) {
        require(length >= 0)
        indexInSlice += length
        sourceOffset += length
        skipEmptySlices()
    }

    private fun skipEmptySlices() {
        while (sliceIndex < slices.size && indexInSlice == slices[sliceIndex].size) {
            sliceIndex++
            indexInSlice = 0
        }
    }
}

private class Utf16SliceCursor(private val slices: List<TextSlice.Utf16>) {
    private var sliceIndex: Int = 0
    private var indexInSlice: Int = 0
    var sourceOffset: Int = 0
        private set

    init {
        skipEmptySlices()
    }

    val atEnd: Boolean
        get() = sliceIndex == slices.size

    fun peek(relativeIndex: Int): Char = requireNotNull(peekOrNull(relativeIndex))

    fun peekOrNull(relativeIndex: Int): Char? {
        require(relativeIndex >= 0)
        var candidateSlice = sliceIndex
        var candidateIndex = indexInSlice
        var remaining = relativeIndex
        while (candidateSlice < slices.size) {
            val slice = slices[candidateSlice]
            val available = slice.size - candidateIndex
            if (remaining < available) return slice[candidateIndex + remaining]
            remaining -= available
            candidateSlice++
            candidateIndex = 0
        }
        return null
    }

    fun crossesSliceBoundary(length: Int): Boolean = length > slices[sliceIndex].size - indexInSlice

    fun advance(length: Int) {
        require(length >= 0)
        indexInSlice += length
        sourceOffset += length
        skipEmptySlices()
    }

    private fun skipEmptySlices() {
        while (sliceIndex < slices.size && indexInSlice == slices[sliceIndex].size) {
            sliceIndex++
            indexInSlice = 0
        }
    }
}

private class PrimitiveIntBuffer(initialCapacity: Int = 16) {
    private var values: IntArray = IntArray(initialCapacity)
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == values.size) {
            val nextCapacity = if (values.isEmpty()) 1 else values.size + (values.size shr 1) + 1
            values = values.copyOf(nextCapacity)
        }
        values[size] = value
        size++
    }

    fun toIntArray(): IntArray = values.copyOf(size)
}

private fun sourceRange(
    version: TextVersion,
    encoding: SourceEncoding,
    start: Int,
    endExclusive: Int,
): SourceRange = SourceRange(
    SourceOffset(version, encoding, start),
    SourceOffset(version, encoding, endExclusive),
)

private fun Byte.unsigned(): Int = toInt() and 0xFF

private const val REPLACEMENT_SCALAR: Int = 0xFFFD
private val CONTINUATION_RANGE: IntRange = 0x80..0xBF
private val HIGH_SURROGATE_RANGE: IntRange = 0xD800..0xDBFF
private val LOW_SURROGATE_RANGE: IntRange = 0xDC00..0xDFFF
private val SURROGATE_RANGE: IntRange = 0xD800..0xDFFF
