package org.graphiks.kalligraphie

import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.test.Test
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphProvenance
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.SourceEncoding
import org.graphiks.kalligraphie.api.TextDecodingOutcome
import org.graphiks.kalligraphie.api.TextDecodingProfile
import org.graphiks.kalligraphie.api.TextDecodingResult
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.Utf16Storage
import org.graphiks.kalligraphie.api.Utf8Storage
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer

class EditableLineMeasurementTest {
    @Test
    fun runsConfiguredEditableLineProfilesOnlyWhenExplicitlyEnabled() {
        check(System.getenv(OPT_IN_ENVIRONMENT) == "true") {
            "$OPT_IN_ENVIRONMENT=true is required to run the editable-line measurement."
        }
        val warmupIterations = positiveEnvironmentInteger(WARMUP_ENVIRONMENT, defaultValue = 5)
        val iterations = positiveEnvironmentInteger(ITERATIONS_ENVIRONMENT, defaultValue = 20)
        val output = checkNotNull(System.getenv(OUTPUT_ENVIRONMENT)) {
            "$OUTPUT_ENVIRONMENT must name an absolute Markdown file outside the repository."
        }
        val configuredOutput = Path.of(output)
        require(configuredOutput.isAbsolute) {
            "$OUTPUT_ENVIRONMENT must be absolute; received $configuredOutput."
        }
        val outputPath = configuredOutput.normalize()
        val repositoryRoot = repositoryRoot()
        require(!outputPath.startsWith(repositoryRoot)) {
            "$OUTPUT_ENVIRONMENT must be outside the repository; received $outputPath."
        }

        val validity = EditableLineMeasurement.captureValidityEvidence()
        check(validity.utf8Scalars == listOf(
            0x45, 0x64, 0x69, 0x74, 0x20,
            0x633, 0x644, 0x627, 0x645, 0x20,
            0x1F600, 0x20, 0x63, 0x61, 0x66, 0xE9,
        )) { "The borrowed fragmented UTF-8 journey did not publish the literal scalar oracle." }
        check(validity.utf8SourceBoundaries == listOf(
            0, 1, 2, 3, 4, 5, 7, 9, 11, 13, 14, 18, 19, 20, 21, 22, 24,
        )) { "The borrowed fragmented UTF-8 journey did not preserve source ranges." }
        check(validity.utf8DiagnosticCodes.isEmpty()) {
            "Valid borrowed fragmented UTF-8 unexpectedly produced diagnostics."
        }
        check(validity.utf16Scalars == validity.utf8Scalars) {
            "The UTF-16 journey did not publish the same literal Unicode scalars as UTF-8."
        }
        check(validity.utf16SourceBoundaries == listOf(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17,
        )) { "The borrowed fragmented UTF-16 journey did not preserve source ranges." }
        check(validity.utf16DiagnosticCodes.isEmpty()) {
            "Valid borrowed fragmented UTF-16 unexpectedly produced diagnostics."
        }
        check(validity.layoutRange == (0 to 16)) {
            "The mixed-script editable line did not cover the complete corpus."
        }
        check(validity.runDirections.containsAll(
            listOf(ShapingDirection.LEFT_TO_RIGHT, ShapingDirection.RIGHT_TO_LEFT),
        )) { "The mixed-script editable line did not publish both LTR and RTL runs." }
        // Frozen external oracle from hb-shape 14.4.0 over the checked-in DejaVu fixture at 2048 units.
        check(validity.glyphIds == listOf(
            40, 71, 76, 87, 3,
            1390, 5366, 5293,
            3, 5857, 3,
            70, 68, 73, 171,
        )) { "The mixed-script glyph sequence did not match the audited DejaVu/HarfBuzz oracle." }
        check(validity.glyphAdvances == listOf(
            1294f, 1300f, 569f, 803f, 651f,
            1268f, 1222f, 1716f,
            651f, 2135f, 651f,
            1126f, 1255f, 721f, 1260f,
        )) { "The mixed-script advances did not match the audited DejaVu/HarfBuzz oracle." }
        check(validity.directProvenanceCount == validity.glyphIds.size) {
            "Every layout-only glyph must retain direct source provenance."
        }
        check(validity.caretOrdinals == (0..16).toSet()) {
            "The mixed-script editable line did not expose every scalar-boundary caret."
        }
        check(validity.layoutDiagnosticCodes.isEmpty()) {
            "Valid mixed-script text unexpectedly produced layout diagnostics."
        }

        val report = EditableLineMeasurement.run(warmupIterations, iterations)
        val rendered = report.toMarkdown()
        outputPath.parent?.let(Files::createDirectories)
        Files.writeString(outputPath, rendered)
        println(rendered)
        println("Editable-line measurement report written to $outputPath")
    }

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
        const val OPT_IN_ENVIRONMENT = "KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT"
        const val WARMUP_ENVIRONMENT = "KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT_WARMUP"
        const val ITERATIONS_ENVIRONMENT = "KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT_ITERATIONS"
        const val OUTPUT_ENVIRONMENT = "KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT_OUTPUT"
    }
}

internal data class EditableLineMeasurementMetadata(
    val commit: String,
    val machine: String,
    val operatingSystem: String,
    val architecture: String,
    val jvm: String,
    val unicodeVersion: String,
    val icuVersion: String,
    val harfBuzzVersion: String,
    val fontHashes: Map<String, String>,
    val gcPolicy: String,
)

internal data class EditableLineMeasurementCorpus(
    val id: String,
    val description: String,
    val encoding: String,
    val sourceUnits: Int,
    val scalarCount: Int,
    val fragmentation: String,
)

internal data class EditableLineMeasurementPercentiles(
    val p50Nanos: Long,
    val p95Nanos: Long,
    val p99Nanos: Long,
)

internal data class EditableLineMeasurementValue(
    val state: String,
    val value: Long?,
    val detail: String,
) {
    companion object {
        fun available(value: Long, detail: String): EditableLineMeasurementValue =
            EditableLineMeasurementValue("available", value, detail)

        fun unavailable(detail: String): EditableLineMeasurementValue =
            EditableLineMeasurementValue("unavailable", null, detail)
    }
}

internal data class EditableLineMeasurementProfile(
    val name: String,
    val corpusId: String,
    val timedBoundary: String,
    val state: String,
    val warmupIterations: Int,
    val iterations: Int,
    val latency: EditableLineMeasurementPercentiles,
    val allocations: EditableLineMeasurementValue,
    val jvmHeapDelta: EditableLineMeasurementValue,
    val nativeMemory: EditableLineMeasurementValue,
)

internal data class EditableLineValidityEvidence(
    val utf8Scalars: List<Int>,
    val utf8SourceBoundaries: List<Int>,
    val utf8DiagnosticCodes: List<String>,
    val utf16Scalars: List<Int>,
    val utf16SourceBoundaries: List<Int>,
    val utf16DiagnosticCodes: List<String>,
    val layoutRange: Pair<Int, Int>,
    val runDirections: List<ShapingDirection>,
    val glyphIds: List<Int>,
    val glyphAdvances: List<Float>,
    val directProvenanceCount: Int,
    val caretOrdinals: Set<Int>,
    val layoutDiagnosticCodes: List<String>,
)

internal data class EditableLineMeasurementReport(
    val metadata: EditableLineMeasurementMetadata,
    val corpora: List<EditableLineMeasurementCorpus>,
    val profiles: List<EditableLineMeasurementProfile>,
) {
    fun toMarkdown(): String = buildString {
        appendLine("# Editable-line consumer measurement")
        appendLine()
        appendLine("> Unpublished observations from one opt-in run; this is not a reference benchmark.")
        appendLine()
        appendLine("- Commit: `${metadata.commit}`")
        appendLine("- Machine: ${metadata.machine}")
        appendLine("- OS: ${metadata.operatingSystem}")
        appendLine("- Architecture: ${metadata.architecture}")
        appendLine("- JVM: ${metadata.jvm}")
        appendLine("- Unicode data: ${metadata.unicodeVersion}")
        appendLine("- ICU: ${metadata.icuVersion}")
        appendLine("- HarfBuzz: ${metadata.harfBuzzVersion}")
        appendLine("- GC policy: ${metadata.gcPolicy}")
        appendLine("- Native-memory boundary: unavailable; the public JVM path exposes no reliable retained-native-byte boundary")
        appendLine("- Font SHA-256:")
        metadata.fontHashes.forEach { (name, hash) -> appendLine("  - `$name`: `$hash`") }
        appendLine()
        appendLine("## Corpora")
        corpora.forEach { corpus ->
            appendLine()
            appendLine("### ${corpus.id}")
            appendLine()
            appendLine("- Description: ${corpus.description}")
            appendLine("- Encoding: ${corpus.encoding}")
            appendLine("- Size: ${corpus.sourceUnits} source units, ${corpus.scalarCount} Unicode scalars")
            appendLine("- Fragmentation: ${corpus.fragmentation}")
        }
        profiles.forEach { profile ->
            appendLine()
            appendLine("## ${profile.name}")
            appendLine()
            appendLine("- Corpus: `${profile.corpusId}`")
            appendLine("- Timed boundary: ${profile.timedBoundary}")
            appendLine("- State: ${profile.state}")
            appendLine("- Warmup iterations: ${profile.warmupIterations}")
            appendLine("- Measured iterations: ${profile.iterations}")
            appendLine("- Latency p50 (nearest-rank): ${profile.latency.p50Nanos} ns")
            appendLine("- Latency p95 (nearest-rank): ${profile.latency.p95Nanos} ns")
            appendLine("- Latency p99 (nearest-rank): ${profile.latency.p99Nanos} ns")
            appendMeasurement("Allocated bytes", profile.allocations)
            appendMeasurement("Signed JVM heap delta", profile.jvmHeapDelta)
            appendMeasurement("Native memory", profile.nativeMemory)
        }
    }

    private fun StringBuilder.appendMeasurement(label: String, measurement: EditableLineMeasurementValue) {
        val value = measurement.value?.let { ": $it" } ?: ""
        appendLine("- $label: ${measurement.state}$value (${measurement.detail})")
    }
}

internal object EditableLineMeasurement {
    private const val SOURCE_TEXT = "Edit سلام 😀 café"
    private const val DEJAVU_PATH = "dejavu/DejaVuSans.ttf"
    private const val GC_POLICY =
        "two explicit System.gc() requests before and after each profile; no requested GC between measured iterations"
    private val EXPECTED_SCALARS = listOf(
        0x45, 0x64, 0x69, 0x74, 0x20,
        0x633, 0x644, 0x627, 0x645, 0x20,
        0x1F600, 0x20, 0x63, 0x61, 0x66, 0xE9,
    )
    private val EXPECTED_UTF8_BOUNDARIES = listOf(
        0, 1, 2, 3, 4, 5, 7, 9, 11, 13, 14, 18, 19, 20, 21, 22, 24,
    )
    private val EXPECTED_UTF16_BOUNDARIES = listOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17,
    )
    private val UTF8_FRAGMENT_LENGTHS = listOf(5, 9, 5, 5)
    private val UTF16_FRAGMENT_LENGTHS = listOf(5, 5, 3, 4)

    internal fun captureValidityEvidence(): EditableLineValidityEvidence {
        val utf8Storage = ImmutableByteStorage(SOURCE_TEXT.encodeToByteArray())
        val utf16Storage = ImmutableCharStorage(SOURCE_TEXT.toCharArray())
        val utf8 = decodeSuccess(
            Kalligraphie.decodeUtf8(
                TextVersion.create(),
                borrowedUtf8Slices(utf8Storage, UTF8_FRAGMENT_LENGTHS),
                TextDecodingProfile.unbounded,
            ),
        )
        val utf16 = decodeSuccess(
            Kalligraphie.decodeUtf16(
                TextVersion.create(),
                borrowedUtf16Slices(utf16Storage, UTF16_FRAGMENT_LENGTHS),
                TextDecodingProfile.unbounded,
            ),
        )
        val snapshot = utf16.snapshot
        val font = prepareFont(fixtureBytes(DEJAVU_PATH))
        val session = openSession()
        val line = try {
            layoutSuccess(session.layout(request(snapshot, font))).line
        } finally {
            closeSession(session)
        }
        val glyphs = line.positionedGlyphRuns.flatMap { it.glyphs }
        return EditableLineValidityEvidence(
            utf8Scalars = utf8.snapshot.scalars.toList(),
            utf8SourceBoundaries = sourceBoundaries(utf8),
            utf8DiagnosticCodes = utf8.diagnostics.map { it.code },
            utf16Scalars = snapshot.scalars.toList(),
            utf16SourceBoundaries = sourceBoundaries(utf16),
            utf16DiagnosticCodes = utf16.diagnostics.map { it.code },
            layoutRange = scalarOrdinal(snapshot, line.range.start) to
                scalarOrdinal(snapshot, line.range.endExclusive),
            runDirections = line.positionedGlyphRuns.map { it.sourceRun.direction },
            glyphIds = glyphs.map { it.shapedGlyph.glyphId.value },
            glyphAdvances = glyphs.map { it.advance.x.value },
            directProvenanceCount = glyphs.count { it.provenance is GlyphProvenance.Direct },
            caretOrdinals = line.allCaretCandidates.mapTo(linkedSetOf()) {
                scalarOrdinal(snapshot, it.position.index)
            },
            layoutDiagnosticCodes = line.diagnostics.map { it.code },
        )
    }

    fun run(warmupIterations: Int, iterations: Int): EditableLineMeasurementReport {
        require(warmupIterations > 0) { "Warmup iterations must be positive." }
        require(iterations > 0) { "Measured iterations must be positive." }

        val utf8Storage = ImmutableByteStorage(SOURCE_TEXT.encodeToByteArray())
        val utf16Storage = ImmutableCharStorage(SOURCE_TEXT.toCharArray())
        val utf8Slices = borrowedUtf8Slices(utf8Storage, UTF8_FRAGMENT_LENGTHS)
        val utf16Slices = borrowedUtf16Slices(utf16Storage, UTF16_FRAGMENT_LENGTHS)
        val layoutSnapshot = validateDecoded(
            Kalligraphie.decodeUtf16(TextVersion.create(), listOf(TextSlice.Utf16(SOURCE_TEXT.toCharArray()))),
            SourceEncoding.UTF16,
            EXPECTED_UTF16_BOUNDARIES,
        ).snapshot
        val fontBytes = fixtureBytes(DEJAVU_PATH)
        val unicodeData = JvmUnicodeAnalyzer.create().analyze(
            layoutSnapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, "en"),
        ).unicodeData

        return EditableLineMeasurementReport(
            metadata = EditableLineMeasurementMetadata(
                commit = currentCommit(),
                machine = machineName(),
                operatingSystem = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
                architecture = System.getProperty("os.arch"),
                jvm = "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
                unicodeVersion = unicodeData.unicodeVersion,
                icuVersion = "${unicodeData.implementation} ${unicodeData.implementationVersion}",
                harfBuzzVersion = openedHarfBuzzVersion(),
                fontHashes = mapOf("DejaVuSans.ttf" to fontBytes.sha256Hex()),
                gcPolicy = GC_POLICY,
            ),
            corpora = listOf(
                EditableLineMeasurementCorpus(
                    id = "borrowed-fragmented-utf8",
                    description = "real mixed Latin, Arabic, emoji, accented-Latin text: “$SOURCE_TEXT”",
                    encoding = "UTF-8",
                    sourceUnits = 24,
                    scalarCount = 16,
                    fragmentation = "4 borrowed slices with source-unit lengths [5, 9, 5, 5]; seams are scalar boundaries",
                ),
                EditableLineMeasurementCorpus(
                    id = "borrowed-fragmented-utf16",
                    description = "real mixed Latin, Arabic, emoji, accented-Latin text: “$SOURCE_TEXT”",
                    encoding = "UTF-16",
                    sourceUnits = 17,
                    scalarCount = 16,
                    fragmentation = "4 borrowed slices with source-unit lengths [5, 5, 3, 4]; seams are scalar boundaries",
                ),
                EditableLineMeasurementCorpus(
                    id = "mixed-script-editable-line",
                    description = "one LTR-base editable line mixing Latin, Arabic, emoji, and BiDi runs: “$SOURCE_TEXT”",
                    encoding = "UTF-16",
                    sourceUnits = 17,
                    scalarCount = 16,
                    fragmentation = "1 owned UTF-16 slice with 17 source units, decoded before layout timing",
                ),
            ),
            profiles = listOf(
                runUtf8Decode(utf8Slices, warmupIterations, iterations),
                runUtf16Decode(utf16Slices, warmupIterations, iterations),
                runColdLayout(layoutSnapshot, fontBytes, warmupIterations, iterations),
                runWarmLayout(layoutSnapshot, fontBytes, warmupIterations, iterations),
            ),
        )
    }

    private fun runUtf8Decode(
        slices: List<TextSlice.Utf8>,
        warmupIterations: Int,
        iterations: Int,
    ): EditableLineMeasurementProfile = measuredProfile(
        name = "BorrowedFragmentedUtf8Decode",
        corpusId = "borrowed-fragmented-utf8",
        timedBoundary = "starts immediately before public decodeUtf8 and ends after scalars, source ranges, and diagnostics are consumed and checked against literal independent oracles",
        state = "warm/stateless: borrowed storage and slices are prepared outside timing; configured warmup precedes measured samples",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            val sample = timed {
                val outcome = Kalligraphie.decodeUtf8(
                    TextVersion.create(),
                    slices,
                    TextDecodingProfile.unbounded,
                )
                consumeDecoded(
                    decodeSuccess(outcome),
                    SourceEncoding.UTF8,
                    EXPECTED_UTF8_BOUNDARIES,
                )
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun runUtf16Decode(
        slices: List<TextSlice.Utf16>,
        warmupIterations: Int,
        iterations: Int,
    ): EditableLineMeasurementProfile = measuredProfile(
        name = "BorrowedFragmentedUtf16Decode",
        corpusId = "borrowed-fragmented-utf16",
        timedBoundary = "starts immediately before public decodeUtf16 and ends after scalars, source ranges, and diagnostics are consumed and checked against literal independent oracles",
        state = "warm/stateless: borrowed storage and slices are prepared outside timing; configured warmup precedes measured samples",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            val sample = timed {
                val outcome = Kalligraphie.decodeUtf16(
                    TextVersion.create(),
                    slices,
                    TextDecodingProfile.unbounded,
                )
                consumeDecoded(
                    decodeSuccess(outcome),
                    SourceEncoding.UTF16,
                    EXPECTED_UTF16_BOUNDARIES,
                )
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun runColdLayout(
        snapshot: TextSnapshot,
        fontBytes: ByteArray,
        warmupIterations: Int,
        iterations: Int,
    ): EditableLineMeasurementProfile = measuredProfile(
        name = "ColdMixedBidiLine",
        corpusId = "mixed-script-editable-line",
        timedBoundary = "starts before embedded-font catalog capture and session opening; includes face resolution, font instantiation, request creation, layout, public-result consumption, and session closure",
        state = "cold: every warmup and measured sample creates and closes a new session and prepares a new DejaVu font catalog and instance",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        repeat(warmupIterations + iterations) { index ->
            val sample = timed {
                val font = prepareFont(fontBytes)
                val session = openSession()
                try {
                    consumeLayout(snapshot, layoutSuccess(session.layout(request(snapshot, font))))
                } finally {
                    closeSession(session)
                }
            }
            if (index >= warmupIterations) record(sample)
        }
    }

    private fun runWarmLayout(
        snapshot: TextSnapshot,
        fontBytes: ByteArray,
        warmupIterations: Int,
        iterations: Int,
    ): EditableLineMeasurementProfile = measuredProfile(
        name = "WarmMixedBidiLine",
        corpusId = "mixed-script-editable-line",
        timedBoundary = "starts immediately before reusable-session layout and ends after line, runs, glyphs, carets, provenance, diagnostics, and advances are consumed; preparation and closure are excluded",
        state = "warm: one font instance and one session are prepared and seeded by an untimed successful layout, then reused for warmup and measured samples",
        warmupIterations = warmupIterations,
        iterations = iterations,
    ) { record ->
        val font = prepareFont(fontBytes)
        val preparedRequest = request(snapshot, font)
        val session = openSession()
        try {
            consumeLayout(snapshot, layoutSuccess(session.layout(preparedRequest)))
            repeat(warmupIterations + iterations) { index ->
                val sample = timed {
                    consumeLayout(snapshot, layoutSuccess(session.layout(preparedRequest)))
                }
                if (index >= warmupIterations) record(sample)
            }
        } finally {
            closeSession(session)
        }
    }

    private fun measuredProfile(
        name: String,
        corpusId: String,
        timedBoundary: String,
        state: String,
        warmupIterations: Int,
        iterations: Int,
        operations: ((EditableLineRawSample) -> Unit) -> Unit,
    ): EditableLineMeasurementProfile {
        forceGc()
        val heapBefore = usedHeapBytes()
        val samples = mutableListOf<EditableLineRawSample>()
        operations(samples::add)
        forceGc()
        val heapAfter = usedHeapBytes()
        check(samples.size == iterations) {
            "Profile $name recorded ${samples.size} measured samples instead of $iterations."
        }
        val allocated = samples.mapNotNull(EditableLineRawSample::allocatedBytes)
        return EditableLineMeasurementProfile(
            name = name,
            corpusId = corpusId,
            timedBoundary = timedBoundary,
            state = state,
            warmupIterations = warmupIterations,
            iterations = iterations,
            latency = percentiles(samples.map(EditableLineRawSample::elapsedNanos)),
            allocations = if (allocated.size == samples.size) {
                EditableLineMeasurementValue.available(
                    allocated.sum() / allocated.size,
                    "average bytes allocated by the measured thread per iteration",
                )
            } else {
                EditableLineMeasurementValue.unavailable(
                    "thread allocation counters are unavailable on this JVM",
                )
            },
            jvmHeapDelta = EditableLineMeasurementValue.available(
                heapAfter - heapBefore,
                "signed used-heap delta after the documented forced-GC samples",
            ),
            nativeMemory = EditableLineMeasurementValue.unavailable(
                "the public JVM path exposes no reliable retained-native-byte boundary",
            ),
        )
    }

    private fun timed(operation: () -> Unit): EditableLineRawSample {
        val allocatedBefore = ThreadAllocationProbe.currentBytes()
        val startedAt = System.nanoTime()
        operation()
        val elapsed = max(1L, System.nanoTime() - startedAt)
        val allocatedAfter = ThreadAllocationProbe.currentBytes()
        val allocated = if (allocatedBefore != null && allocatedAfter != null) {
            max(0L, allocatedAfter - allocatedBefore)
        } else {
            null
        }
        return EditableLineRawSample(elapsed, allocated)
    }

    private fun consumeDecoded(
        result: TextDecodingResult,
        expectedEncoding: SourceEncoding,
        expectedBoundaries: List<Int>,
    ) {
        val checked = validateDecoded(result, expectedEncoding, expectedBoundaries)
        var checksum = checked.snapshot.scalars.fold(0L) { sum, scalar -> sum + scalar }
        checked.snapshot.sourceRanges.forEach { range ->
            checksum = checksum xor range.start.value.toLong()
            checksum += range.endExclusive.value
        }
        checked.diagnostics.forEach { diagnostic ->
            checksum = checksum xor diagnostic.code.hashCode().toLong()
            checksum += diagnostic.sourceRange.start.value + diagnostic.sourceRange.endExclusive.value
        }
        blackhole = blackhole xor checksum
    }

    private fun validateDecoded(
        result: TextDecodingResult,
        expectedEncoding: SourceEncoding,
        expectedBoundaries: List<Int>,
    ): TextDecodingResult {
        check(result.snapshot.sourceEncoding == expectedEncoding) {
            "Decoded source encoding did not match the profile."
        }
        check(result.snapshot.scalars == EXPECTED_SCALARS) {
            "Decoded scalar values did not match the literal mixed-script oracle."
        }
        val observedBoundaries = result.snapshot.sourceRanges.map { it.start.value } +
            result.snapshot.sourceRanges.last().endExclusive.value
        check(observedBoundaries == expectedBoundaries) {
            "Decoded source ranges did not match the literal source-unit oracle."
        }
        check(result.diagnostics.isEmpty()) {
            "Valid measurement text unexpectedly produced decoding diagnostics."
        }
        return result
    }

    private fun sourceBoundaries(result: TextDecodingResult): List<Int> =
        result.snapshot.sourceRanges.map { it.start.value } + result.snapshot.sourceRanges.last().endExclusive.value

    private fun consumeLayout(snapshot: TextSnapshot, result: EditableLineResult.Success) {
        val line = result.line
        check(scalarOrdinal(snapshot, line.range.start) == 0 && scalarOrdinal(snapshot, line.range.endExclusive) == 16) {
            "Editable-line result did not cover the complete literal corpus."
        }
        val runs = line.positionedGlyphRuns
        val logicalRunRanges = runs.map { run ->
            scalarOrdinal(snapshot, run.sourceRun.range.start) to scalarOrdinal(snapshot, run.sourceRun.range.endExclusive)
        }.sortedBy(Pair<Int, Int>::first)
        check(logicalRunRanges.first().first == 0 && logicalRunRanges.last().second == 16 &&
            logicalRunRanges.zipWithNext().all { (left, right) -> left.second == right.first }
        ) { "Editable-line runs did not partition the complete literal corpus." }
        check(runs.map { it.sourceRun.direction }.containsAll(
            listOf(ShapingDirection.LEFT_TO_RIGHT, ShapingDirection.RIGHT_TO_LEFT),
        )) { "Editable-line output did not retain both LTR and RTL run directions." }
        // Frozen external oracle from hb-shape 14.4.0 over the checked-in DejaVu fixture at 2048 units.
        check(runs.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } } == listOf(
            40, 71, 76, 87, 3,
            1390, 5366, 5293,
            3, 5857, 3,
            70, 68, 73, 171,
        )) {
            "Editable-line glyph identifiers did not match the audited DejaVu/HarfBuzz oracle."
        }
        check(runs.flatMap { run -> run.glyphs.map { it.advance.x.value } } == listOf(
            1294f, 1300f, 569f, 803f, 651f,
            1268f, 1222f, 1716f,
            651f, 2135f, 651f,
            1126f, 1255f, 721f, 1260f,
        )) {
            "Editable-line glyph advances did not match the audited DejaVu/HarfBuzz oracle."
        }
        check(runs.flatMap { it.glyphs }.all { it.provenance is GlyphProvenance.Direct }) {
            "Layout-only source glyphs must retain direct provenance."
        }
        val caretOrdinals = line.allCaretCandidates.map { scalarOrdinal(snapshot, it.position.index) }
        check(caretOrdinals.toSet() == (0..16).toSet()) {
            "Editable-line carets did not expose every literal scalar boundary."
        }
        check(line.diagnostics.isEmpty()) {
            "Valid mixed-script measurement text unexpectedly produced layout diagnostics."
        }

        var checksum = line.range.hashCode().toLong() + line.allCaretCandidates.size
        runs.forEach { run ->
            checksum = checksum xor run.sourceRun.backendIdentity.provenance.hashCode().toLong()
            checksum += run.visualOrder + run.sourceRun.bidiLevel
            run.glyphs.forEach { glyph ->
                checksum += glyph.shapedGlyph.glyphId.value
                checksum += glyph.advance.x.value.toRawBits()
                checksum = checksum xor glyph.provenance.hashCode().toLong()
                checksum += glyph.sourceClusters.size
            }
        }
        line.allCaretCandidates.forEach { caret ->
            checksum += caret.position.hashCode()
            checksum += caret.geometry.start.x.value.toRawBits()
            checksum += caret.bidiLevel + caret.visualOrder + caret.visualRunOrder
        }
        line.diagnostics.forEach { diagnostic -> checksum = checksum xor diagnostic.hashCode().toLong() }
        blackhole = blackhole xor checksum
    }

    private fun request(snapshot: TextSnapshot, font: FontInstance): JvmEditableLineFacadeRequest =
        JvmEditableLineFacadeRequest(
            snapshot = snapshot,
            font = font,
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
            features = emptyList(),
            verticalMetrics = LineVerticalMetrics(LayoutUnit(1900f), LayoutUnit(500f)),
            materialization = EditableLineMaterialization.LayoutOnly,
        )

    private fun prepareFont(bytes: ByteArray): FontInstance {
        val catalog = fontSuccess(
            Kalligraphie.embedded(
                sourceBytes = bytes,
                provenance = FontSourceProvenance(declaredName = "DejaVu Sans"),
            ),
            "capture DejaVu font catalog",
        )
        val face = fontSuccess(
            catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()),
            "resolve DejaVu layout face",
        )
        return fontSuccess(
            face.instantiate(FontInstanceDescriptor(layoutSize = LayoutUnit(2048f))),
            "instantiate DejaVu font",
        )
    }

    private fun openSession(): JvmEditableLineLayoutSession = fontSuccess(
        JvmEditableLineLayoutSession.open(),
        "open editable-line session",
    )

    private fun closeSession(session: JvmEditableLineLayoutSession) {
        fontSuccess(session.close(), "close editable-line session")
    }

    private fun <Value> fontSuccess(result: FontOperationResult<Value>, operation: String): Value = when (result) {
        is FontOperationResult.Success -> result.value
        is FontOperationResult.Failure -> error("Could not $operation: ${result.error.message}")
        is FontOperationResult.Cancelled -> error("Could not $operation: operation was cancelled.")
    }

    private fun decodeSuccess(result: TextDecodingOutcome): TextDecodingResult = when (result) {
        is TextDecodingOutcome.Success -> result.value
        is TextDecodingOutcome.Failure -> error("Measurement decode failed: ${result.reason}.")
        is TextDecodingOutcome.LimitExceeded -> error(
            "Measurement decode exceeded ${result.limit} at ${result.observed}.",
        )
        TextDecodingOutcome.Cancelled -> error("Measurement decode was cancelled.")
    }

    private fun layoutSuccess(result: EditableLineResult): EditableLineResult.Success =
        result as? EditableLineResult.Success
            ?: error("Measurement accepts only complete editable-line successes; received $result.")

    private fun scalarOrdinal(snapshot: TextSnapshot, index: TextIndex): Int =
        (0..EXPECTED_SCALARS.size).single { ordinal -> snapshot.textIndexAtScalarBoundary(ordinal) == index }

    private fun borrowedUtf8Slices(storage: Utf8Storage, lengths: List<Int>): List<TextSlice.Utf8> {
        var start = 0
        return lengths.map { length ->
            TextSlice.Utf8.borrow(storage, start, start + length).also { start += length }
        }.also { check(start == storage.length) }
    }

    private fun borrowedUtf16Slices(storage: Utf16Storage, lengths: List<Int>): List<TextSlice.Utf16> {
        var start = 0
        return lengths.map { length ->
            TextSlice.Utf16.borrow(storage, start, start + length).also { start += length }
        }.also { check(start == storage.length) }
    }

    private fun openedHarfBuzzVersion(): String = when (val opened = JvmHarfBuzzShapingBackend.open()) {
        is FontOperationResult.Success -> try {
            opened.value.identity.semantic.engineVersion
        } finally {
            fontSuccess(opened.value.close(), "close metadata HarfBuzz backend")
        }
        is FontOperationResult.Failure -> error("Could not identify HarfBuzz: ${opened.error.message}")
        is FontOperationResult.Cancelled -> error("Opening HarfBuzz for metadata was cancelled.")
    }

    private fun fixtureBytes(relativePath: String): ByteArray {
        EditableLineMeasurement::class.java.getResourceAsStream("/fonts/$relativePath")?.use { stream ->
            return stream.readBytes()
        }
        val candidates = listOf(
            Path.of("shaping", "src", "jvmTest", "resources", "fonts", relativePath),
            Path.of("kalligraphie", "shaping", "src", "jvmTest", "resources", "fonts", relativePath),
        )
        val fixture = checkNotNull(candidates.firstOrNull(Files::isRegularFile)) {
            "Missing measurement font fixture /fonts/$relativePath."
        }
        return Files.readAllBytes(fixture)
    }

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

    private fun percentiles(values: List<Long>): EditableLineMeasurementPercentiles {
        check(values.isNotEmpty()) { "At least one measured iteration is required." }
        val sorted = values.sorted()
        fun nearestRank(percentile: Double): Long = sorted[(ceil(percentile * sorted.size).toInt() - 1).coerceAtLeast(0)]
        return EditableLineMeasurementPercentiles(
            nearestRank(0.50),
            nearestRank(0.95),
            nearestRank(0.99),
        )
    }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun forceGc() {
        repeat(2) { System.gc() }
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private data class EditableLineRawSample(
        val elapsedNanos: Long,
        val allocatedBytes: Long?,
    )

    private class ImmutableByteStorage(bytes: ByteArray) : Utf8Storage {
        private val values = bytes.copyOf()
        override val length: Int = values.size
        override fun get(index: Int): Byte = values[index]
    }

    private class ImmutableCharStorage(chars: CharArray) : Utf16Storage {
        private val values = chars.copyOf()
        override val length: Int = values.size
        override fun get(index: Int): Char = values[index]
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

    @Volatile
    private var blackhole: Long = 0L
}
