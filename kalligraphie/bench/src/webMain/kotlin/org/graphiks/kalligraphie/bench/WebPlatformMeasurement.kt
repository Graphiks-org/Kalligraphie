package org.graphiks.kalligraphie.bench

import kotlin.KotlinVersion

private const val GC_POLICY =
    "no forced collection; the host runtime's collector is left to run on its own"

/**
 * Web identity: the JavaScript/Wasm runtime and the build-time measured revision.
 *
 * The browser or Node host exposes no stable machine or OS identity through the binding's type
 * surface, so the identity reports the web platform and the runtime version instead of borrowing
 * another platform's machine name.
 */
public actual fun measurementIdentity(
    platformId: String,
    corpusId: String,
    fontHashes: Map<String, String>,
): MeasurementIdentity {
    check(WEB_BENCHMARK_COMMIT.isNotEmpty() && WEB_BENCHMARK_COMMIT != "unknown") {
        "The web measurement must name its commit: rebuild with the generated web benchmark identity."
    }
    return MeasurementIdentity(
        commit = WEB_BENCHMARK_COMMIT,
        machine = "wasm32",
        operatingSystem = "Web",
        runtime = "Kotlin/Wasm or Kotlin/JS ${KotlinVersion.CURRENT}",
        platformId = platformId,
        fontHashes = fontHashes,
        gcPolicy = GC_POLICY,
    )
}

/** Web figures: neither the JS engine nor the Wasm runtime exposes a live-set or allocator instrument. */
public actual fun allocationFigures(allocatedBytes: Long?): Map<String, MeasurementValue> = mapOf(
    "Allocated bytes" to MeasurementValue.unavailable("no allocation instrument in the web harness"),
    "Retained heap" to MeasurementValue.unavailable("no live-set instrument in the web harness"),
    "Native memory" to MeasurementValue.unavailable("no native allocator instrument in the web harness"),
)

/**
 * No instrument of this harness exists on the single-threaded web runtime: there are no persistent
 * OS threads to start and no per-thread allocation counter. `ConcurrentResolveWarm` is therefore
 * reported as deferred here, by name, rather than replayed as a sequential loop.
 */
public actual val measurementInstruments: Set<MeasurementInstrument> = emptySet()

/** The web runtime exposes no per-thread allocator counter; the profiles publish the unavailable state. */
public actual object ThreadAllocationProbe {
    public actual fun currentBytes(): Long? = null
}
