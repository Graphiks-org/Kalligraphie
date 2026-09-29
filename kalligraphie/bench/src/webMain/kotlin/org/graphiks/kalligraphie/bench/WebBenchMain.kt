package org.graphiks.kalligraphie.bench

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.graphiks.kalligraphie.bench.fixture.WebBenchFixtureCorpus
import org.graphiks.kalligraphie.bench.fixture.WebFixtureCorpusData
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import org.graphiks.kalligraphie.initialize

private const val CORPUS_ID = "portable-glyphs-and-paragraph-layout"

private const val CORPUS_DESCRIPTION =
    "the seven fixtures the glyph and paragraph profiles read: COLR v0, SVG-in-OT, EBDT bitmap, " +
        "TrueType, DejaVu, Amiri and variable Work Sans"

private const val WARMUP_ITERATIONS = 3

private const val MEASURED_ITERATIONS = 5

private val ITERATION_DURATION: Duration = 1.seconds

/**
 * The web entry point of the portable measurement.
 *
 * The WebAssembly HarfBuzz module is instantiated asynchronously, so this awaits [initialize] first.
 * It then runs the capability-selected scenarios with the same protocol the JVM and iOS drivers use
 * — three warm-up iterations and five measured iterations of one second — and prints the publication
 * contract's Markdown report to stdout. Scenarios whose route or instrument the web runtime does not
 * serve are reported as deferred, by name, exactly as on the other platforms.
 */
internal suspend fun runWebBenchmark() {
    initialize()
    val corpus = WebBenchFixtureCorpus
    val capabilities = currentPortableCapabilityIdentity()
    val selected = ScenarioRegistry.select(corpus, capabilities)
    check(selected.isNotEmpty()) { "The web capability identity selects no scenario to measure." }
    val deferred = ScenarioRegistry.deferred(corpus, capabilities) + ScenarioRegistry.deferredInstruments()
    val fontHashes = WebFixtureCorpusData.paths.sorted().associateWith { path -> corpus.sha256Hex(path) }
    val profiles = selected.map { scenario -> measure(scenario) }
    val report = MeasurementReport(
        identity = measurementIdentity("web", CORPUS_ID, fontHashes),
        corpusId = CORPUS_ID,
        corpusDescription = CORPUS_DESCRIPTION,
        profiles = profiles,
    )
    println(report.toMarkdown())
    if (deferred.isNotEmpty()) {
        println()
        deferred.forEach { scenario ->
            println("Deferred on this platform: ${scenario.name} — ${scenario.reason}")
        }
    }
}

/**
 * One measured profile: warm-up and measured iterations at [ITERATION_DURATION], each reporting
 * nanoseconds per operation. The web runtime exposes no allocator instrument, so the figures publish
 * the unavailable state rather than an estimate.
 */
private fun measure(scenario: MeasurementScenario): MeasurementProfile {
    scenario.prepare()
    repeat(WARMUP_ITERATIONS) { iterate(scenario) }
    val samples = buildList {
        repeat(MEASURED_ITERATIONS) { add(iterate(scenario)) }
    }
    val counters = scenario.observations().snapshot()
    scenario.release()
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

/** Repeats the scenario's timed operation for one iteration and returns the nanoseconds per operation. */
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
