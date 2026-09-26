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
