package org.graphiks.kalligraphie.bench

import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import org.graphiks.kalligraphie.bench.fixture.IosBenchFixtureCorpus
import org.graphiks.kalligraphie.bench.fixture.IosFixtureCorpus
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv

private const val CORPUS_ID = "portable-glyphs"

private const val CORPUS_DESCRIPTION =
    "the four fixtures the portable glyph scenarios read: COLR v0, SVG-in-OT, EBDT bitmap and TrueType"

private const val WARMUP_ITERATIONS = 3

private const val MEASURED_ITERATIONS = 5

private val ITERATION_DURATION: Duration = 1.seconds

private const val OBSERVATIONS_ENVIRONMENT = "KALLIGRAPHIE_BENCH_OBSERVATIONS"

/**
 * The iOS simulator entry point of the portable measurement.
 *
 * kotlinx-benchmark's native backend skips every benchmark target whose Kotlin/Native target
 * differs from the build host, so no standard runner serves the simulator; this driver is the
 * pre-declared fallback. It runs the capability-selected scenarios with the protocol the JMH
 * configuration gives the JVM — three warm-up iterations and five measured iterations of one
 * second — prints the publication contract's Markdown report to stdout, and appends one JSONL
 * observation per scenario in the same schema the JVM driver writes, plus the per-iteration
 * samples that the JMH JSON carries on the JVM.
 */
public fun main() {
    val corpus = IosBenchFixtureCorpus
    val capabilities = currentPortableCapabilityIdentity()
    val selected = ScenarioRegistry.select(corpus, capabilities)
    check(selected.isNotEmpty()) { "The iOS capability identity selects no scenario to measure." }
    val deferred = ScenarioRegistry.deferred(corpus, capabilities)
    val fontHashes = IosFixtureCorpus.paths.sorted().associateWith { path -> corpus.sha256Hex(path) }
    val profiles = selected.map { scenario -> measure(scenario) }
    val report = MeasurementReport(
        identity = measurementIdentity("ios", CORPUS_ID, fontHashes),
        corpusId = CORPUS_ID,
        corpusDescription = CORPUS_DESCRIPTION,
        profiles = profiles,
    )
    println(report.toMarkdown())
    if (deferred.isNotEmpty()) {
        println()
        println("Deferred on this platform: ${deferred.joinToString(", ") { it.name }}")
    }
}

/**
 * One measured profile: warm-up and measured iterations at [ITERATION_DURATION], each reporting
 * nanoseconds per operation, with the collector asked to run before and after exactly as the JVM
 * asks for `System.gc()`. The counters are read before release, as the JVM teardown reads them.
 */
private fun measure(scenario: MeasurementScenario): MeasurementProfile {
    scenario.prepare()
    collectGarbageTwice()
    repeat(WARMUP_ITERATIONS) { iterate(scenario) }
    val samples = buildList {
        repeat(MEASURED_ITERATIONS) { add(iterate(scenario)) }
    }
    val counters = scenario.observations().snapshot()
    writeObservation(scenario, samples)
    scenario.release()
    collectGarbageTwice()
    return MeasurementProfile(
        name = scenario.name,
        route = scenario.route,
        timedBoundary = scenario.timedBoundary,
        cacheState = scenario.cacheState,
        warmupIterations = WARMUP_ITERATIONS,
        iterations = MEASURED_ITERATIONS,
        latency = Percentiles.of(samples),
        consumed = counters,
        figures = allocationFigures(null),
    )
}

/**
 * Repeats the scenario's timed operation for one iteration and returns the nanoseconds per
 * operation. The deadline check reads the monotonic clock once per operation — the same harness
 * structure JMH's timed loop has, so the reported boundary includes the same kind of overhead.
 */
private fun iterate(scenario: MeasurementScenario): Long {
    var operations = 0L
    val started = TimeSource.Monotonic.markNow()
    while (started.elapsedNow() < ITERATION_DURATION) {
        scenario.operation()
        operations++
    }
    check(operations > 0L) { "Scenario ${scenario.name} did not complete one operation in $ITERATION_DURATION." }
    scenario.observations().count("measuredOperations", operations)
    return started.elapsedNow().inWholeNanoseconds / operations
}

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
private fun collectGarbageTwice() {
    kotlin.native.runtime.GC.collect()
    kotlin.native.runtime.GC.collect()
}

@OptIn(ExperimentalForeignApi::class)
private fun writeObservation(scenario: MeasurementScenario, samples: List<Long>) {
    val target = getenv(OBSERVATIONS_ENVIRONMENT)?.toKString()?.trim().orEmpty().ifEmpty { return }
    val counters = scenario.observations().snapshot()
    val line = buildString {
        append("{\"scenario\":\"")
        append(Base64.UrlSafe.encode(scenario.name.encodeToByteArray()))
        append("\",\"evidence\":")
        append(scenario.evidence)
        append(",\"counters\":{")
        append(
            counters.entries.sortedBy { it.key }.joinToString(",") { (name, value) ->
                "\"${Base64.UrlSafe.encode(name.encodeToByteArray())}\":$value"
            },
        )
        append("},\"iterationsNs\":[")
        append(samples.joinToString(","))
        append("]}")
    }
    memScoped {
        val file = fopen(target, "a")
            ?: error("Could not open the observations file at $target (errno $errno).")
        try {
            fputs("$line\n", file)
        } finally {
            fclose(file)
        }
    }
}
