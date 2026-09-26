package org.graphiks.kalligraphie.bench.scenarios

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.graphiks.kalligraphie.JvmEditableLineLayoutSession
import org.graphiks.kalligraphie.JvmIncrementalParagraphLayoutSession
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLine
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.IncrementalLayoutResult
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutStateHandle
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.TextChange
import org.graphiks.kalligraphie.api.TextChangeSet
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.TypographySnapshot
import org.graphiks.kalligraphie.api.TypographyVersion
import org.graphiks.kalligraphie.api.createIncrementalLayoutRequest
import org.graphiks.kalligraphie.bench.ThreadAllocationProbe
import org.graphiks.kalligraphie.layout.openLayoutHandle
import org.graphiks.kalligraphie.shaping.HarfBuzzShapingBackend

/**
 * JVM-only support for the paragraph scenarios: the consumer, session, handoff and concurrent
 * profiles of `GlyphMaterializationBenchmark`, plus the incremental-layout machinery of
 * `IncrementalLayoutBenchmark`. Everything here needs `END_TO_END_LAYOUT`.
 */
internal class OpenConsumerScenario(
    val scenario: ConsumerScenario,
    val catalog: FontCatalogSnapshot,
    val resolver: FontAssetResolverHandle,
    val policy: FontResolutionPolicySnapshot,
    val snapshot: TextSnapshot,
) {
    fun close() {
        resolver.close()
    }
}

internal class ConsumerScenario(
    val id: String,
    val fixtures: List<CorpusFixture>,
    val text: String,
    val language: String,
    val requirements: FontAccessRequirementsSnapshot,
) {
    val profileSuffix: String = when (id) {
        "single-font" -> "SingleFont"
        "mixed-bidi" -> "MixedBidi"
        else -> error("Unsupported consumer measurement scenario: $id")
    }

    val routeDescription: String = when (id) {
        "single-font" -> "one Bungee Color Latin glyph"
        "mixed-bidi" -> "Bungee Color Latin plus Liberation Sans Hebrew fallback"
        else -> error("Unsupported consumer measurement scenario: $id")
    }

    val sourceBytes: Long = fixtures.sumOf { fixture -> fixture.bytes.size.toLong() }

    val expectedFaceCount: Int = when (id) {
        "single-font" -> 1
        "mixed-bidi" -> 2
        else -> error("Unsupported consumer measurement scenario: $id")
    }
}

internal fun openConsumerScenario(scenario: ConsumerScenario): OpenConsumerScenario {
    val catalog = success(
        Kalligraphie.embedded(
            sources = scenario.fixtures.map { fixture ->
                FontSource(fixture.bytes, FontSourceProvenance(fixture.provenance))
            },
            cachePolicy = CACHE_POLICY,
        ),
    )
    val resolver = success(catalog.openAssetResolver())
    val policy = FontResolutionPolicySnapshot(
        generation = catalog.generation,
        policyId = "glyph-materialization-${scenario.id}",
        version = "1",
        candidates = catalog.faces.map { record -> FontResolutionCandidate(record.id) },
        lastResortFace = catalog.faces.last().id,
    )
    val snapshot = Kalligraphie.decodeUtf8(
        version = TextVersion.create(),
        slices = listOf(TextSlice.Utf8(scenario.text.encodeToByteArray())),
    ).snapshot
    return OpenConsumerScenario(scenario, catalog, resolver, policy, snapshot)
}

internal fun layoutConsumerScenario(opened: OpenConsumerScenario): ParagraphLayoutResult =
    org.graphiks.kalligraphie.JvmEditableParagraphFacade.layout(
        org.graphiks.kalligraphie.JvmEditableParagraphFacadeRequest(
            snapshot = opened.snapshot,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(8_000f), LayoutUnit(1_000f)),
                lineMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
            ),
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = opened.scenario.language,
            fontCatalog = opened.catalog,
            resolutionPolicy = opened.policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
            materialization = EditableLineMaterialization.Renderable(
                resolver = opened.resolver,
                renderVariant = FontRenderVariantSnapshot.default,
                requirements = opened.scenario.requirements,
            ),
        ),
    )

/** Observes one consumer layout, proving the certified glyphs and the expected faces came back. */
internal fun observeConsumerLayout(
    scenario: PortableScenario,
    result: ParagraphLayoutResult,
    opened: OpenConsumerScenario,
    sourceBytes: Long,
) {
    val layout = when (result) {
        is ParagraphLayoutResult.Success -> result.layout
        is ParagraphLayoutResult.Failure -> error("Consumer measurement failed: ${result.error}")
        is ParagraphLayoutResult.Cancelled -> error("Consumer measurement was unexpectedly cancelled.")
    }
    val glyphs = layout.lines.flatMap { line -> line.positionedGlyphRuns.flatMap { run -> run.glyphs } }
    check(glyphs.isNotEmpty()) { "Consumer measurement must publish final glyphs." }
    check(glyphs.all { glyph -> glyph.materializationCertificate != null }) {
        "Consumer measurement must publish only certified glyphs."
    }
    val faceCount = layout.lines
        .flatMap { line -> line.positionedGlyphRuns }
        .map { run -> run.fontInstanceKey.face }
        .distinct()
        .size
    check(faceCount == opened.scenario.expectedFaceCount) {
        "Consumer scenario ${opened.scenario.id} expected ${opened.scenario.expectedFaceCount} faces but received $faceCount."
    }
    scenario.count("certifiedGlyphs", glyphs.size.toLong())
    scenario.record("selectedFaces", faceCount.toLong())
    scenario.record("assetOpenings", glyphs.map { checkNotNull(it.materializationCertificate).assetKey }.distinct().size.toLong())
    if (sourceBytes > 0) scenario.count("sourceBytes", sourceBytes)
    scenario.count("consumerLayouts")
}

internal class OpenIncrementalFixture(
    val snapshot: TextSnapshot,
    val catalog: FontCatalogSnapshot,
    val policy: FontResolutionPolicySnapshot,
    val typography: TypographySnapshot,
) {
    /**
     * The same catalog, policy and typography checkpoint — version included — over a new text
     * version: the layout contract requires the typography version to stay unchanged across a plain
     * text edit, which the `TextChangeSet` delta carries instead.
     */
    fun withText(value: String): OpenIncrementalFixture = OpenIncrementalFixture(
        snapshot = incrementalSnapshot(value),
        catalog = catalog,
        policy = policy,
        typography = typography,
    )
}

internal fun incrementalSnapshot(value: String): TextSnapshot = Kalligraphie.decodeUtf16(
    TextVersion.create(),
    listOf(TextSlice.Utf16(value.toCharArray())),
).snapshot

internal fun incrementalRealFontFixture(
    corpus: org.graphiks.kalligraphie.bench.fixture.FixtureCorpus,
    value: String,
    fonts: List<Pair<String, String>>,
    policyId: String = "incremental-session-fixture",
): OpenIncrementalFixture {
    val sources = fonts.map { (relativePath, declaredName) ->
        FontSource(corpus.bytes("/fonts/$relativePath"), FontSourceProvenance(declaredName))
    }
    val catalog = success(Kalligraphie.embedded(sources))
    val faces = sources.map { source -> FontFaceId(source.id, 0) }
    val policy = FontResolutionPolicySnapshot(
        generation = catalog.generation,
        policyId = policyId,
        version = "1",
        candidates = faces.map(::FontResolutionCandidate),
        lastResortFace = faces.last(),
    )
    return OpenIncrementalFixture(
        snapshot = incrementalSnapshot(value),
        catalog = catalog,
        policy = policy,
        typography = TypographySnapshot(
            version = TypographyVersion.create(),
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
        ),
    )
}

internal fun TextSnapshot.incrementalRange(start: Int, endExclusive: Int): TextRange = TextRange(
    textIndexAtScalarBoundary(start),
    textIndexAtScalarBoundary(endExclusive),
)

internal fun incrementalRequest(
    fixture: OpenIncrementalFixture,
    requestedRange: TextRange,
    overscan: Int,
    previousState: LayoutStateHandle? = null,
    delta: org.graphiks.kalligraphie.api.LayoutDelta? = null,
    cancellationToken: org.graphiks.kalligraphie.api.CancellationToken = org.graphiks.kalligraphie.api.CancellationToken.none,
): org.graphiks.kalligraphie.JvmIncrementalParagraphLayoutRequest {
    val portable = when (
        val result = createIncrementalLayoutRequest(
            input = LayoutInput(fixture.snapshot, fixture.typography),
            requestedRange = requestedRange,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(1_500f), LayoutUnit(4_850f)),
                lineMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
            ),
            overscan = LineOverscan(overscan),
            previousState = previousState,
            delta = delta,
            cancellationToken = cancellationToken,
        )
    ) {
        is org.graphiks.kalligraphie.api.LayoutContractResult.Success -> result.value
        is org.graphiks.kalligraphie.api.LayoutContractResult.Failure ->
            error("Measurement request failed: ${result.error.code}: ${result.error.message}")
    }
    return org.graphiks.kalligraphie.JvmIncrementalParagraphLayoutRequest(
        request = portable,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        materialization = EditableLineMaterialization.LayoutOnly,
    )
}

internal fun incrementalChange(
    source: OpenIncrementalFixture,
    target: OpenIncrementalFixture,
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
    is org.graphiks.kalligraphie.api.LayoutContractResult.Success -> result.value
    is org.graphiks.kalligraphie.api.LayoutContractResult.Failure -> error("Measurement delta failed: ${result.error.code}: ${result.error.message}")
}

internal fun openIncrementalSession(): JvmIncrementalParagraphLayoutSession = when (
    val opened = JvmIncrementalParagraphLayoutSession.open()
) {
    is FontOperationResult.Success -> opened.value
    is FontOperationResult.Failure -> error("Could not open measurement session: ${opened.error.message}")
    is FontOperationResult.Cancelled -> error("Opening the measurement session was cancelled.")
}

/** Consumes a successful incremental layout, proving complete coverage and feeding the checksum. */
internal fun consumeIncrementalResult(scenario: PortableScenario, result: IncrementalLayoutResult.Success) {
    check(result.layout.coverage.isComplete) { "A fast incomplete result is not a successful measurement sample." }
    val covered = result.layout.coveredRange
    var checksum = covered.start.hashCode().toLong() xor covered.endExclusive.hashCode().toLong()
    var glyphCount = 0L
    result.layout.lines.forEach { line ->
        checksum = checksum xor line.range.hashCode().toLong()
        checksum += line.allCaretCandidates.size
        line.positionedGlyphRuns.forEach { run ->
            checksum = checksum xor run.sourceRun.backendIdentity.hashCode().toLong()
            run.glyphs.forEach { glyph ->
                checksum += glyph.shapedGlyph.glyphId.value.toLong()
                checksum += glyph.sourceClusters.size
                glyphCount += 1
            }
        }
    }
    checksum = checksum xor result.layout.coverage.tailState.hashCode().toLong()
    checksum = checksum xor result.diagnostics.hashCode().toLong()
    scenario.sink(checksum)
    scenario.count("shapedGlyphs", glyphCount)
}

internal class HandoffSession(  // AutoCloseable so callers can `use` it

    val catalog: FontCatalogSnapshot,
    val resolver: FontAssetResolverHandle,
    val font: FontInstance,
    val session: JvmEditableLineLayoutSession,
    private val owned: MeasurementOwners,
) : AutoCloseable {
    override fun close() = owned.close()

    fun layout(): EditableLine {
        val snapshot = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8(TRUE_TYPE_PARAGRAPH.encodeToByteArray())),
        ).snapshot
        val result = session.layout(
            org.graphiks.kalligraphie.JvmEditableLineFacadeRequest(
                snapshot = snapshot,
                font = font,
                baseDirection = BaseDirection.LEFT_TO_RIGHT,
                language = "en",
                featurePolicy = HarfBuzzShapingBackend.pinnedFeaturePolicy,
                features = emptyList(),
                verticalMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
                materialization = EditableLineMaterialization.Renderable(
                    resolver, FontRenderVariantSnapshot.default, trueTypeRequirements(),
                ),
            ),
        )
        return when (result) {
            is EditableLineResult.Success -> result.line
            else -> error("Handoff measurement failed: $result")
        }
    }

}

/** Runs all cleanup actions, including after a failed close; no production counters. */
internal class MeasurementOwners : AutoCloseable {
    private val actions = mutableListOf<() -> Unit>()

    fun add(close: () -> Unit) {
        actions.add(close)
    }

    override fun close() {
        var failure: Throwable? = null
        while (actions.isNotEmpty()) {
            try {
                actions.removeAt(actions.lastIndex)()
            } catch (cause: Throwable) {
                if (failure == null) failure = cause else failure.addSuppressed(cause)
            }
        }
        failure?.let { throw it }
    }
}

internal fun openHandoffSession(fixture: CorpusFixture): HandoffSession {
    val owned = MeasurementOwners()
    return try {
        val catalog = captureTrueTypeCatalog(fixture)
        val resolver = success(catalog.openAssetResolver()).also { value -> owned.add { success(value.close()) } }
        val face = success(catalog.resolveFace(catalog.faces.single().id, trueTypeRequirements()))
        val font = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(1_000f))))
        val session = success(JvmEditableLineLayoutSession.open()).also { value -> owned.add { success(value.close()) } }
        HandoffSession(catalog, resolver, font, session, owned)
    } catch (failure: Throwable) {
        try {
            owned.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}

/** The 35 distinct nonzero paragraph glyph ids, in first-occurrence order, frozen by the original audit. */
internal val HANDOFF_GLYPH_CORPUS: List<GlyphId> = listOf(
    53, 72, 68, 71, 69, 79, 3, 87, 92, 83, 82, 74, 85, 75, 78, 86, 90, 15,
    88, 81, 70, 76, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 89, 91, 17,
).map(::GlyphId)

/** Derives the renderer-owned asset through the real editable-line path, as the original profile did. */
internal fun rendererAssetFromLayout(fixture: CorpusFixture): FontRenderAssetHandle {
    var retained: FontRenderAssetHandle? = null
    return try {
        openHandoffSession(fixture).use { session ->
            val layout = session.layout()
            val handle = success(layout.openLayoutHandle(session.resolver))
            try {
                val certificates = layout.positionedGlyphRuns.flatMap { it.glyphs }
                    .map { checkNotNull(it.materializationCertificate) }
                check(certificates.map { it.assetKey }.distinct().size == 1)
                check(certificates.map { it.glyphId }.distinct() == HANDOFF_GLYPH_CORPUS)
                retained = success(handle.retainFontAsset(certificates.first()))
            } finally {
                success(handle.close())
            }
        }
        checkNotNull(retained)
    } catch (failure: Throwable) {
        try {
            retained?.let { success(it.close()) }
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}

/** Proves the fixture is the audited Liberation build before any handoff timing. */
internal fun validateHandoffFixture(scenario: PortableScenario, corpus: org.graphiks.kalligraphie.bench.fixture.FixtureCorpus, fixture: CorpusFixture) {
    check(corpus.sha256Hex(LIBERATION_BYTES_PATH) == "76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8")
    val asset = rendererAssetFromLayout(fixture)
    try {
        val outline = (success(asset.resolveGlyph(FontGlyphRequest(GlyphId(36)))) as GlyphRepresentation.Outline).outline
        check(outline.glyphId == 36 && outline.unitsPerEm == 2048)
        check(outline.bounds == org.graphiks.kalligraphie.api.DesignBounds(4, 0, 1362, 1409))
        check(outline.contours.size == 2)
        consumeOutlineRepresentation(scenario, GlyphId(36), GlyphRepresentation.Outline(outline))
    } finally {
        success(asset.close())
    }
}

internal class WorkerObservation(val checksum: Long, val allocatedBytes: Long?)

internal fun closeWorker(executor: ThreadPoolExecutor) {
    executor.shutdownNow()
    var interrupted = false
    while (!executor.isTerminated) {
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

internal fun dispatchConcurrentWave(asset: FontRenderAssetHandle, executors: List<ThreadPoolExecutor>): List<WorkerObservation> {
    val partitions = List(4) { worker -> HANDOFF_GLYPH_CORPUS.filterIndexed { index, _ -> index % 4 == worker } }
    return partitions.mapIndexed { worker, glyphs ->
        executors[worker].submit(
            Callable {
                val before = ThreadAllocationProbe.currentBytes()
                var checksum = 0L
                glyphs.forEach { glyph ->
                    val representation = success(asset.resolveGlyph(FontGlyphRequest(glyph)))
                    checksum += when (representation) {
                        is GlyphRepresentation.Outline -> representation.outline.let { outline ->
                            outline.glyphId.toLong() + outline.unitsPerEm + outline.bounds.minX + outline.bounds.minY +
                                outline.bounds.maxX + outline.bounds.maxY + outline.contours.size + outline.commands.size
                        }

                        GlyphRepresentation.Empty -> glyph.value.toLong()
                        else -> error("Concurrent outline measurement received $representation")
                    }
                }
                val after = ThreadAllocationProbe.currentBytes()
                WorkerObservation(checksum, if (before != null && after != null && after >= before) after - before else null)
            },
        )
    }.map { future -> future.get() }
}

internal fun newPersistentWorkers(): List<ThreadPoolExecutor> = List(4) {
    (Executors.newFixedThreadPool(1) as ThreadPoolExecutor).also { executor ->
        executor.prestartAllCoreThreads()
    }
}

/** Borrowed UTF-8 storage over an owned copy of the bytes, ported from the original harness. */
internal class ImmutableByteStorage(bytes: ByteArray) : org.graphiks.kalligraphie.api.Utf8Storage {
    private val values = bytes.copyOf()
    override val length: Int = values.size
    override fun get(index: Int): Byte = values[index]
}

/** Borrowed UTF-16 storage over an owned copy of the chars, ported from the original harness. */
internal class ImmutableCharStorage(chars: CharArray) : org.graphiks.kalligraphie.api.Utf16Storage {
    private val values = chars.copyOf()
    override val length: Int = values.size
    override fun get(index: Int): Char = values[index]
}
