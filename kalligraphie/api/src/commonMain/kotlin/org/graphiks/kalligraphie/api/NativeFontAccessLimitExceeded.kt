package org.graphiks.kalligraphie.api

/** Phase whose controlled byte admission refused native access. */
public enum class NativeFontAccessPhase {
    /** Capturing immutable exact source bytes. */
    SOURCE_CAPTURE,
    /** Constructing one independently owned native font context. */
    NATIVE_CREATION,
}
/** Controlled byte dimension exceeded; private OS/JVM object allocations are excluded. */
public enum class NativeFontAccessDimension {
    /** Exact source length of an individual face. */
    SOURCE_BYTES_PER_FACE,
    /** Complete immutable source total retained by the adapted snapshot. */
    CAPTURED_SOURCE_BYTES,
    /** Simultaneously admitted controlled copy/transfer/native-data buffers. */
    TRANSIENT_OWNED_BYTES,
}
/** Terminal native access policy refusal, reporting the attempted controlled allocation charge. */
public data class NativeFontAccessLimitExceeded(
    /** Refused operation phase. */
    public val phase: NativeFontAccessPhase,
    /** Refused controlled byte dimension. */
    public val dimension: NativeFontAccessDimension,
    /** Finite non-negative caller maximum. */
    public val maximum: Long,
    /** Attempted charge, greater than [maximum]; overflow saturates at Long.MAX_VALUE. */
    public val observed: Long,
) : FontError {
    /** Stable machine-readable limit code. */
    override val code: String = "font.native-font-access-limit-exceeded"
    /** Human-readable phase/dimension/charge diagnostic. */
    override val message: String = "Native access exceeded $dimension during $phase at $observed (maximum $maximum)."
    /** Admission concerns the exact source. */
    override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source
}
