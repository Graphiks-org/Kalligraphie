package org.graphiks.kalligraphie.bench

/**
 * One measured operation, with everything a reader needs to reproduce it and to trust it.
 *
 * A profile whose [consumed] counters are missing or zero is refused: the harness cannot tell a
 * fast operation from an operation that did nothing, and publishing a latency for a no-op is the
 * worst failure this module can produce.
 */
public data class MeasurementProfile(
    /** Stable profile name, e.g. `ColrColdNormalization`. */
    public val name: String,
    /** API route exercised, in words. */
    public val route: String,
    /** Where the timed boundary starts and ends. */
    public val timedBoundary: String,
    /** Cache state at the start of the timed operation. */
    public val cacheState: String,
    /** Warmup iterations the harness actually ran. */
    public val warmupIterations: Int,
    /** Measured iterations the harness actually ran. */
    public val iterations: Int,
    /** Latency summary. */
    public val latency: Percentiles,
    /** Counters proving the work happened, e.g. glyphs materialized. */
    public val consumed: Map<String, Long>,
    /** Memory and allocation figures, each carrying its own state. */
    public val figures: Map<String, MeasurementValue>,
    /** Observational p95 objective in nanoseconds, never a gate. */
    public val p95ObjectiveNanos: Long? = null,
) {
    init {
        require(name.isNotBlank() && route.isNotBlank()) { "A profile needs a name and a route." }
        require(timedBoundary.isNotBlank() && cacheState.isNotBlank()) {
            "A profile needs a timed boundary and a cache state."
        }
        require(warmupIterations > 0 && iterations > 0) {
            "A profile needs positive warmup and iteration counts."
        }
        require(consumed.isNotEmpty()) {
            "A profile must publish what it consumed, or it cannot be told from a no-op: $name."
        }
        require(consumed.values.all { it > 0 }) {
            "A profile that consumed nothing measured a no-op: $name."
        }
    }
}
