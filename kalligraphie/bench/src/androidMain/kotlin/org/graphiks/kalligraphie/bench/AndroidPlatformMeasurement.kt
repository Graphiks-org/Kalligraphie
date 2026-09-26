package org.graphiks.kalligraphie.bench

/**
 * Android identity placeholder: Phase 3 wires the real ART identity (device model, API level,
 * runtime version). The strings stay non-blank and honest until then, because the publication
 * contract refuses blanks and forbids borrowing another platform's values.
 */
public actual fun measurementIdentity(
    platformId: String,
    corpusId: String,
    fontHashes: Map<String, String>,
): MeasurementIdentity = MeasurementIdentity(
    commit = "not-yet-wired-phase-3",
    machine = "pending android wiring (Phase 3)",
    operatingSystem = "pending android wiring (Phase 3)",
    runtime = "pending android wiring (Phase 3)",
    platformId = platformId,
    fontHashes = fontHashes,
    gcPolicy = "pending android wiring (Phase 3)",
)

/** Android figures placeholder: Phase 3 wires androidx.benchmark's allocation tracking. */
public actual fun allocationFigures(allocatedBytes: Long?): Map<String, MeasurementValue> = mapOf(
    "Allocated bytes" to MeasurementValue.unavailable("wired by Phase 3 through androidx.benchmark"),
    "Retained heap" to MeasurementValue.unavailable("wired by Phase 3 through androidx.benchmark"),
    "Native memory" to MeasurementValue.unavailable("no native allocator instrument on the Android harness"),
)
