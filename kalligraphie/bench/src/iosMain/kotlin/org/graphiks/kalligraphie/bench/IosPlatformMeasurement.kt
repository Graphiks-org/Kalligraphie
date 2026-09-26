package org.graphiks.kalligraphie.bench

import kotlin.KotlinVersion
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSProcessInfo
import platform.posix.getenv
import platform.posix.uname
import platform.posix.utsname

private const val COMMIT_ENVIRONMENT = "KALLIGRAPHIE_BENCH_COMMIT"

private const val GC_POLICY =
    "kotlin.native.runtime.GC.collect() twice before and after each profile; no requested GC between samples"

/**
 * iOS identity: what the simulator process can actually observe — the machine and Darwin kernel
 * from `uname()`, the simulator's iOS version from Foundation, and the Kotlin/Native runtime
 * version. A `simctl`-spawned process cannot call git, so the launching task hands the measured
 * commit over through the environment, and a run without one fails loudly instead of publishing an
 * unnamed commit.
 */
@OptIn(ExperimentalForeignApi::class)
public actual fun measurementIdentity(
    platformId: String,
    corpusId: String,
    fontHashes: Map<String, String>,
): MeasurementIdentity {
    val commit = getenv(COMMIT_ENVIRONMENT)?.toKString()?.trim().orEmpty()
    check(commit.isNotEmpty()) {
        "The iOS measurement must name its commit: set $COMMIT_ENVIRONMENT in the spawning task."
    }
    memScoped {
        val system = alloc<utsname>()
        uname(system.ptr)
        return MeasurementIdentity(
            commit = commit,
            machine = system.machine.toKString(),
            operatingSystem =
                "iOS Simulator ${NSProcessInfo.processInfo.operatingSystemVersionString} (Darwin ${system.release.toKString()})",
            runtime = "Kotlin/Native ${KotlinVersion.CURRENT}",
            platformId = platformId,
            fontHashes = fontHashes,
            gcPolicy = GC_POLICY,
        )
    }
}

/** iOS figures: no allocation instrument exists in the native harness, so the states say so. */
public actual fun allocationFigures(allocatedBytes: Long?): Map<String, MeasurementValue> = mapOf(
    "Allocated bytes" to MeasurementValue.unavailable("no allocation instrument in the Kotlin/Native harness"),
    "Retained heap" to MeasurementValue.unavailable("no live-set instrument in the Kotlin/Native harness"),
    "Native memory" to MeasurementValue.unavailable("no native allocator instrument in the Kotlin/Native harness"),
)
