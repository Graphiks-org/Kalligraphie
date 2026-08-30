package org.graphiks.kalligraphie.api

import kotlin.jvm.JvmInline

/** Identifies one immutable revision of source text and all indices derived from it. */
@JvmInline
public value class TextVersion(
    /** Non-negative revision number supplied by the caller. */
    public val value: Long,
) {
    init {
        require(value >= 0L) { "Text version must be non-negative." }
    }
}

/** Scalar boundary within a particular [TextVersion]. */
public data class TextIndex(
    /** Version in which this boundary is meaningful. */
    public val version: TextVersion,
    /** Zero-based Unicode scalar boundary. */
    public val value: Int,
) {
    init {
        require(value >= 0) { "Text index must be non-negative." }
    }
}

/** Half-open range of Unicode scalar boundaries from one text version. */
public data class TextRange(
    /** Inclusive start boundary. */
    public val start: TextIndex,
    /** Exclusive end boundary. */
    public val endExclusive: TextIndex,
) {
    init {
        require(start.version == endExclusive.version) { "Text range boundaries must use the same version." }
        require(start.value <= endExclusive.value) { "Text range start must not follow its end." }
    }
}

/** Code-unit boundary within the source representation of a particular text version. */
public data class SourceOffset(
    /** Version in which this source boundary is meaningful. */
    public val version: TextVersion,
    /** Zero-based byte or UTF-16 code-unit boundary. */
    public val value: Int,
) {
    init {
        require(value >= 0) { "Source offset must be non-negative." }
    }
}

/** Half-open source range whose offsets belong to one text version. */
public data class SourceRange(
    /** Inclusive source boundary. */
    public val start: SourceOffset,
    /** Exclusive source boundary. */
    public val endExclusive: SourceOffset,
) {
    init {
        require(start.version == endExclusive.version) { "Source range boundaries must use the same version." }
        require(start.value <= endExclusive.value) { "Source range start must not follow its end." }
    }
}

/** Chooses the preceding or following scalar boundary for an interior source offset. */
public enum class SourceBias {
    /** Resolve an interior source offset to the preceding scalar boundary. */
    BEFORE,

    /** Resolve an interior source offset to the following scalar boundary. */
    AFTER,
}

/** Result of mapping a source offset to a scalar boundary. */
public sealed interface SourceIndexResult {
    /** Scalar boundary selected by the mapping. */
    public val index: TextIndex

    /** A source offset that already lies on an exact scalar boundary. */
    public data class Exact(
        /** Exact scalar boundary. */
        override val index: TextIndex,
    ) : SourceIndexResult

    /** A source offset inside a multi-unit scalar or malformed maximal subpart. */
    public data class Biased(
        /** Boundary selected according to the requested bias. */
        override val index: TextIndex,
        /** Scalar source range containing the requested offset. */
        public val containingRange: SourceRange,
    ) : SourceIndexResult
}

/** Owned source fragment accepted by the canonical text decoders. */
public sealed interface TextSlice {
    /** Immutable snapshot of one UTF-8 byte fragment. */
    public class Utf8(bytes: ByteArray) : TextSlice {
        private val capturedBytes: ByteArray = bytes.copyOf()

        /** Returns a defensive copy of this fragment's bytes. */
        public fun copyBytes(): ByteArray = capturedBytes.copyOf()
    }

    /** Immutable snapshot of one UTF-16 code-unit fragment. */
    public class Utf16(codeUnits: CharArray) : TextSlice {
        private val capturedCodeUnits: CharArray = codeUnits.copyOf()

        /** Returns a defensive copy of this fragment's UTF-16 code units. */
        public fun copyCodeUnits(): CharArray = capturedCodeUnits.copyOf()
    }
}

/** Immutable Unicode scalar snapshot with reversible source-boundary mapping. */
public class TextSnapshot(
    /** Version shared by every index and source range in this snapshot. */
    public val version: TextVersion,
    scalars: List<Int>,
    sourceRanges: List<SourceRange>,
) {
    /** Unicode scalar values in logical order. */
    public val scalars: List<Int> = scalars.immutableListSnapshot()

    /** Source range consumed by each scalar at the corresponding index. */
    public val sourceRanges: List<SourceRange> = sourceRanges.immutableListSnapshot()

    /** Complete half-open scalar range of this snapshot. */
    public val range: TextRange = TextRange(TextIndex(version, 0), TextIndex(version, this.scalars.size))

    private val sourceLength: Int = this.sourceRanges.lastOrNull()?.endExclusive?.value ?: 0

    init {
        require(this.scalars.size == this.sourceRanges.size) {
            "Each text scalar must have exactly one source range."
        }
        require(this.scalars.all(::isUnicodeScalar)) { "Text snapshots contain only Unicode scalar values." }
        var expectedStart = 0
        this.sourceRanges.forEach { sourceRange ->
            require(sourceRange.start.version == version) { "Source ranges must use the snapshot version." }
            require(sourceRange.start.value == expectedStart) { "Source ranges must be contiguous and ordered." }
            require(sourceRange.endExclusive.value > sourceRange.start.value) { "Source ranges must not be empty." }
            expectedStart = sourceRange.endExclusive.value
        }
    }

    /** Maps a source offset to an exact or bias-selected scalar boundary. */
    public fun sourceToTextIndex(offset: SourceOffset, bias: SourceBias): SourceIndexResult {
        require(offset.version == version) { "Source offset must use the snapshot version." }
        require(offset.value <= sourceLength) { "Source offset lies outside the snapshot." }

        sourceRanges.forEachIndexed { scalarIndex, sourceRange ->
            if (offset.value == sourceRange.start.value) {
                return SourceIndexResult.Exact(TextIndex(version, scalarIndex))
            }
            if (offset.value < sourceRange.endExclusive.value) {
                val boundary = if (bias == SourceBias.BEFORE) scalarIndex else scalarIndex + 1
                return SourceIndexResult.Biased(TextIndex(version, boundary), sourceRange)
            }
        }
        return SourceIndexResult.Exact(TextIndex(version, scalars.size))
    }

    /** Maps a scalar boundary to its exact source boundary. */
    public fun textIndexToSource(index: TextIndex): SourceOffset {
        require(index.version == version) { "Text index must use the snapshot version." }
        require(index.value <= scalars.size) { "Text index lies outside the snapshot." }
        return if (index.value == scalars.size) {
            SourceOffset(version, sourceLength)
        } else {
            sourceRanges[index.value].start
        }
    }

    /** Returns the source range consumed by the scalar beginning at [index]. */
    public fun sourceRange(index: TextIndex): SourceRange {
        require(index.version == version) { "Text index must use the snapshot version." }
        require(index.value < scalars.size) { "Text index does not identify a scalar in the snapshot." }
        return sourceRanges[index.value]
    }
}

/** Recoverable source-decoding issue retaining the complete malformed source span. */
public data class TextDiagnostic(
    /** Stable machine-readable diagnostic code. */
    public val code: String,
    /** Malformed source range replaced in the scalar snapshot. */
    public val sourceRange: SourceRange,
    /** Human-readable description of the decoding issue. */
    public val message: String,
) {
    init {
        require(code.isNotBlank()) { "Text diagnostic code must not be blank." }
        require(message.isNotBlank()) { "Text diagnostic message must not be blank." }
    }
}

/** Canonical decoded snapshot and its immutable diagnostics. */
public class TextDecodingResult(
    /** Scalar snapshot produced from the complete source. */
    public val snapshot: TextSnapshot,
    diagnostics: List<TextDiagnostic> = emptyList(),
) {
    /** Diagnostics in source order. */
    public val diagnostics: List<TextDiagnostic> = diagnostics.immutableListSnapshot()
}

private fun isUnicodeScalar(value: Int): Boolean = value in 0..0x10FFFF && value !in 0xD800..0xDFFF
