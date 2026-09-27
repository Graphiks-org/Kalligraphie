package org.graphiks.kalligraphie.bench

import java.io.File
import kotlin.io.encoding.Base64
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus
import org.graphiks.kalligraphie.bench.fixture.sha256Hex
import org.graphiks.kalligraphie.bench.scenarios.threadedInstrumentScenarios

private val JSON = Json { ignoreUnknownKeys = true }

/** The corpus id every platform publishes, so a report names the corpus it actually measured. */
private const val CORPUS_ID = "portable-glyphs-and-paragraph-layout"

/** The six fixtures the glyph and paragraph profiles read, on every platform that serves them. */
private val MEASURED_CORPUS_PATHS = listOf(
    "/fonts/bungee-color/BungeeColor-Regular.ttf",
    "/fonts/twemoji-svginot-glyph5/TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf.base64",
    "/fonts/skia-ebdt-format1/ebdt_fmt1.ttf",
    "/fonts/liberation/LiberationSans-Regular.ttf",
    "/fonts/dejavu/DejaVuSans.ttf",
    "/fonts/amiri/Amiri-Regular.ttf",
)

private const val CORPUS_DESCRIPTION =
    "the six fixtures the glyph and paragraph profiles read: COLR v0, SVG-in-OT, EBDT bitmap, " +
        "TrueType, DejaVu and Amiri"

/** Warm-up and measured iterations the iOS driver runs, in one-second iterations. */
private const val IOS_WARMUP_ITERATIONS = 3
private const val IOS_MEASURED_ITERATIONS = 5

/**
 * Joins the three platforms' raw measurement outputs into the publication contract's reports —
 * one Markdown per platform plus a comparison — without measuring anything itself.
 *
 * The metadata (route, timed boundary, cache state) comes from the scenario objects built against
 * the committed fixtures, the latency comes from each platform's measurement tool, and the counters
 * come from the observations the scenario runs published; a missing metadata entry or a missing
 * input fails loudly, because a report assembled from partial runs would look complete while
 * publishing nothing for the absent platform. Android is rendered in the same shape with honest
 * labels: androidx.benchmark's instrumentation mode publishes a median and an allocation count,
 * not percentiles, and a median promoted into a p95 would be an estimate posing as a measurement.
 */
public fun main(args: Array<String>) {
    val options = parseOptions(args)
    val corpus = ReportCorpus()
    // The catalogue is the whole module's, worker-pool profile included: it is the JVM that builds
    // this report, and that is where the instrument lives. Every platform's coverage is then checked
    // against it, so "this platform serves one profile fewer" is a named deferral, never a gap.
    val catalogue = ScenarioRegistry.all(corpus, threadedInstrumentScenarios(corpus))
    val metadata = catalogue.associateBy { it.name }
    check(metadata.isNotEmpty()) { "The scenario registry resolved no measurement metadata." }

    val jvmReport = renderJvmReport(options, metadata, corpus)
    val iosReport = renderIosReport(options, metadata)
    val androidReport = renderAndroidReport(options, metadata)
    requireCoverage(jvmReport, metadata.keys)
    requireCoverage(iosReport, metadata.keys)
    requireCoverage(androidReport, metadata.keys)
    val comparison = renderComparison(jvmReport, iosReport, androidReport, corpus)

    val out = File(options.getValue("out"))
    out.mkdirs()
    jvmReport.markdown.let { File(out, "report-jvm.md").writeText(it) }
    iosReport.markdown.let { File(out, "report-ios.md").writeText(it) }
    File(out, "report-android.md").writeText(androidReport.markdown)
    File(out, "report-comparison.md").writeText(comparison)
    println(
        "Measurement reports written to ${out.absolutePath}: " +
            "jvm=${jvmReport.profileCount}, ios=${iosReport.profileCount}, " +
            "android=${androidReport.profileCount} profiles.",
    )
    listOf(jvmReport, iosReport, androidReport).forEach { report ->
        if (report.deferred.isNotEmpty()) {
            println("Deferred on ${report.platformId}: ${report.deferred.sorted().joinToString(", ")}")
        }
    }
}

/**
 * Refuses a report that silently measures less than the module knows.
 *
 * A platform may leave a profile out only by saying so — through its capability identity or through
 * a harness instrument it does not have — and its own report is where that statement lands. A
 * missing profile that is not named is a gap, and a report with one would look complete.
 */
private fun requireCoverage(report: AggregatedReport, catalogue: Set<String>) {
    val published = report.measured
    val unexplained = catalogue - published - report.deferred
    check(unexplained.isEmpty()) {
        "The ${report.platformId} report measures ${published.size} of ${catalogue.size} profiles and " +
            "names no reason for: ${unexplained.sorted()}. Run its measurement again or publish the deferral."
    }
    val measuredAndDeferred = published intersect report.deferred
    check(measuredAndDeferred.isEmpty()) {
        "The ${report.platformId} report defers ${measuredAndDeferred.sorted()} and measures them anyway."
    }
}

private fun parseOptions(args: Array<String>): Map<String, String> = args.associate { argument ->
    check(argument.startsWith("--") && '=' in argument) {
        "Argument '$argument' must be --key=value; this aggregator reads the measurement runs' raw outputs."
    }
    val key = argument.removePrefix("--").substringBefore('=')
    val value = argument.substringAfter('=')
    check(value.isNotBlank()) { "Argument '$argument' carries no path." }
    key to value
}

private fun readInput(label: String, path: String): String {
    val file = File(path)
    check(file.isFile) {
        "The measurement report needs $label at $path — run the corresponding measurement first."
    }
    return file.readText()
}

private fun decodeKey(encoded: String): String = Base64.UrlSafe.decode(encoded).decodeToString()

/** One parsed platform report, shared by the per-platform renderers and the comparison. */
internal data class AggregatedReport(
    val platformId: String,
    val commit: String,
    val runtime: String,
    val profileCount: Int,
    /** The profiles this platform's report actually publishes. */
    val measured: Set<String>,
    /** The profiles this platform named as deferred in its own run outputs. */
    val deferred: Set<String>,
    /** p50 per measured profile, for the comparison table. */
    val p50Nanos: Map<String, Long>,
    val markdown: String,
)

// -------------------------------------------------------------------------------------------------
// JVM: JMH JSON for latency and protocol, observations JSONL for counters.
// -------------------------------------------------------------------------------------------------

/**
 * The corpus reader the aggregator compiles against: the JVM and Android device-test readers live
 * in test-only source sets the main compilation cannot see, and the aggregator never reads beyond
 * the class-path resources the measurement runs read, so it carries its own minimal reader.
 */
internal class ReportCorpus : FixtureCorpus {
    private val hashes = mutableMapOf<String, String>()

    override fun bytes(path: String): ByteArray =
        ReportCorpus::class.java.getResourceAsStream(path)?.use { stream -> stream.readBytes() }
            ?: error("The measurement corpus has no resource at $path.")

    override fun sha256Hex(path: String): String = hashes.getOrPut(path) { sha256Hex(bytes(path)) }
}

private fun renderJvmReport(
    options: Map<String, String>,
    metadata: Map<String, MeasurementScenario>,
    corpus: ReportCorpus,
): AggregatedReport {
    val jmhDir = File(options.getValue("jvm-jmh-dir"))
    val jmhJson = jmhDir.walkTopDown()
        .filter { it.isFile && it.name == "jvmBenchmark.json" }
        .maxByOrNull { it.parentFile.name }
    checkNotNull(jmhJson) {
        "No jvmBenchmark.json under ${jmhDir.absolutePath} — run :kalligraphie:bench:jvmBenchmarkBenchmark first."
    }
    val observations = readObservations(readInput("the JVM observations", options.getValue("jvm-obs")))

    val profiles = JSON.parseToJsonElement(jmhJson.readText()).jsonArray.map { element ->
        val entry = element.jsonObject
        val name = entry["params"]!!.jsonObject["scenarioName"]!!.jsonPrimitive.content
        val scenario = metadata[name]
            ?: error("The JMH output measures unknown scenario $name — the scenario list has drifted.")
        val metric = entry["primaryMetric"]!!.jsonObject
        val percentiles = metric["scorePercentiles"]!!.jsonObject
        val counters = observations.counters[name]
            ?: error("The JVM observations carry no counters for $name — the run file is incomplete.")
        val operations = counters.getValue("measuredOperations")
        val perOperationAllocation = counters["allocatedBytes"]?.let { it / operations }
        MeasurementProfile(
            name = name,
            route = scenario.route,
            timedBoundary = scenario.timedBoundary,
            cacheState = scenario.cacheState,
            warmupIterations = entry["warmupIterations"]!!.jsonPrimitive.content.toInt(),
            iterations = entry["measurementIterations"]!!.jsonPrimitive.content.toInt(),
            latency = Percentiles(
                p50Nanos = percentiles["50.0"]!!.jsonPrimitive.content.toDouble().roundToLong(),
                p95Nanos = percentiles["95.0"]!!.jsonPrimitive.content.toDouble().roundToLong(),
                p99Nanos = percentiles["99.0"]!!.jsonPrimitive.content.toDouble().roundToLong(),
            ),
            consumed = counters,
            figures = allocationFigures(perOperationAllocation),
        )
    }.sortedBy { profile -> metadata.keys.indexOfFirst { it == profile.name } }

    val identity = measurementIdentity(
        platformId = "jvm",
        corpusId = CORPUS_ID,
        fontHashes = MEASURED_CORPUS_PATHS.associateWith { path -> corpus.sha256Hex(path) },
    )
    val markdown = MeasurementReport(
        identity = identity,
        corpusId = CORPUS_ID,
        corpusDescription = CORPUS_DESCRIPTION,
        profiles = profiles,
    ).toMarkdown()
    return AggregatedReport(
        platformId = "jvm",
        commit = identity.commit,
        runtime = identity.runtime,
        profileCount = profiles.size,
        measured = profiles.map { profile -> profile.name }.toSet(),
        // The JVM serves the whole catalogue: it is where the worker instrument runs.
        deferred = emptySet(),
        p50Nanos = profiles.associate { it.name to it.latency.p50Nanos },
        markdown = markdown,
    )
}

// -------------------------------------------------------------------------------------------------
// iOS: the driver's observations carry per-iteration samples; the report carries the identity.
// -------------------------------------------------------------------------------------------------

private fun renderIosReport(options: Map<String, String>, metadata: Map<String, MeasurementScenario>): AggregatedReport {
    val markdownReport = readInput("the iOS measurement report", options.getValue("ios-report"))
    val identity = parseIosIdentity(markdownReport)
    val observations = readObservations(readInput("the iOS observations", options.getValue("ios-obs")))

    val profiles = observations.counters.map { (name, counters) ->
        val scenario = metadata[name]
            ?: error("The iOS observations measure unknown scenario $name — the scenario list has drifted.")
        val samples = observations.samples[name]
            ?: error("The iOS observations carry no per-iteration samples for $name — the run file is incomplete.")
        MeasurementProfile(
            name = name,
            route = scenario.route,
            timedBoundary = scenario.timedBoundary,
            cacheState = scenario.cacheState,
            warmupIterations = IOS_WARMUP_ITERATIONS,
            iterations = samples.size,
            latency = Percentiles.of(samples),
            consumed = counters,
            figures = iosFigures(),
        )
    }.sortedBy { profile -> metadata.keys.indexOfFirst { it == profile.name } }

    val markdown = MeasurementReport(
        identity = identity,
        corpusId = CORPUS_ID,
        corpusDescription = CORPUS_DESCRIPTION,
        profiles = profiles,
    ).toMarkdown()
    return AggregatedReport(
        platformId = "ios",
        commit = identity.commit,
        runtime = identity.runtime,
        profileCount = profiles.size,
        measured = profiles.map { profile -> profile.name }.toSet(),
        deferred = parseDeferredProfiles(markdownReport),
        p50Nanos = profiles.associate { it.name to it.latency.p50Nanos },
        markdown = markdown,
    )
}

/**
 * The profiles the driver declared deferred, read back from its own report tail — the line
 * `Deferred on this platform: <name> — <reason>`. Reading them from the run, rather than restating
 * the capability rules here, is what makes the coverage check a check: the aggregator compares what
 * each platform says it left out against what it published, and fails on anything unexplained.
 */
private fun parseDeferredProfiles(markdown: String): Set<String> = markdown.lineSequence()
    .filter { it.startsWith(DEFERRED_PREFIX) }
    .map { line -> line.removePrefix(DEFERRED_PREFIX).substringBefore(" — ").trim() }
    .filter { it.isNotEmpty() }
    .toSet()

/**
 * The figures for a platform the aggregator does not run on, in that platform's own words.
 *
 * [allocationFigures] is a platform declaration and the aggregator is a JVM program: calling it
 * would publish the JVM harness's reasons — "no native allocator instrument on the JVM harness",
 * "JMH reports allocation rate" — inside an iOS or Android report, which says the wrong harness lost
 * the figure. Each set below restates what that platform's own `allocationFigures` publishes.
 */
private fun iosFigures(): Map<String, MeasurementValue> = mapOf(
    "Allocated bytes" to MeasurementValue.unavailable("no allocation instrument in the Kotlin/Native harness"),
    "Retained heap" to MeasurementValue.unavailable("no live-set instrument in the Kotlin/Native harness"),
    "Native memory" to MeasurementValue.unavailable("no native allocator instrument in the Kotlin/Native harness"),
)

private fun androidFigures(): Map<String, MeasurementValue> = mapOf(
    "Allocated bytes" to MeasurementValue.unavailable("no allocation metric in the instrumentation benchmark"),
    "Retained heap" to MeasurementValue.unavailable("no live-set instrument on the Android harness"),
    "Native memory" to MeasurementValue.unavailable("no native allocator instrument on the Android harness"),
)

private const val DEFERRED_PREFIX = "Deferred on this platform: "

/**
 * Reads the driver JSONL lines: each is `{"scenario":b64url,"evidence":N,"counters":{…}}`, and the
 * iOS driver appends `"iterationsNs":[…]` with the per-iteration nanoseconds per operation.
 */
private class ObservedRun(val counters: MutableMap<String, Map<String, Long>>) {
    val samples = mutableMapOf<String, List<Long>>()
}

private fun readObservations(text: String): ObservedRun {
    val run = ObservedRun(mutableMapOf())
    text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
        val entry = JSON.parseToJsonElement(line).jsonObject
        val name = decodeKey(entry["scenario"]!!.jsonPrimitive.content)
        val counters = entry["counters"]!!.jsonObject.entries.associate { (key, value) ->
            decodeKey(key) to value.jsonPrimitive.long
        }
        // Same no-op guard as the profile model: one positive counter clears the run, while a
        // cold path's zero reuse-or-hit counters are themselves the evidence.
        check(counters.isNotEmpty() && counters.values.any { it > 0 }) {
            "The observations for $name consumed nothing and measured a no-op: $counters."
        }
        run.counters[name] = counters
        entry["iterationsNs"]?.let { samples ->
            run.samples[name] = samples.jsonArray.map { it.jsonPrimitive.long }
        }
    }
    return run
}

/**
 * The iOS report's header is our own generator's stable output; the identity is read back from it
 * so the published report cannot drift from the run that produced the observations.
 */
private fun parseIosIdentity(markdown: String): MeasurementIdentity {
    fun value(prefix: String): String =
        markdown.lineSequence().firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)?.trim()
            ?: error("The iOS report header carries no '$prefix' line.")

    val hashes = markdown.lineSequence()
        .dropWhile { !it.startsWith("- Corpus SHA-256:") }
        .drop(1)
        .takeWhile { it.startsWith("  - `") }
        .map { line ->
            val path = line.substringAfter("`").substringBefore("`")
            val hash = line.substringAfterLast("`").substringBefore("`")
            path to hash
        }
        .toMap()
    check(hashes.isNotEmpty()) { "The iOS report header carries no corpus hashes." }
    return MeasurementIdentity(
        commit = value("- Commit: `").removeSuffix("`"),
        machine = value("- Machine: "),
        operatingSystem = value("- OS: "),
        runtime = value("- Runtime: "),
        platformId = "ios",
        fontHashes = hashes,
        gcPolicy = value("- Cache policy: "),
    )
}

// -------------------------------------------------------------------------------------------------
// Android: identity from the logcat identity line, counters from the JSONL lines, latency as the
// tool's median only — rendered in the same shape as the model's Markdown, with honest labels.
// -------------------------------------------------------------------------------------------------

private fun renderAndroidReport(options: Map<String, String>, metadata: Map<String, MeasurementScenario>): AggregatedReport {
    val raw = readInput("the Android observations", options.getValue("android-obs"))
    val identityLine = raw.lineSequence().mapNotNull { line -> markerPayload(line, IDENTITY_MARKER) }.firstOrNull()
        ?: error("The Android observations carry no '$IDENTITY_MARKER' identity line — the run's logcat capture is incomplete.")
    val identityEntry = JSON.parseToJsonElement(identityLine).jsonObject
    val identity = MeasurementIdentity(
        commit = identityEntry["commit"]!!.jsonPrimitive.content,
        machine = identityEntry["machine"]!!.jsonPrimitive.content,
        operatingSystem = identityEntry["operatingSystem"]!!.jsonPrimitive.content,
        runtime = identityEntry["runtime"]!!.jsonPrimitive.content,
        platformId = "android",
        fontHashes = identityEntry["fontHashes"]!!.jsonObject.entries.associate { (key, value) ->
            decodeKey(key) to value.jsonPrimitive.content
        },
        gcPolicy = identityEntry["gcPolicy"]!!.jsonPrimitive.content,
    )
    val counters = raw.lineSequence()
        .mapNotNull { line -> markerPayload(line, JSONL_MARKER) }
        .associate { payload ->
            val entry = JSON.parseToJsonElement(payload).jsonObject
            val name = decodeKey(entry["scenario"]!!.jsonPrimitive.content)
            name to entry["counters"]!!.jsonObject.entries.associate { (key, value) ->
                decodeKey(key) to value.jsonPrimitive.long
            }
        }
    check(counters.isNotEmpty()) { "The Android observations carry no scenario counters." }

    val mediansDir = File(options.getValue("android-medians"))
    val medians = mediansDir.listFiles { file -> file.name.startsWith("additionaltestoutput.benchmark.message_") }
        ?.associate { file ->
            // The message file names the JUnit method (camelCase); the registry names the scenario
            // (PascalCase), so the first letter is normalized to the registry's form.
            val method = file.name
                .removePrefix("additionaltestoutput.benchmark.message_")
                .removeSuffix(".txt")
                .substringAfterLast('.')
            method.replaceFirstChar { it.uppercase() } to readMedian(file)
        } ?: emptyMap()

    val unknown = (counters.keys + medians.keys) - metadata.keys
    check(unknown.isEmpty()) { "The Android outputs measure unknown scenarios: $unknown." }
    val ordered = metadata.values.mapNotNull { metadataScenario ->
        val name = metadataScenario.name
        val scenarioCounters = counters[name] ?: return@mapNotNull null
        AndroidProfile(
            metadata = metadataScenario,
            counters = scenarioCounters,
            medianNanos = medians[name]?.first,
            allocationCount = medians[name]?.second,
        )
    }
    check(ordered.isNotEmpty()) { "The Android medians directory matched no observed scenario." }

    val markdown = buildString {
        appendLine("# android measurement")
        appendLine()
        appendLine("- Commit: `${identity.commit}`")
        appendLine("- Platform: android")
        appendLine("- Machine: ${identity.machine}")
        appendLine("- OS: ${identity.operatingSystem}")
        appendLine("- Runtime: ${identity.runtime}")
        appendLine("- Cache policy: ${identity.gcPolicy}")
        appendLine("- Corpus: `$CORPUS_ID` — $CORPUS_DESCRIPTION")
        appendLine("- Corpus SHA-256:")
        identity.fontHashes.entries.sortedBy { it.key }.forEach { (path, hash) ->
            appendLine("  - `$path`: `$hash`")
        }
        ordered.forEach { profile ->
            appendLine()
            appendLine("## ${profile.metadata.name}")
            appendLine()
            appendLine("- Route: ${profile.metadata.route}")
            appendLine("- Timed boundary: ${profile.metadata.timedBoundary}")
            appendLine("- Cache state: ${profile.metadata.cacheState}")
            appendLine("- Warmup/measurement iterations: decided by androidx.benchmark's default protocol (not reported in this mode)")
            profile.medianNanos?.let { median ->
                appendLine("- Latency median: $median ns (androidx.benchmark median)")
            } ?: appendLine("- Latency median: unavailable — no benchmark message file for this scenario")
            appendLine("- Latency p95/p99: unavailable — the instrumentation benchmark publishes a median only in this mode")
            profile.counters.entries.sortedBy { it.key }.forEach { (name, count) ->
                appendLine("- Consumed `$name`: $count")
            }
            profile.allocationCount?.let { allocations ->
                appendLine("- Allocation count: measured $allocations (androidx.benchmark allocation count per operation)")
            }
            androidFigures().entries.sortedBy { it.key }.forEach { (label, figure) ->
                appendLine("- $label: ${figure.state.name.lowercase()} — ${figure.detail}")
            }
        }
    }
    return AggregatedReport(
        platformId = "android",
        commit = identity.commit,
        runtime = identity.runtime,
        profileCount = ordered.size,
        measured = ordered.map { profile -> profile.metadata.name }.toSet(),
        // The device declares every portable capability and ART has the worker instrument, so the
        // Android report names no deferral; the coverage check holds it to that.
        deferred = emptySet(),
        p50Nanos = ordered.mapNotNull { profile ->
            profile.medianNanos?.let { profile.metadata.name to it }
        }.toMap(),
        markdown = markdown,
    )
}

/** Median nanoseconds and per-operation allocation count from the tool's message file. */
private fun readMedian(file: File): Pair<Long, Long> {
    val match = Regex("""([\d,]+)\s+ns\s+([\d,]+)\s+allocs""")
        .find(file.readText())
        ?: error("The benchmark message ${file.name} carries no '<ns> ns <n> allocs' summary.")
    val (nanos, allocations) = match.destructured
    return nanos.replace(",", "").toLong() to allocations.replace(",", "").toLong()
}

private const val JSONL_MARKER = "KALLIGRAPHIE-BENCH-JSONL:"
private const val IDENTITY_MARKER = "KALLIGRAPHIE-BENCH-IDENTITY:"

/**
 * What follows [marker] on a logcat line, or null when the line carries none.
 *
 * The Android channel is the instrumentation log, and a capture keeps logcat's own prefix —
 * `09-27 15:40:55.648 2439 2458 I System.out: KALLIGRAPHIE-BENCH-…` — while a capture made with
 * `-v raw` carries the payload alone. Both are the same measurement, so the marker is searched for
 * rather than required at the start of the line.
 */
private fun markerPayload(line: String, marker: String): String? {
    val at = line.indexOf(marker)
    return if (at < 0) null else line.substring(at + marker.length)
}

private class AndroidProfile(
    val metadata: MeasurementScenario,
    val counters: Map<String, Long>,
    val medianNanos: Long?,
    val allocationCount: Long?,
)

// -------------------------------------------------------------------------------------------------
// Comparison: every profile the module knows, across the three platforms, with their caveats.
// -------------------------------------------------------------------------------------------------

private fun renderComparison(
    jvm: AggregatedReport,
    ios: AggregatedReport,
    android: AggregatedReport,
    corpus: ReportCorpus,
): String = buildString {
    appendLine("# measurement comparison")
    appendLine()
    appendLine("- JVM `${jvm.commit.take(12)}` (${jvm.runtime}), ${jvm.profileCount} profiles")
    appendLine("- iOS `${ios.commit.take(12)}` (${ios.runtime}), ${ios.profileCount} profiles")
    appendLine("- Android `${android.commit.take(12)}` (${android.runtime}), ${android.profileCount} profiles")
    listOf(jvm, ios, android).filter { report -> report.deferred.isNotEmpty() }.forEach { report ->
        appendLine(
            "- ${report.platformId} declares ${report.deferred.sorted().joinToString(", ")} deferred: " +
                "this harness has no instrument for the pattern that profile exercises, and running a " +
                "different pattern under the same name would publish another measurement.",
        )
    }
    appendLine()
    appendLine("| Scenario | JVM p50 (ns/op) | iOS p50 (ns/op) | Android median (ns/op) |")
    appendLine("| --- | --- | --- | --- |")
    ScenarioRegistry.all(corpus, threadedInstrumentScenarios(corpus)).forEach { scenario ->
        val name = scenario.name
        appendLine(
            "| $name | ${jvm.p50Nanos[name] ?: "—"} | " +
                "${ios.p50Nanos[name] ?: "—"} | ${android.p50Nanos[name] ?: "—"} |",
        )
    }
    appendLine()
    appendLine("The Android column is androidx.benchmark's median (the tool publishes no percentiles in this mode), measured on a debuggable APK on an emulator with the tool's refusals suppressed; the iOS column is measured on the simulator; neither is a like-for-like number against the JVM.")
}
