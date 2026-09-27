package org.graphiks.kalligraphie.bench

/**
 * A harness instrument a scenario needs, beside the platform's capability identity.
 *
 * A capability says what the product serves; an instrument says what the harness can measure. The
 * two are independent: a platform can serve a route and still have no tool for the pattern one
 * profile exercises. A scenario that needs an absent instrument is therefore reported as deferred,
 * by name, instead of being re-measured with a different instrument under the same profile name —
 * which would publish another measurement under an unchanged identity.
 */
public enum class MeasurementInstrument(
    /** The profile that needs this instrument. */
    public val profileName: String,
    /** What the instrument does, in words, for a platform reporting it as missing. */
    public val description: String,
) {
    /**
     * Four persistent OS threads, started before timing and reused across waves, resolving one
     * shared render asset, with a per-thread allocation counter.
     *
     * `java.util.concurrent` and `com.sun.management.ThreadMXBean` supply it on the JVM, and ART
     * supplies the threads; Kotlin/Native has neither in this module's dependency surface, so the
     * profile that needs it is deferred there rather than replayed as a sequential loop.
     */
    PARALLEL_WORKERS(
        profileName = "ConcurrentResolveWarm",
        description = "four persistent OS threads resolving one shared render asset, with a per-thread allocation counter",
    ),
}

/** The instruments this platform's harness provides. */
public expect val measurementInstruments: Set<MeasurementInstrument>
