package org.graphiks.kalligraphie.bench

/**
 * iOS identity placeholder: Phase 2 wires the real Kotlin/Native identity (uname, device model,
 * simulator runtime). The strings stay non-blank and honest until then, because the publication
 * contract refuses blanks and forbids borrowing another platform's values.
 */
public actual fun measurementIdentity(
    platformId: String,
    corpusId: String,
    fontHashes: Map<String, String>,
): MeasurementIdentity = MeasurementIdentity(
    commit = "not-yet-wired-phase-2",
    machine = "pending iOS wiring (Phase 2)",
    operatingSystem = "pending iOS wiring (Phase 2)",
    runtime = "pending iOS wiring (Phase 2)",
    platformId = platformId,
    fontHashes = fontHashes,
    gcPolicy = "pending iOS wiring (Phase 2)",
)

/** iOS figures placeholder: Phase 2 decides the honest unavailable wording per figure. */
public actual fun allocationFigures(allocatedBytes: Long?): Map<String, MeasurementValue> = mapOf(
    "Allocated bytes" to MeasurementValue.unavailable("no allocation instrument in the Kotlin/Native harness"),
    "Retained heap" to MeasurementValue.unavailable("no live-set instrument in the Kotlin/Native harness"),
    "Native memory" to MeasurementValue.unavailable("no native allocator instrument in the Kotlin/Native harness"),
)
