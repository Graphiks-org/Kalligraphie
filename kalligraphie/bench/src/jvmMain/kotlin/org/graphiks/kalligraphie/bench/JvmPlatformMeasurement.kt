package org.graphiks.kalligraphie.bench

import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val GC_POLICY: String =
    "System.gc() twice before and after each profile; no requested GC between samples"

/**
 * JVM identity: host name, OS and JVM version from the runtime, and the corpus hashes passed in by
 * the caller — the caller hashes what it actually read, so the report cannot drift from the corpus.
 */
public actual fun measurementIdentity(
    platformId: String,
    corpusId: String,
    fontHashes: Map<String, String>,
): MeasurementIdentity = MeasurementIdentity(
    commit = currentCommit(),
    machine = machineName(),
    operatingSystem =
        "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
    runtime = "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
    platformId = platformId,
    fontHashes = fontHashes,
    gcPolicy = GC_POLICY,
)

/**
 * JVM figures: JMH's `gc.alloc.rate.norm` provides the per-operation allocation, reported by the
 * caller as [allocatedBytes]. Live-set and native sizes have no instrument in this harness and are
 * published as unavailable rather than estimated.
 */
public actual fun allocationFigures(allocatedBytes: Long?): Map<String, MeasurementValue> = mapOf(
    "Allocated bytes" to (
        allocatedBytes?.let { MeasurementValue.measured(it, "JMH gc.alloc.rate.norm over the measured iterations") }
            ?: MeasurementValue.unavailable("this scenario ran without an allocation instrument")
        ),
    "Retained heap" to MeasurementValue.unavailable(
        "JMH reports allocation rate, not live-set size; an estimate would not be a measurement",
    ),
    "Native memory" to MeasurementValue.unavailable("no native allocator instrument on the JVM harness"),
)

/** Resolves the measured commit from the repository the benchmark is running in. */
public fun currentCommit(): String {
    val process = ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(repositoryRoot().toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    val completed = process.waitFor(30, TimeUnit.SECONDS)
    check(completed && process.exitValue() == 0 && output.matches(Regex("[0-9a-f]{40}"))) {
        "Could not identify the measured commit: $output"
    }
    return output
}

private fun repositoryRoot(): Path {
    var candidate: Path? = Path.of("").toAbsolutePath().normalize()
    while (candidate != null) {
        if (Files.exists(candidate.resolve(".git"))) return candidate
        candidate = candidate.parent
    }
    error("Could not locate the repository root from the working directory.")
}

private fun machineName(): String =
    runCatching { InetAddress.getLocalHost().hostName }
        .getOrElse { System.getenv("HOSTNAME") ?: "unknown-host" }
        .ifBlank { "unknown-host" }

/**
 * The per-thread allocation probe from the original harness: `com.sun.management.ThreadMXBean` when
 * the JVM supports it, null otherwise. The JMH gc profiler is the primary instrument; this probe
 * remains for scenarios that measure inside workers, where the profiler cannot see.
 */
public object ThreadAllocationProbe {
    private val bean: com.sun.management.ThreadMXBean? =
        (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)?.takeIf { candidate ->
            candidate.isThreadAllocatedMemorySupported && runCatching {
                if (!candidate.isThreadAllocatedMemoryEnabled) candidate.isThreadAllocatedMemoryEnabled = true
            }.isSuccess
        }

    public fun currentBytes(): Long? =
        bean?.getThreadAllocatedBytes(Thread.currentThread().threadId())?.takeIf { it >= 0L }
}
