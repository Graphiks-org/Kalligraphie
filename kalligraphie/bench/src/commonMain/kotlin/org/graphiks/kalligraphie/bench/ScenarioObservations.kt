package org.graphiks.kalligraphie.bench

/**
 * Counters that prove the timed operation did the work it claims.
 *
 * Every published scenario must state the glyphs, lines or operations it actually consumed: a
 * harness cannot tell a fast operation from an operation that did nothing. These counters are
 * evidence, never oracles — nothing schedules a test on them.
 *
 * Two recording styles coexist by design: [count] accumulates across invocations (glyphs
 * materialized, bytes consumed), while [record] stores the last observed value (a per-operation
 * figure such as a cancellation delay). The report names what it publishes either way.
 */
public class ScenarioObservations {
    private val counters = mutableMapOf<String, Long>()

    /** Adds [amount] to the counter named [name]. */
    public fun count(name: String, amount: Long = 1L) {
        require(name.isNotBlank()) { "An observation needs a name." }
        require(amount > 0) { "An observation increment must be positive." }
        counters[name] = (counters[name] ?: 0L) + amount
    }

    /** Records [value] as the counter named [name], replacing any previous reading. */
    public fun record(name: String, value: Long) {
        require(name.isNotBlank()) { "An observation needs a name." }
        counters[name] = value
    }

    /** Current readings, copied so callers cannot mutate them. */
    public fun snapshot(): Map<String, Long> = counters.toMap()
}
