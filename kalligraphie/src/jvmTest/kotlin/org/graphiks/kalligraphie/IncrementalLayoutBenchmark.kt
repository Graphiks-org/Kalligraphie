package org.graphiks.kalligraphie

import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.IncrementalLayoutRequest
import org.graphiks.kalligraphie.api.IncrementalLayoutResult
import org.graphiks.kalligraphie.api.LayoutContractResult
import org.graphiks.kalligraphie.api.LayoutDelta
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutStateHandle
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.TextChange
import org.graphiks.kalligraphie.api.TextChangeSet
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.createIncrementalLayoutRequest
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer

class IncrementalLayoutBenchmarkTest {
    @Test
    fun measurementReportNamesEveryRequiredReproducibilityField() {
        val report = IncrementalLayoutBenchmark.reportFor(
            environment = MeasurementEnvironment(
                commit = "0123456789abcdef",
                machine = "fixed-machine",
                operatingSystem = "FixedOS 1.0",
                jvm = "FixedVM 21",
                unicodeVersion = "16.0",
                harfBuzzVersion = "14.3.0",
                fontHashes = mapOf("Fixture.ttf" to "abc123"),
                gcPolicy = "between profiles",
            ),
            corpus = MeasurementCorpus(
                id = "fixed-corpus",
                description = "fixed multilingual paragraph",
                scalarCount = 24,
                paragraphCount = 2,
            ),
            profiles = listOf(
                fixedProfile("InteractiveEdit"),
                fixedProfile("ViewportLayout"),
                fixedProfile("Cancellation"),
            ),
        )

        assertEquals(
            listOf("InteractiveEdit", "ViewportLayout", "Cancellation"),
            report.profiles.map(MeasurementProfileReport::name),
        )
        assertTrue(report.metadata.commit.isNotBlank())
        assertTrue(report.metadata.machine.isNotBlank())
        assertTrue(report.metadata.operatingSystem.isNotBlank())
        assertTrue(report.metadata.jvm.isNotBlank())
        assertTrue(report.metadata.fontHashes.isNotEmpty())
        assertTrue(report.metadata.unicodeVersion.isNotBlank())
        assertTrue(report.metadata.harfBuzzVersion.isNotBlank())
        assertTrue(report.metadata.gcPolicy.isNotBlank())
        assertTrue(report.corpus.id.isNotBlank())
        assertTrue(report.corpus.description.isNotBlank())
        assertTrue(report.corpus.scalarCount > 0)
        assertTrue(report.corpus.paragraphCount > 0)
        assertTrue(report.profiles.all { profile ->
            profile.iterations > 0 &&
                profile.warmupIterations > 0 &&
                profile.coverage.isNotBlank() &&
                profile.cacheState.isNotBlank() &&
                profile.latency.p50Nanos > 0 &&
                profile.latency.p95Nanos > 0 &&
                profile.latency.p99Nanos > 0 &&
                profile.allocations.state.isNotBlank() &&
                profile.retainedJvmMemory.state.isNotBlank() &&
                profile.retainedNativeMemory.state.isNotBlank() &&
                profile.cancellationDelay.state.isNotBlank() &&
                profile.rematerializedText.state.isNotBlank() &&
                profile.rematerializedLines.state.isNotBlank() &&
                profile.rematerializedParagraphs.state.isNotBlank()
        })
    }

    @Test
    fun runConfiguredMeasurementProfiles() {
        if (System.getenv(OPT_IN_ENVIRONMENT) != "true") return

        val warmupIterations = positiveEnvironmentInteger(WARMUP_ENVIRONMENT, defaultValue = 5)
        val iterations = positiveEnvironmentInteger(ITERATIONS_ENVIRONMENT, defaultValue = 20)
        val output = checkNotNull(System.getenv(OUTPUT_ENVIRONMENT)) {
            "$OUTPUT_ENVIRONMENT must name an absolute file outside the repository."
        }
        val outputPath = Path.of(output).toAbsolutePath().normalize()
        val repositoryRoot = repositoryRoot()
        require(!outputPath.startsWith(repositoryRoot)) {
            "$OUTPUT_ENVIRONMENT must be outside the repository; received $outputPath."
        }

        val report = IncrementalLayoutBenchmark.run(warmupIterations, iterations)
        val rendered = report.toMarkdown()
        outputPath.parent?.let(Files::createDirectories)
        Files.writeString(outputPath, rendered)
        println(rendered)
        println("Measurement report written to $outputPath")
    }

    private fun fixedProfile(name: String): MeasurementProfileReport = MeasurementProfileReport(
        name = name,
        warmupIterations = 2,
        iterations = 5,
        coverage = "scalars 0..8; overscan 1 line",
        cacheState = "warm after profile-local warmup",
        latency = Percentiles(100, 200, 300),
        allocations = MeasurementValue.available(1_024, "bytes per iteration"),
        retainedJvmMemory = MeasurementValue.unavailable("not sampled in completeness fixture"),
        retainedNativeMemory = MeasurementValue.unavailable("not exposed by the backend"),
        cancellationDelay = MeasurementValue.unavailable("not applicable to this fixture"),
        rematerializedText = MeasurementValue.available(8, "scalars per iteration"),
        rematerializedLines = MeasurementValue.available(2, "lines per iteration"),
        rematerializedParagraphs = MeasurementValue.available(1, "paragraphs per iteration"),
    )

    private fun positiveEnvironmentInteger(name: String, defaultValue: Int): Int {
        val value = System.getenv(name)?.toIntOrNull() ?: defaultValue
        require(value > 0) { "$name must be a positive integer." }
        return value
    }

    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.exists(candidate.resolve(".git"))) return candidate
            candidate = candidate.parent
        }
        error("Could not locate the repository root from the test working directory.")
    }

    private companion object {
        const val OPT_IN_ENVIRONMENT = "KALLIGRAPHIE_MEASUREMENT"
        const val WARMUP_ENVIRONMENT = "KALLIGRAPHIE_MEASUREMENT_WARMUP"
        const val ITERATIONS_ENVIRONMENT = "KALLIGRAPHIE_MEASUREMENT_ITERATIONS"
        const val OUTPUT_ENVIRONMENT = "KALLIGRAPHIE_MEASUREMENT_OUTPUT"
    }
}

internal data class MeasurementEnvironment(
    val commit: String,
    val machine: String,
    val operatingSystem: String,
    val jvm: String,
    val unicodeVersion: String,
    val harfBuzzVersion: String,
    val fontHashes: Map<String, String>,
    val gcPolicy: String,
)

internal data class MeasurementCorpus(
    val id: String,
    val description: String,
    val scalarCount: Int,
    val paragraphCount: Int,
)

internal data class Percentiles(
    val p50Nanos: Long,
    val p95Nanos: Long,
    val p99Nanos: Long,
)

internal data class MeasurementValue(
    val state: String,
    val value: Long?,
    val detail: String,
) {
    companion object {
        fun available(value: Number, detail: String): MeasurementValue =
            MeasurementValue("available", value.toLong(), detail)

        fun unavailable(detail: String): MeasurementValue =
            MeasurementValue("unavailable", null, detail)
    }
}

internal data class MeasurementProfileReport(
    val name: String,
    val warmupIterations: Int,
    val iterations: Int,
    val coverage: String,
    val cacheState: String,
    val latency: Percentiles,
    val allocations: MeasurementValue,
    val retainedJvmMemory: MeasurementValue,
    val retainedNativeMemory: MeasurementValue,
    val cancellationDelay: MeasurementValue,
    val rematerializedText: MeasurementValue,
    val rematerializedLines: MeasurementValue,
    val rematerializedParagraphs: MeasurementValue,
)

internal data class IncrementalLayoutMeasurementReport(
    val metadata: MeasurementEnvironment,
    val corpus: MeasurementCorpus,
    val profiles: List<MeasurementProfileReport>,
) {
    fun toMarkdown(): String = buildString {
        appendLine("# Incremental layout engine-only measurement")
        appendLine()
        appendLine("- Commit: `${metadata.commit}`")
        appendLine("- Machine: ${metadata.machine}")
        appendLine("- OS: ${metadata.operatingSystem}")
        appendLine("- JVM: ${metadata.jvm}")
        appendLine("- Unicode: ${metadata.unicodeVersion}")
        appendLine("- HarfBuzz: ${metadata.harfBuzzVersion}")
        appendLine("- GC policy: ${metadata.gcPolicy}")
        appendLine("- Corpus: `${corpus.id}` — ${corpus.description}")
        appendLine("- Corpus size: ${corpus.scalarCount} scalars, ${corpus.paragraphCount} paragraphs")
        appendLine("- Font SHA-256:")
        metadata.fontHashes.forEach { (name, hash) -> appendLine("  - `$name`: `$hash`") }
        profiles.forEach { profile ->
            appendLine()
            appendLine("## ${profile.name}")
            appendLine()
            appendLine("- Coverage: ${profile.coverage}")
            appendLine("- Cache state: ${profile.cacheState}")
            appendLine("- Warmup iterations: ${profile.warmupIterations}")
            appendLine("- Measured iterations: ${profile.iterations}")
            appendLine("- Latency p50: ${profile.latency.p50Nanos} ns")
            appendLine("- Latency p95: ${profile.latency.p95Nanos} ns")
            appendLine("- Latency p99: ${profile.latency.p99Nanos} ns")
            appendMeasurement("Allocations", profile.allocations)
            appendMeasurement("Retained JVM memory", profile.retainedJvmMemory)
            appendMeasurement("Retained native memory", profile.retainedNativeMemory)
            appendMeasurement("Cancellation delay", profile.cancellationDelay)
            appendMeasurement("Rematerialized text", profile.rematerializedText)
            appendMeasurement("Rematerialized lines", profile.rematerializedLines)
            appendMeasurement("Rematerialized paragraphs", profile.rematerializedParagraphs)
        }
    }

    private fun StringBuilder.appendMeasurement(label: String, measurement: MeasurementValue) {
        val value = measurement.value?.let { ": $it" } ?: ""
        appendLine("- $label: ${measurement.state}$value (${measurement.detail})")
    }
}

internal object IncrementalLayoutBenchmark {
    private const val CORPUS_ID = "incremental-mixed-script-v1"
    private const val SOURCE_TEXT =
        "office cafe\nabc \u0633\u0644\u0627\u0645\nstable paragraph for viewport layout\nfinal line"
    private const val TARGET_TEXT =
        "office \uD83D\uDE00\nabc \u0633\u0644\u0627\u0645\nstable paragraph for viewport layout\nfinal line"
    private const val GC_POLICY =
        "System.gc() twice before and after each profile; no requested GC between measured iterations"
    private val FONT_PATHS = linkedMapOf(
        "GdefKerningFixture.ttf" to "gdef-kern/GdefKerningFixture.ttf",
        "Amiri-Regular.ttf" to "amiri/Amiri-Regular.ttf",
    )

    fun reportFor(
        environment: MeasurementEnvironment,
        corpus: MeasurementCorpus,
        profiles: List<MeasurementProfileReport>,
    ): IncrementalLayoutMeasurementReport {
        require(profiles.map(MeasurementProfileReport::name) == REQUIRED_PROFILES) {
            "Measurement reports must contain the required profiles in their documented order."
        }
        require(environment.fontHashes.isNotEmpty()) { "At least one font hash is required." }
        require(profiles.all { it.warmupIterations > 0 && it.iterations > 0 }) {
            "Warmup and measured iteration counts must be positive."
        }
        return IncrementalLayoutMeasurementReport(environment, corpus, profiles.toList())
    }

    fun run(warmupIterations: Int, iterations: Int): IncrementalLayoutMeasurementReport {
        require(warmupIterations > 0) { "Warmup iterations must be positive." }
        require(iterations > 0) { "Measured iterations must be positive." }

        val source = incrementalRealFontFixture(SOURCE_TEXT)
        val target = source.withText(TARGET_TEXT)
        val unicodeVersion = JvmUnicodeAnalyzer.create().analyze(
            source.snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, "en"),
        ).unicodeData.unicodeVersion
        val harfBuzzVersion = openedHarfBuzzVersion()
        val corpus = MeasurementCorpus(
            id = CORPUS_ID,
            description = "Latin, Arabic, ligature, emoji replacement, and newline-delimited viewport text",
            scalarCount = SOURCE_TEXT.codePointCount(0, SOURCE_TEXT.length),
            paragraphCount = SOURCE_TEXT.count { it == '\n' } + 1,
        )
        val environment = MeasurementEnvironment(
            commit = currentCommit(),
            machine = machineName(),
            operatingSystem = "${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
                "(${System.getProperty("os.arch")})",
            jvm = "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
            unicodeVersion = unicodeVersion,
            harfBuzzVersion = harfBuzzVersion,
            fontHashes = FONT_PATHS.mapValues { (_, relativePath) -> fixtureBytes(relativePath).sha256Hex() },
            gcPolicy = GC_POLICY,
        )
        val profiles = listOf(
            runInteractiveEdit(source, target, warmupIterations, iterations),
            runViewportLayout(source, warmupIterations, iterations),
            runCancellation(source, warmupIterations, iterations),
        )
        return reportFor(environment, corpus, profiles)
    }

    private fun runInteractiveEdit(
        source: IncrementalRealFontFixture,
        target: IncrementalRealFontFixture,
        warmupIterations: Int,
        iterations: Int,
    ): MeasurementProfileReport = measuredProfile(
        name = "InteractiveEdit",
        warmupIterations = warmupIterations,
        iterations = iterations,
        coverage = "target scalars 0..18 with 1 complete line of overscan",
        runOperations = { record ->
            openSession().use { session ->
                val forward = change(source, target, sourceStart = 7, sourceEnd = 11, targetStart = 7, targetEnd = 8)
                val reverse = change(target, source, sourceStart = 7, sourceEnd = 8, targetStart = 7, targetEnd = 11)
                var currentFixture = source
                var current = success(
                    session.layout(request(source, source.snapshot.incrementalRange(0, 18), overscan = 1)),
                )
                repeat(warmupIterations + iterations) { index ->
                    val nextFixture = if (currentFixture === source) target else source
                    val delta = if (currentFixture === source) forward else reverse
                    val prepared = request(
                        fixture = nextFixture,
                        requestedRange = nextFixture.snapshot.incrementalRange(0, 18),
                        overscan = 1,
                        previousState = current.layout.state,
                        delta = LayoutDelta(text = delta),
                    )
                    val sample = timed {
                        val result = success(session.layout(prepared))
                        consume(result)
                        result
                    }
                    current = sample.value
                    currentFixture = nextFixture
                    if (index >= warmupIterations) {
                        record(sample.withDiagnostics(materializationDiagnostics(sample.value, nextFixture.snapshot)))
                    }
                }
            }
        },
    )

    private fun runViewportLayout(
        fixture: IncrementalRealFontFixture,
        warmupIterations: Int,
        iterations: Int,
    ): MeasurementProfileReport = measuredProfile(
        name = "ViewportLayout",
        warmupIterations = warmupIterations,
        iterations = iterations,
        coverage = "alternating scalar ranges 0..18 and 25..52 with 2 complete lines of overscan",
        runOperations = { record ->
            openSession().use { session ->
                var current = success(
                    session.layout(request(fixture, fixture.snapshot.incrementalRange(0, 18), overscan = 2)),
                )
                repeat(warmupIterations + iterations) { index ->
                    val requested = if (index % 2 == 0) {
                        fixture.snapshot.incrementalRange(25, 52)
                    } else {
                        fixture.snapshot.incrementalRange(0, 18)
                    }
                    val prepared = request(
                        fixture = fixture,
                        requestedRange = requested,
                        overscan = 2,
                        previousState = current.layout.state,
                    )
                    val sample = timed {
                        val result = success(session.layout(prepared))
                        consume(result)
                        result
                    }
                    current = sample.value
                    if (index >= warmupIterations) {
                        record(sample.withDiagnostics(materializationDiagnostics(sample.value, fixture.snapshot)))
                    }
                }
            }
        },
    )

    private fun runCancellation(
        fixture: IncrementalRealFontFixture,
        warmupIterations: Int,
        iterations: Int,
    ): MeasurementProfileReport = measuredProfile(
        name = "Cancellation",
        warmupIterations = warmupIterations,
        iterations = iterations,
        coverage = "full ${SOURCE_TEXT.codePointCount(0, SOURCE_TEXT.length)}-scalar corpus requested; " +
            "token cancels after 3 cooperative checks; no partial coverage accepted",
        runOperations = { record ->
            openSession().use { session ->
                repeat(warmupIterations + iterations) { index ->
                    val token = CancelAfterChecks(3)
                    val prepared = request(
                        fixture = fixture,
                        requestedRange = fixture.snapshot.range,
                        overscan = 0,
                        cancellationToken = token,
                    )
                    val sample = timed {
                        check(session.layout(prepared) == IncrementalLayoutResult.Cancelled) {
                            "Cancellation profile accepted only typed cancelled outcomes."
                        }
                    }
                    if (index >= warmupIterations) {
                        record(sample.withDiagnostics(MaterializationDiagnostics.unavailableAfterCancellation()))
                    }
                }
            }
        },
        cancellation = true,
    )

    private fun measuredProfile(
        name: String,
        warmupIterations: Int,
        iterations: Int,
        coverage: String,
        cancellation: Boolean = false,
        runOperations: ((Sample) -> Unit) -> Unit,
    ): MeasurementProfileReport {
        forceGc()
        val retainedBefore = usedHeapBytes()
        val samples = mutableListOf<Sample>()
        runOperations(samples::add)
        forceGc()
        val retainedAfter = usedHeapBytes()
        check(samples.size == iterations) { "Profile $name recorded ${samples.size} of $iterations iterations." }
        val latencies = samples.map(Sample::elapsedNanos)
        val allocationValues = samples.mapNotNull(Sample::allocatedBytes)
        val allocations = if (allocationValues.size == samples.size) {
            MeasurementValue.available(allocationValues.averageAsLong(), "bytes per measured thread iteration")
        } else {
            MeasurementValue.unavailable("thread allocation counters are unavailable on this JVM")
        }
        return MeasurementProfileReport(
            name = name,
            warmupIterations = warmupIterations,
            iterations = iterations,
            coverage = coverage,
            cacheState = "new session, one untimed seed where applicable, then profile-local warmup; measured state warm",
            latency = percentiles(latencies),
            allocations = allocations,
            retainedJvmMemory = MeasurementValue.available(
                retainedAfter - retainedBefore,
                "signed used-heap delta after the documented forced-GC samples",
            ),
            retainedNativeMemory = MeasurementValue.unavailable(
                "the JVM HarfBuzz backend exposes identity and lifecycle, not retained native byte accounting",
            ),
            cancellationDelay = if (cancellation) {
                MeasurementValue.available(percentiles(latencies).p95Nanos, "p95 nanoseconds from call entry to typed cancellation return")
            } else {
                MeasurementValue.unavailable("profile does not signal cancellation")
            },
            rematerializedText = aggregateDiagnostics(samples) { it.textScalars },
            rematerializedLines = aggregateDiagnostics(samples) { it.lines },
            rematerializedParagraphs = aggregateDiagnostics(samples) { it.paragraphs },
        )
    }

    private fun aggregateDiagnostics(
        samples: List<Sample>,
        select: (MaterializationDiagnostics) -> Long?,
    ): MeasurementValue {
        val values = samples.map { sample -> select(sample.diagnostics) }
        return if (values.all { it != null }) {
            MeasurementValue.available(values.filterNotNull().averageAsLong(), "average per measured iteration")
        } else {
            MeasurementValue.unavailable("cancelled results intentionally expose no partial rematerialization diagnostics")
        }
    }

    private fun <Value> timed(operation: () -> Value): RawSample<Value> {
        val allocationBefore = ThreadAllocationProbe.currentBytes()
        val startedAt = System.nanoTime()
        val value = operation()
        val elapsed = System.nanoTime() - startedAt
        val allocationAfter = ThreadAllocationProbe.currentBytes()
        val allocated = if (allocationBefore != null && allocationAfter != null) {
            max(0L, allocationAfter - allocationBefore)
        } else {
            null
        }
        return RawSample(value, max(1L, elapsed), allocated)
    }

    private fun <Value> RawSample<Value>.withDiagnostics(diagnostics: MaterializationDiagnostics): Sample =
        Sample(elapsedNanos, allocatedBytes, diagnostics)

    private fun consume(result: IncrementalLayoutResult.Success) {
        check(result.layout.coverage.isComplete) { "A fast incomplete result is not a successful measurement sample." }
        val covered = result.layout.coveredRange
        var checksum = covered.start.hashCode().toLong() xor covered.endExclusive.hashCode().toLong()
        result.layout.lines.forEach { line ->
            checksum = checksum xor line.range.hashCode().toLong()
            checksum += line.allCaretCandidates.size
            line.positionedGlyphRuns.forEach { run ->
                checksum = checksum xor run.sourceRun.backendIdentity.hashCode().toLong()
                run.glyphs.forEach { glyph ->
                    checksum += glyph.shapedGlyph.glyphId.value.toLong()
                    checksum += glyph.sourceClusters.size
                }
            }
        }
        checksum = checksum xor result.layout.coverage.tailState.hashCode().toLong()
        checksum = checksum xor result.diagnostics.hashCode().toLong()
        blackhole = blackhole xor checksum
    }

    private fun materializationDiagnostics(
        result: IncrementalLayoutResult.Success,
        snapshot: TextSnapshot,
    ): MaterializationDiagnostics {
        val scalars = snapshot.scalarValues(result.layout.coveredRange)
        return MaterializationDiagnostics(
            textScalars = scalars.size.toLong(),
            lines = result.layout.lines.size.toLong(),
            paragraphs = (scalars.count { it == '\n'.code } + 1).toLong(),
        )
    }

    private fun request(
        fixture: IncrementalRealFontFixture,
        requestedRange: TextRange,
        overscan: Int,
        previousState: LayoutStateHandle? = null,
        delta: LayoutDelta? = null,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): JvmIncrementalParagraphLayoutRequest {
        val portable = when (
            val result = createIncrementalLayoutRequest(
                input = LayoutInput(fixture.snapshot, fixture.typography),
                requestedRange = requestedRange,
                constraints = incrementalTestConstraints(width = 1_400f, height = 4_800f),
                overscan = LineOverscan(overscan),
                previousState = previousState,
                delta = delta,
                cancellationToken = cancellationToken,
            )
        ) {
            is LayoutContractResult.Success<IncrementalLayoutRequest> -> result.value
            is LayoutContractResult.Failure -> error("Measurement request failed: ${result.error.code}: ${result.error.message}")
        }
        return JvmIncrementalParagraphLayoutRequest(
            request = portable,
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            materialization = EditableLineMaterialization.LayoutOnly,
        )
    }

    private fun change(
        source: IncrementalRealFontFixture,
        target: IncrementalRealFontFixture,
        sourceStart: Int,
        sourceEnd: Int,
        targetStart: Int,
        targetEnd: Int,
    ): TextChangeSet = when (
        val result = TextChangeSet.create(
            source.snapshot,
            target.snapshot,
            listOf(
                TextChange(
                    source.snapshot.incrementalRange(sourceStart, sourceEnd),
                    target.snapshot.incrementalRange(targetStart, targetEnd),
                ),
            ),
        )
    ) {
        is LayoutContractResult.Success<TextChangeSet> -> result.value
        is LayoutContractResult.Failure -> error("Measurement delta failed: ${result.error.code}: ${result.error.message}")
    }

    private fun openSession(): JvmIncrementalParagraphLayoutSession = when (
        val opened = JvmIncrementalParagraphLayoutSession.open()
    ) {
        is FontOperationResult.Success -> opened.value
        is FontOperationResult.Failure -> error("Could not open measurement session: ${opened.error.message}")
        is FontOperationResult.Cancelled -> error("Opening the measurement session was cancelled.")
    }

    private fun success(result: IncrementalLayoutResult): IncrementalLayoutResult.Success =
        result as? IncrementalLayoutResult.Success
            ?: error("Measurement accepts only complete successes; received $result.")

    private fun openedHarfBuzzVersion(): String = when (val opened = JvmHarfBuzzShapingBackend.open()) {
        is FontOperationResult.Success -> try {
            opened.value.identity.nativeVersion
        } finally {
            opened.value.close()
        }
        is FontOperationResult.Failure -> error("Could not identify HarfBuzz: ${opened.error.message}")
        is FontOperationResult.Cancelled -> error("Opening HarfBuzz for metadata was cancelled.")
    }

    private fun fixtureBytes(relativePath: String): ByteArray =
        checkNotNull(IncrementalLayoutBenchmark::class.java.getResourceAsStream("/fonts/$relativePath")) {
            "Missing measurement font fixture /fonts/$relativePath."
        }.use { stream -> stream.readBytes() }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun currentCommit(): String {
        val process = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(repositoryRoot().toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{40}"))) {
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
        error("Could not locate the repository root from the test working directory.")
    }

    private fun machineName(): String = runCatching { InetAddress.getLocalHost().hostName }
        .getOrElse { System.getenv("HOSTNAME") ?: "unknown-host" }
        .ifBlank { "unknown-host" }

    private fun percentiles(values: List<Long>): Percentiles {
        require(values.isNotEmpty()) { "At least one measured iteration is required." }
        val sorted = values.sorted()
        fun nearestRank(percentile: Double): Long = sorted[(ceil(percentile * sorted.size).toInt() - 1).coerceAtLeast(0)]
        return Percentiles(nearestRank(0.50), nearestRank(0.95), nearestRank(0.99))
    }

    private fun List<Long>.averageAsLong(): Long = sum() / size

    private fun forceGc() {
        repeat(2) {
            System.gc()
        }
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private class CancelAfterChecks(private val allowedChecks: Int) : CancellationToken {
        private var checks: Int = 0

        override fun isCancellationRequested(): Boolean {
            checks += 1
            return checks > allowedChecks
        }
    }

    private data class RawSample<Value>(
        val value: Value,
        val elapsedNanos: Long,
        val allocatedBytes: Long?,
    )

    private data class Sample(
        val elapsedNanos: Long,
        val allocatedBytes: Long?,
        val diagnostics: MaterializationDiagnostics,
    )

    private data class MaterializationDiagnostics(
        val textScalars: Long?,
        val lines: Long?,
        val paragraphs: Long?,
    ) {
        companion object {
            fun unavailableAfterCancellation(): MaterializationDiagnostics =
                MaterializationDiagnostics(null, null, null)
        }
    }

    private object ThreadAllocationProbe {
        private val bean: com.sun.management.ThreadMXBean? =
            (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)?.takeIf { candidate ->
                candidate.isThreadAllocatedMemorySupported && runCatching {
                    if (!candidate.isThreadAllocatedMemoryEnabled) candidate.isThreadAllocatedMemoryEnabled = true
                }.isSuccess
            }

        fun currentBytes(): Long? = bean?.getThreadAllocatedBytes(Thread.currentThread().threadId())?.takeIf { it >= 0L }
    }

    private val REQUIRED_PROFILES = listOf("InteractiveEdit", "ViewportLayout", "Cancellation")

    @Volatile
    private var blackhole: Long = 0L
}
