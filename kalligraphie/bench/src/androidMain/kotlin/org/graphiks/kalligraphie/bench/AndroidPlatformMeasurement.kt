package org.graphiks.kalligraphie.bench

import android.os.Build
import java.util.Properties

private const val IDENTITY_RESOURCE = "/bench-identity.properties"

private const val GC_POLICY =
    "System.gc() twice before and after each profile; no requested GC between samples"

/**
 * Android identity: what the instrumentation process can actually observe — the device model, the
 * Android version and API level, the ART runtime version. A process on the device cannot call git,
 * so the build writes the measured commit into the device-test APK as the same class-path resource
 * the corpus travels by, and a run without one fails loudly instead of publishing an unnamed
 * commit.
 */
public actual fun measurementIdentity(
    platformId: String,
    corpusId: String,
    fontHashes: Map<String, String>,
): MeasurementIdentity {
    val resource = checkNotNull(MeasurementIdentity::class.java.getResourceAsStream(IDENTITY_RESOURCE)) {
        "The device-test APK carries no $IDENTITY_RESOURCE: the androidBenchmarkIdentity task must run first."
    }
    val commit = resource.use { stream -> Properties().apply { load(stream) } }
        .getProperty("commit")
        ?.trim()
        .orEmpty()
    check(commit.isNotEmpty()) { "The measurement identity resource carries no commit." }
    return MeasurementIdentity(
        commit = commit,
        machine = Build.MODEL,
        operatingSystem = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        // AGP 9's KMP device-test DSL offers no build-type lever, so the instrumentation APK is
        // debuggable and the measurement runs on an emulator; androidx.benchmark's own
        // DEBUGGABLE and EMULATOR refusals are suppressed, and the bias is disclosed here
        // instead of silently shaping the numbers.
        runtime = "ART ${System.getProperty("java.vm.version")} (debuggable APK on an emulator; " +
            "DEBUGGABLE and EMULATOR refusals suppressed)",
        platformId = platformId,
        fontHashes = fontHashes,
        gcPolicy = GC_POLICY,
    )
}

/** Android figures: androidx.benchmark publishes no allocation metric in this mode, so the states say so. */
public actual fun allocationFigures(allocatedBytes: Long?): Map<String, MeasurementValue> = mapOf(
    "Allocated bytes" to MeasurementValue.unavailable("no allocation metric in the instrumentation benchmark"),
    "Retained heap" to MeasurementValue.unavailable("no live-set instrument on the Android harness"),
    "Native memory" to MeasurementValue.unavailable("no native allocator instrument on the Android harness"),
)

/**
 * The instruments ART provides: `java.util.concurrent` supplies the same persistent worker threads
 * the JVM harness uses, so a profile measured from workers runs here too. The per-thread allocation
 * counter is the JVM's own `com.sun.management` extension and is absent from ART, so that one figure
 * stays unavailable rather than estimated.
 */
public actual val measurementInstruments: Set<MeasurementInstrument> =
    setOf(MeasurementInstrument.PARALLEL_WORKERS)

/** ART exposes no per-thread allocator counter; the profile publishes the unavailable state. */
public actual object ThreadAllocationProbe {
    public actual fun currentBytes(): Long? = null
}
