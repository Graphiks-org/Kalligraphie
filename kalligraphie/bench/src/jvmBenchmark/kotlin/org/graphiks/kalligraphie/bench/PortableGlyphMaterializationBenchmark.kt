package org.graphiks.kalligraphie.bench

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.encoding.Base64
import org.graphiks.kalligraphie.bench.fixture.JvmBenchmarkFixtureCorpus
import org.graphiks.kalligraphie.bench.scenarios.glyphMaterializationScenarios

/**
 * The JVM entry point of the portable glyph scenarios.
 *
 * The scenario is chosen by [scenarioName] (`@Param`), so the harness gives every scenario its own
 * fork: [Setup] prepares exactly the selected scenario's long-lived state, [measure] times its
 * operation, and [TearDown] releases the state and appends the accumulated counters to the
 * observations file named by `KALLIGRAPHIE_BENCH_OBSERVATIONS` — the fork is a separate JVM, so
 * counters not written there die with it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(kotlinx.benchmark.BenchmarkTimeUnit.NANOSECONDS)
public open class PortableGlyphMaterializationBenchmark {
    @Param(
        "ColrColdNormalization",
        "ColrWarmResolution",
        "SvgColdNormalization",
        "SvgWarmResolution",
        "BitmapColdDecode",
        "BitmapWarmResolution",
        "PaletteChange",
        "CachePressureAndEviction",
        "CooperativeCancellation",
        "TrueTypeColdPreparation",
        "TrueTypeWarmPreparation",
        "TrueTypeColdTextMapping",
        "TrueTypeWarmTextMapping",
        "TrueTypeColdMetrics",
        "TrueTypeWarmMetrics",
        "TrueTypeColdOutlines",
        "TrueTypeWarmOutlines",
        "TrueTypeColdDetach",
        "TrueTypeWarmDetach",
    )
    public var scenarioName: String = ""

    private var scenario: MeasurementScenario? = null

    @Setup
    public fun setup() {
        val corpus = JvmBenchmarkFixtureCorpus
        val selected = glyphMaterializationScenarios(corpus).firstOrNull { it.name == scenarioName }
            ?: error("Unknown glyph-materialization scenario: $scenarioName")
        selected.prepare()
        scenario = selected
    }

    @Benchmark
    public fun measure(blackhole: Blackhole) {
        val selected = checkNotNull(scenario) { "Scenario $scenarioName was not prepared." }
        // kotlinx-benchmark's runner is not jmh.Main and ignores JMH CLI profilers, so the per-thread
        // allocation probe is the allocation instrument, exactly as in the original harness. The two
        // probe calls sit inside the timed boundary; for sub-microsecond warm scenarios this is the
        // documented cost of keeping an allocation figure at all.
        val allocatedBefore = ThreadAllocationProbe.currentBytes()
        selected.operation()
        val allocatedAfter = ThreadAllocationProbe.currentBytes()
        val counters = selected.observations()
        if (allocatedBefore != null && allocatedAfter != null && allocatedAfter >= allocatedBefore) {
            counters.count("allocatedBytes", allocatedAfter - allocatedBefore)
        }
        counters.count("measuredOperations")
        blackhole.consume(selected.evidence)
    }

    @TearDown
    public fun tearDown() {
        val selected = scenario ?: return
        try {
            writeObservations(selected)
        } finally {
            selected.release()
            scenario = null
        }
    }

    private fun writeObservations(selected: MeasurementScenario) {
        val target = System.getProperty(OUTPUT_PROPERTY) ?: System.getenv(OUTPUT_ENVIRONMENT) ?: return
        val counters = selected.observations().snapshot()
        val line = buildString {
            append("{\"scenario\":\"")
            append(Base64.UrlSafe.encode(selected.name.encodeToByteArray()))
            append("\",\"evidence\":")
            append(selected.evidence)
            append(",\"counters\":{")
            append(
                counters.entries.sortedBy { it.key }.joinToString(",") { (name, value) ->
                    "\"${Base64.UrlSafe.encode(name.encodeToByteArray())}\":$value"
                },
            )
            append("}}")
        }
        val path = Path.of(target).toAbsolutePath().normalize()
        path.parent?.let(Files::createDirectories)
        Files.writeString(
            path,
            "$line\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private companion object {
        const val OUTPUT_PROPERTY = "kalligraphie.bench.observations"
        const val OUTPUT_ENVIRONMENT = "KALLIGRAPHIE_BENCH_OBSERVATIONS"
    }
}
