package org.graphiks.kalligraphie.bench

import org.graphiks.kalligraphie.conformance.PortableCapability

/** Which half of the measurement surface a scenario belongs to, and therefore who can run it. */
public enum class ScenarioRoute {
    /** Glyph materialization through the portable font routes: runs on every target. */
    PORTABLE_GLYPHS,

    /** End-to-end paragraph work through Unicode analysis: needs `END_TO_END_LAYOUT`. */
    PARAGRAPH_LAYOUT,
}

/**
 * One measured operation.
 *
 * A scenario owns what it reads, what one timed unit does, and what it consumed while doing it. It
 * does **not** time itself — kotlinx-benchmark or androidx.benchmark does, which is the point of
 * using the platform standards. The lifecycle matches what those tools can express:
 *
 * - [prepare] runs once per measurement run, untimed: it opens long-lived assets and seeds caches
 *   exactly like the original profiles' untimed setup.
 * - [operation] is the timed unit, invoked repeatedly; everything the report claims about latency
 *   refers to this body alone.
 * - [release] runs once after the run, untimed.
 *
 * Where the original harness excluded per-sample cleanup from timing via a finally hook, the
 * standard tools offer no per-invocation untimed hook, so the affected scenarios state the inclusion
 * in [timedBoundary] instead of silently changing what is measured.
 */
public interface MeasurementScenario {
    /** Stable profile name, e.g. `ColrColdNormalization`. */
    public val name: String

    /** What the operation exercises, in words, for the published report. */
    public val route: String

    /** Where the timed boundary starts and ends, including any harness-forced inclusion. */
    public val timedBoundary: String

    /** Cache state at the start of the timed operation. */
    public val cacheState: String

    /** Which route this scenario needs, so a platform only runs what it can serve. */
    public val scenarioRoute: ScenarioRoute

    /** The capability this scenario requires, or null when it is portable glyph work. */
    public val requiredCapability: PortableCapability?
        get() = when (scenarioRoute) {
            ScenarioRoute.PORTABLE_GLYPHS -> null
            ScenarioRoute.PARAGRAPH_LAYOUT -> PortableCapability.END_TO_END_LAYOUT
        }

    /** Untimed, once per measurement run: open long-lived assets and seed caches. */
    public fun prepare() {}

    /** The timed unit; the harness measures repeated invocations of it. */
    public fun operation()

    /** Untimed, once after the measurement run: release everything [prepare] opened. */
    public fun release() {}

    /**
     * A running checksum of everything the operations consumed, for the harness to hand to its
     * black hole. Without it, a scheduler may eliminate a measured operation that produces no
     * observable value — the fastest no-op in the report.
     */
    public val evidence: Long
        get() = 0L

    /** Reads the counters left by the timed operations. */
    public fun observations(): ScenarioObservations
}
