@file:OptIn(androidx.benchmark.ExperimentalBlackHoleApi::class)

package org.graphiks.kalligraphie.bench

import androidx.benchmark.BlackHole
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.io.encoding.Base64
import org.graphiks.kalligraphie.bench.fixture.ClasspathFixtureCorpus
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Publishes one line to the instrumentation log — logcat tag `System.out` — with a marker a run
 * captures from the device's logcat buffer. Every file-backed channel was measured and rejected:
 * the test APK is uninstalled after the run, taking app-private files with it, and registering
 * extra files with androidx.benchmark's additional-output machinery fails the run's final phase.
 */
private fun publishLine(line: String) {
    println(line)
}

/**
 * The Android entry point of the portable glyph scenarios.
 *
 * androidx.benchmark's Gradle plugin targets the classic AGP extensions, which the KMP device-test
 * DSL of AGP 9 does not provide, so the benchmark runs dependency-only: `BenchmarkRule` under
 * `AndroidBenchmarkRunner`, one explicit test per scenario, the corpus read through the class path
 * the device-test APK packages. The scenario selection is derived from the platform's capability
 * identity, never hand-written — a capability that stops being served fails the test that needs it.
 *
 * The lifecycle maps onto JUnit the way the scenario contract maps onto JMH: [prepareScenario]
 * opens the long-lived state untimed before the measurement, [measureRepeated] times the operation
 * repeated times, and [releaseScenario] closes it after. The operations increment inside the timed
 * block — the documented cost of publishing a measured-operation counter at all — and the
 * collector is asked to run twice before and after each profile, mirroring the JVM policy.
 */
@RunWith(AndroidJUnit4::class)
public class AndroidGlyphMaterializationBenchmark {    @get:Rule
    public val benchmarkRule: BenchmarkRule = BenchmarkRule()

    private val scenarios: Map<String, MeasurementScenario> =
        ScenarioRegistry.select(ClasspathFixtureCorpus(), currentPortableCapabilityIdentity())
            .associateBy { it.name }

    @Test
    public fun colrColdNormalization() {
        runScenario("ColrColdNormalization")
    }

    @Test
    public fun colrWarmResolution() {
        runScenario("ColrWarmResolution")
    }

    @Test
    public fun svgColdNormalization() {
        runScenario("SvgColdNormalization")
    }

    @Test
    public fun svgWarmResolution() {
        runScenario("SvgWarmResolution")
    }

    @Test
    public fun bitmapColdDecode() {
        runScenario("BitmapColdDecode")
    }

    @Test
    public fun bitmapWarmResolution() {
        runScenario("BitmapWarmResolution")
    }

    @Test
    public fun paletteChange() {
        runScenario("PaletteChange")
    }

    @Test
    public fun cachePressureAndEviction() {
        runScenario("CachePressureAndEviction")
    }

    @Test
    public fun cooperativeCancellation() {
        runScenario("CooperativeCancellation")
    }

    @Test
    public fun trueTypeColdPreparation() {
        runScenario("TrueTypeColdPreparation")
    }

    @Test
    public fun trueTypeWarmPreparation() {
        runScenario("TrueTypeWarmPreparation")
    }

    @Test
    public fun trueTypeColdTextMapping() {
        runScenario("TrueTypeColdTextMapping")
    }

    @Test
    public fun trueTypeWarmTextMapping() {
        runScenario("TrueTypeWarmTextMapping")
    }

    @Test
    public fun trueTypeColdMetrics() {
        runScenario("TrueTypeColdMetrics")
    }

    @Test
    public fun trueTypeWarmMetrics() {
        runScenario("TrueTypeWarmMetrics")
    }

    @Test
    public fun trueTypeColdOutlines() {
        runScenario("TrueTypeColdOutlines")
    }

    @Test
    public fun trueTypeWarmOutlines() {
        runScenario("TrueTypeWarmOutlines")
    }

    @Test
    public fun trueTypeColdDetach() {
        runScenario("TrueTypeColdDetach")
    }

    @Test
    public fun trueTypeWarmDetach() {
        runScenario("TrueTypeWarmDetach")
    }

    private fun runScenario(name: String) {
        val target = scenarios[name]
            ?: error("The Android capability identity does not serve the scenario $name.")
        collectGarbageTwice()
        target.prepare()
        collectGarbageTwice()
        var operations = 0L
        benchmarkRule.measureRepeated {
            operations++
            target.operation()
            BlackHole.consume(target.evidence)
        }
        collectGarbageTwice()
        target.observations().count("measuredOperations", operations)
        val counters = target.observations().snapshot()
        check(counters.values.all { it > 0L }) {
            "The scenario $name consumed nothing on Android and measured a no-op: $counters."
        }
        publishLine(JSONL_MARKER + renderLine(name, target.evidence, counters))
        target.release()
        collectGarbageTwice()
    }

    private fun renderLine(name: String, evidence: Long, counters: Map<String, Long>): String = buildString {
        append("{\"scenario\":\"")
        append(Base64.UrlSafe.encode(name.encodeToByteArray()))
        append("\",\"evidence\":")
        append(evidence)
        append(",\"counters\":{")
        append(
            counters.entries.sortedBy { it.key }.joinToString(",") { (counterName, value) ->
                "\"${Base64.UrlSafe.encode(counterName.encodeToByteArray())}\":$value"
            },
        )
        append("}}")
    }

    private fun collectGarbageTwice() {
        System.gc()
        System.gc()
    }

    private companion object {
        const val CORPUS_ID = "portable-glyphs"
        const val JSONL_MARKER = "KALLIGRAPHIE-BENCH-JSONL:"
        const val IDENTITY_MARKER = "KALLIGRAPHIE-BENCH-IDENTITY:"

        /** The four fixtures the portable glyph scenarios read, and nothing else. */
        val MEASURED_CORPUS_PATHS = listOf(
            "/fonts/bungee-color/BungeeColor-Regular.ttf",
            "/fonts/twemoji-svginot-glyph5/TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf.base64",
            "/fonts/skia-ebdt-format1/ebdt_fmt1.ttf",
            "/fonts/liberation/LiberationSans-Regular.ttf",
        )

        /**
         * The publication contract refuses an unnamed commit and unknown corpus: the identity line
         * prints once per class run, before any profile, with the hashes of the four fixtures the
         * portable scenarios read.
         */
        @BeforeClass
        @JvmStatic
        public fun printIdentity() {
            val corpus = ClasspathFixtureCorpus()
            val hashes = MEASURED_CORPUS_PATHS.associateWith { path -> corpus.sha256Hex(path) }
            val identity = measurementIdentity("android", CORPUS_ID, hashes)
            val line = buildString {
                append("{\"commit\":\"")
                append(identity.commit)
                append("\",\"machine\":\"")
                append(identity.machine)
                append("\",\"operatingSystem\":\"")
                append(identity.operatingSystem)
                append("\",\"runtime\":\"")
                append(identity.runtime)
                append("\",\"gcPolicy\":\"")
                append(identity.gcPolicy)
                append("\",\"fontHashes\":{")
                append(
                    hashes.entries.sortedBy { it.key }.joinToString(",") { (path, hash) ->
                        "\"${Base64.UrlSafe.encode(path.encodeToByteArray())}\":\"$hash\""
                    },
                )
                append("}}")
            }
            publishLine("$IDENTITY_MARKER$line")
        }
    }
}
