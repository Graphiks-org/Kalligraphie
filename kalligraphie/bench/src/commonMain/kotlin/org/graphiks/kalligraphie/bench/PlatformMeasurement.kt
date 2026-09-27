package org.graphiks.kalligraphie.bench

/**
 * Builds the measurement identity for [platformId] from what this platform can actually observe.
 *
 * Platform identifiers in use: `jvm`, `android`, `ios`. A runtime that cannot report a value says so
 * in its string rather than borrowing another platform's.
 */
public expect fun measurementIdentity(
    platformId: String,
    corpusId: String,
    fontHashes: Map<String, String>,
): MeasurementIdentity

/**
 * Memory and allocation figures for one profile.
 *
 * [allocatedBytes] is what the harness's allocation instrument reported for the timed operation, or
 * null when this platform has no allocator instrument — in which case the figures are
 * [MeasurementState.UNAVAILABLE] with the reason, and never an estimate presented as a measurement.
 */
public expect fun allocationFigures(allocatedBytes: Long?): Map<String, MeasurementValue>

/**
 * What this harness's allocation instrument can say about a thread.
 *
 * The JVM answers from `com.sun.management.ThreadMXBean`. ART and Kotlin/Native expose no
 * per-thread allocator counter, so they answer null — a figure taken there would be an estimate
 * wearing a measurement's name, and the profile would publish the unavailable state instead.
 */
public expect object ThreadAllocationProbe {
    /** The calling thread's allocated bytes so far, or null when this platform counts none. */
    public fun currentBytes(): Long?
}
