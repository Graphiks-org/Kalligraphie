@file:OptIn(androidx.benchmark.ExperimentalBlackHoleApi::class)

package org.graphiks.kalligraphie.bench

import androidx.benchmark.BlackHole
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.io.encoding.Base64
import org.graphiks.kalligraphie.bench.fixture.ClasspathFixtureCorpus
import org.graphiks.kalligraphie.bench.scenarios.threadedInstrumentScenarios
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
 * The Android entry point of the portable measurement: the glyph profiles and, since the paragraph
 * facades and the portable Unicode analysis became `commonMain` code, the paragraph profiles too.
 *
 * androidx.benchmark's Gradle plugin targets the classic AGP extensions, which the KMP device-test
 * DSL of AGP 9 does not provide, so the benchmark runs dependency-only: `BenchmarkRule` under
 * `AndroidBenchmarkRunner`, one explicit test per scenario, the corpus read through the class path
 * the device-test APK packages. The scenario selection is derived from the platform's capability
 * identity and its harness instruments, never hand-written — a capability that stops being served,
 * or an instrument this harness lacks, fails the test that needs it.
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

    @Test
    public fun woffColdCapture() {
        runScenario("WoffColdCapture")
    }

    @Test
    public fun woff2ColdCapture() {
        runScenario("Woff2ColdCapture")
    }

    @Test
    public fun woff2ColdGlyph() {
        runScenario("Woff2ColdGlyph")
    }

    @Test
    public fun interactiveEdit() {
        runScenario("InteractiveEdit")
    }

    @Test
    public fun viewportLayout() {
        runScenario("ViewportLayout")
    }

    @Test
    public fun cancellation() {
        runScenario("Cancellation")
    }

    @Test
    public fun borrowedFragmentedUtf8Decode() {
        runScenario("BorrowedFragmentedUtf8Decode")
    }

    @Test
    public fun borrowedFragmentedUtf16Decode() {
        runScenario("BorrowedFragmentedUtf16Decode")
    }

    @Test
    public fun coldMixedBidiLine() {
        runScenario("ColdMixedBidiLine")
    }

    @Test
    public fun warmMixedBidiLine() {
        runScenario("WarmMixedBidiLine")
    }

    @Test
    public fun renderableConsumerColdSingleFont() {
        runScenario("RenderableConsumerColdSingleFont")
    }

    @Test
    public fun renderableConsumerWarmSingleFont() {
        runScenario("RenderableConsumerWarmSingleFont")
    }

    @Test
    public fun renderableConsumerColdMixedBidi() {
        runScenario("RenderableConsumerColdMixedBidi")
    }

    @Test
    public fun renderableConsumerWarmMixedBidi() {
        runScenario("RenderableConsumerWarmMixedBidi")
    }

    @Test
    public fun sessionColdSingleFont() {
        runScenario("SessionColdSingleFont")
    }

    @Test
    public fun sessionWarmSingleFont() {
        runScenario("SessionWarmSingleFont")
    }

    @Test
    public fun sessionColdMixedBidi() {
        runScenario("SessionColdMixedBidi")
    }

    @Test
    public fun sessionWarmMixedBidi() {
        runScenario("SessionWarmMixedBidi")
    }

    @Test
    public fun fontAssetRetainReopenCold() {
        runScenario("FontAssetRetainReopenCold")
    }

    @Test
    public fun fontAssetRetainReopenWarm() {
        runScenario("FontAssetRetainReopenWarm")
    }

    @Test
    public fun concurrentResolveWarm() {
        runScenario("ConcurrentResolveWarm")
    }

    @Test
    public fun styledSpanParagraphCold() {
        runScenario("StyledSpanParagraphCold")
    }

    @Test
    public fun styledSpanParagraphWarm() {
        runScenario("StyledSpanParagraphWarm")
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
        // The no-op guard is "nothing was consumed", not "every counter is positive": a cold
        // profile legitimately observes zero on counters that count reuse or hits, and a warm
        // session records its prepared-source bytes as zero because the backend was reused.
        check(counters.isNotEmpty() && counters.values.any { it > 0L }) {
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
        const val CORPUS_ID = "portable-glyphs-and-paragraph-layout"
        const val JSONL_MARKER = "KALLIGRAPHIE-BENCH-JSONL:"
        const val IDENTITY_MARKER = "KALLIGRAPHIE-BENCH-IDENTITY:"

        /**
         * The derived catalogue, built once for the class run instead of once per JUnit instance:
         * the paragraph scenarios parse their DejaVu and Amiri fixtures in their constructors, which
         * is untimed setup and must not repeat for every one of the forty-two tests.
         */
        val scenarios: Map<String, MeasurementScenario> = ClasspathFixtureCorpus().let { corpus ->
            ScenarioRegistry.select(
                corpus = corpus,
                identity = currentPortableCapabilityIdentity(),
                platformScenarios = threadedInstrumentScenarios(corpus),
            )
        }.associateBy { it.name }

        /** The nine fixtures the glyph and paragraph profiles read, and nothing else. */
        val MEASURED_CORPUS_PATHS = listOf(
            "/fonts/bungee-color/BungeeColor-Regular.ttf",
            "/fonts/twemoji-svginot-glyph5/TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf.base64",
            "/fonts/skia-ebdt-format1/ebdt_fmt1.ttf",
            "/fonts/liberation/LiberationSans-Regular.ttf",
            "/fonts/dejavu/DejaVuSans.ttf",
            "/fonts/amiri/Amiri-Regular.ttf",
            "/fonts/worksans/WorkSans[wght].ttf",
            "/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff",
            "/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff2",
        )

        /**
         * The publication contract refuses an unnamed commit and unknown corpus: the identity line
         * prints once per class run, before any profile, with the hashes of the nine fixtures the
         * profiles read.
         */
        @BeforeClass
        @JvmStatic
        public fun printIdentity() {
            // Touching the catalogue here builds it before the first timed test rather than during
            // one, and refuses a run whose capability identity serves nothing.
            check(scenarios.isNotEmpty()) { "The Android capability identity selects no scenario to measure." }
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
