package org.graphiks.kalligraphie.bench

/** Latency summary of one measured profile, in nanoseconds. */
public data class Percentiles(
    /** Median latency. */
    public val p50Nanos: Long,
    /** 95th percentile latency. */
    public val p95Nanos: Long,
    /** 99th percentile latency. */
    public val p99Nanos: Long,
) {
    init {
        require(p50Nanos <= p95Nanos && p95Nanos <= p99Nanos) {
            "Percentiles must be ordered: p50 <= p95 <= p99."
        }
    }

    public companion object {
        /** Percentiles of [samples], which must not be empty, using the nearest-rank definition. */
        public fun of(samples: List<Long>): Percentiles {
            require(samples.isNotEmpty()) { "Latency percentiles require at least one sample." }
            val sorted = samples.sorted()

            fun rank(percent: Int): Long {
                val index = ((percent / 100.0) * sorted.size).toInt().coerceIn(0, sorted.size - 1)
                return sorted[index]
            }

            return Percentiles(rank(50), rank(95), rank(99))
        }
    }
}
