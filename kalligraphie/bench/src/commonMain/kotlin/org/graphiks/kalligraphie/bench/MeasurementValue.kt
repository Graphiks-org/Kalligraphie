package org.graphiks.kalligraphie.bench

/**
 * How a published number was obtained.
 *
 * A figure that cannot be measured on a platform must be published as [UNAVAILABLE] rather than
 * omitted or guessed: an estimate presented as a measurement is exactly what the publication
 * contract forbids.
 */
public enum class MeasurementState {
    /** Read from the runtime or from an instrument that measures it. */
    MEASURED,

    /** Derived from a documented model rather than observed. */
    ESTIMATED,

    /** Not obtainable on this platform. */
    UNAVAILABLE,
}

/** One published figure, carrying how it was obtained. */
public data class MeasurementValue(
    /** How the figure was obtained. */
    public val state: MeasurementState,
    /** The figure, or null when the state is [MeasurementState.UNAVAILABLE]. */
    public val value: Long?,
    /** What the figure means, or why it is unavailable. */
    public val detail: String,
) {
    init {
        require(state == MeasurementState.UNAVAILABLE || value != null) {
            "A measured or estimated value must carry a number."
        }
        require(state != MeasurementState.UNAVAILABLE || value == null) {
            "An unavailable value must not carry a number."
        }
        require(detail.isNotBlank()) { "A published figure requires an explanation." }
    }

    public companion object {
        /** A figure observed on this platform. */
        public fun measured(value: Long, detail: String): MeasurementValue =
            MeasurementValue(MeasurementState.MEASURED, value, detail)

        /** A figure derived from a documented model. */
        public fun estimated(value: Long, detail: String): MeasurementValue =
            MeasurementValue(MeasurementState.ESTIMATED, value, detail)

        /** A figure this platform cannot produce, with the reason. */
        public fun unavailable(detail: String): MeasurementValue =
            MeasurementValue(MeasurementState.UNAVAILABLE, null, detail)
    }
}
