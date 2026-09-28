package org.graphiks.kalligraphie.bench.scenarios

import org.graphiks.kalligraphie.IncrementalParagraphLayoutSession
import org.graphiks.kalligraphie.EditableLineLayoutSession
import org.graphiks.kalligraphie.EditableParagraphFacadeRequest
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.layout.openLayoutHandle
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.IncrementalLayoutResult
import org.graphiks.kalligraphie.api.LayoutDelta
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutStateHandle
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ParagraphStyleSnapshot
import org.graphiks.kalligraphie.api.ParagraphStyleSpan
import org.graphiks.kalligraphie.api.PositionedGlyphRun
import org.graphiks.kalligraphie.api.TextDecodingOutcome
import org.graphiks.kalligraphie.api.TextDecodingProfile
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.shaping.HarfBuzzShapingBackend
import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus

/**
 * The paragraph scenarios, in canonical order: the incremental-layout profiles, the editable-line
 * decode and layout profiles, the consumer and session journeys, and the font-asset handoff. All of
 * them need `END_TO_END_LAYOUT`, which every platform of `:kalligraphie:conformance` now declares.
 *
 * Nothing here is platform-specific any more: the facades and the portable Unicode analysis they
 * compose through are `commonMain` code, so these profiles measure the same routes on the JVM, on
 * ART and on the iOS simulator. The one profile that does not travel, `ConcurrentResolveWarm`, needs
 * a harness instrument rather than a product capability, and lives with that instrument in the
 * Java-family source set, declared through [org.graphiks.kalligraphie.bench.MeasurementInstrument].
 */

private const val INCREMENTAL_SOURCE_TEXT: String =
    "office cafe\nabc \u0633\u0644\u0627\u0645\nstable paragraph for viewport layout\nfinal line"

private const val INCREMENTAL_TARGET_TEXT: String =
    "office \uD83D\uDE00\nabc \u0633\u0644\u0627\u0645\nstable paragraph for viewport layout\nfinal line"

private val INCREMENTAL_FONTS = listOf(
    "dejavu/DejaVuSans.ttf" to "DejaVu Sans",
    "amiri/Amiri-Regular.ttf" to "Amiri Regular",
)

private class InteractiveEdit(private val corpus: FixtureCorpus) : ParagraphScenario(
    name = "InteractiveEdit",
    route = "incremental paragraph layout through one session, alternating edit and unedit",
    timedBoundary = "starts before the session layout of the prepared delta and ends after the complete certified result is consumed",
    cacheState = "warm: one untimed uncancelled seed layout, then the harness warmup, populate the session caches",
) {
    private val source = incrementalRealFontFixture(corpus, INCREMENTAL_SOURCE_TEXT, INCREMENTAL_FONTS)
    private val target = source.withText(INCREMENTAL_TARGET_TEXT)

    private var session: IncrementalParagraphLayoutSession? = null
    private var current: IncrementalLayoutResult.Success? = null
    private var currentIsSource = true
    private val forward = incrementalChange(source, target, sourceStart = 7, sourceEnd = 11, targetStart = 7, targetEnd = 8)
    private val reverse = incrementalChange(target, source, sourceStart = 7, sourceEnd = 8, targetStart = 7, targetEnd = 11)

    override fun prepare() {
        val opened = openIncrementalSession()
        current = opened.layout(
            incrementalRequest(source, source.snapshot.incrementalRange(0, 18), overscan = 1),
        ).let { result ->
            result as? IncrementalLayoutResult.Success ?: error("InteractiveEdit seed failed: ${describeIncrementalFailure(result)}")
        }
        session = opened
    }

    override fun operation() {
        val activeSession = checkNotNull(session)
        val nextIsSource = !currentIsSource
        val nextFixture = if (nextIsSource) source else target
        val delta = if (nextIsSource) reverse else forward
        val prepared = incrementalRequest(
            fixture = nextFixture,
            requestedRange = nextFixture.snapshot.incrementalRange(0, 18),
            overscan = 1,
            previousState = checkNotNull(current).layout.state,
            delta = LayoutDelta(text = delta),
        )
        val result = activeSession.layout(prepared)
        check(result is IncrementalLayoutResult.Success) { "InteractiveEdit accepted only complete successes; received $result." }
        consumeIncrementalResult(this, result)
        val coveredScalars = nextFixture.snapshot.scalarValues(result.layout.coveredRange).size.toLong()
        count("textScalars", coveredScalars)
        current = result
        currentIsSource = nextIsSource
    }

    override fun release() {
        session?.close()
        session = null
    }
}

private class ViewportLayout(private val corpus: FixtureCorpus) : ParagraphScenario(
    name = "ViewportLayout",
    route = "incremental paragraph layout through one session, alternating viewport ranges",
    timedBoundary = "starts before the session layout of the prepared viewport move and ends after the complete certified result is consumed",
    cacheState = "warm: one untimed uncancelled seed layout, then the harness warmup, populate the session caches",
) {
    private val fixture = incrementalRealFontFixture(corpus, INCREMENTAL_SOURCE_TEXT, INCREMENTAL_FONTS)
    private var session: IncrementalParagraphLayoutSession? = null
    private var current: IncrementalLayoutResult.Success? = null

    override fun prepare() {
        val opened = openIncrementalSession()
        current = opened.layout(
            incrementalRequest(fixture, fixture.snapshot.incrementalRange(0, 18), overscan = 2),
        ).let { result ->
            result as? IncrementalLayoutResult.Success ?: error("ViewportLayout seed failed: $result")
        }
        session = opened
    }

    override fun operation() {
        val requested = if (operations % 2 == 0L) {
            fixture.snapshot.incrementalRange(25, 52)
        } else {
            fixture.snapshot.incrementalRange(0, 18)
        }
        val prepared = incrementalRequest(
            fixture = fixture,
            requestedRange = requested,
            overscan = 2,
            previousState = checkNotNull(current).layout.state,
        )
        val result = checkNotNull(session).layout(prepared)
        check(result is IncrementalLayoutResult.Success) { "ViewportLayout accepted only complete successes; received $result." }
        consumeIncrementalResult(this, result)
        current = result
        operations += 1
    }

    private var operations: Long = 0

    override fun release() {
        session?.close()
        session = null
    }
}

private class IncrementalCancellation(private val corpus: FixtureCorpus) : ParagraphScenario(
    name = "Cancellation",
    route = "incremental paragraph layout cancelled from a cooperative in-layout signal",
    timedBoundary =
        "starts before the session layout carrying the cancellation token and ends at the typed " +
            "cancelled return; the token allocation, excluded by the original harness, is included " +
            "because the standard harness has no per-invocation untimed hook",
    cacheState = "warm: one untimed uncancelled seed layout; the cache is warm so cancellation exercises real work",
) {
    private val fixture = incrementalRealFontFixture(corpus, INCREMENTAL_SOURCE_TEXT, INCREMENTAL_FONTS)
    private var session: IncrementalParagraphLayoutSession? = null
    private var maxCancellationDelayNanos = 0L

    override fun prepare() {
        val opened = openIncrementalSession()
        val seed = opened.layout(
            incrementalRequest(fixture, fixture.snapshot.range, overscan = 0),
        )
        consumeIncrementalResult(this, seed as? IncrementalLayoutResult.Success ?: error("Cancellation seed failed: $seed"))
        session = opened
    }

    override fun operation() {
        val token = CancelsOnCheck(3)
        val prepared = incrementalRequest(
            fixture = fixture,
            requestedRange = fixture.snapshot.range,
            overscan = 0,
            cancellationToken = token,
        )
        val result = checkNotNull(session).layout(prepared)
        check(result == IncrementalLayoutResult.Cancelled) {
            "Cancellation profile accepted only typed cancelled outcomes."
        }
        val signal = checkNotNull(token.signaledAt) { "Cancellation profile returned before its token signaled." }
        // The maximum observed delay is published, not the last reading: across millions of
        // operations the last one can observe the cancellation in under the clock's resolution,
        // and a zero would be refused by the profile contract as a no-op.
        val delay = (kotlin.time.TimeSource.Monotonic.markNow() - signal).inWholeNanoseconds
        if (delay > maxCancellationDelayNanos) {
            maxCancellationDelayNanos = delay
            record("cancellationDelayNanosMax", delay)
        }
    }

    override fun release() {
        session?.close()
        session = null
    }
}

private const val EDITABLE_LINE_TEXT = "Edit سلام 😀 café"

private val EDITABLE_LINE_EXPECTED_SCALARS = listOf(
    0x45, 0x64, 0x69, 0x74, 0x20,
    0x633, 0x644, 0x627, 0x645, 0x20,
    0x1F600, 0x20, 0x63, 0x61, 0x66, 0xE9,
)

private val EDITABLE_LINE_UTF8_BOUNDARIES = listOf(0, 1, 2, 3, 4, 5, 7, 9, 11, 13, 14, 18, 19, 20, 21, 22, 24)

private val EDITABLE_LINE_UTF16_BOUNDARIES = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17)

private val UTF8_FRAGMENT_LENGTHS = listOf(5, 9, 5, 5)

private val UTF16_FRAGMENT_LENGTHS = listOf(5, 5, 3, 4)

private class BorrowedFragmentedUtf8Decode(private val corpus: FixtureCorpus) : ParagraphScenario(
    name = "BorrowedFragmentedUtf8Decode",
    route = "public decodeUtf8 over four borrowed storage-backed slices of mixed-script text",
    timedBoundary =
        "starts immediately before public decodeUtf8 and ends after scalars, source ranges, and " +
            "diagnostics are consumed and checked against literal independent oracles",
    cacheState = "warm/stateless: borrowed storage and slices are prepared outside timing",
) {
    private val slices: List<TextSlice.Utf8> by lazy {
        val storage = ImmutableByteStorage(EDITABLE_LINE_TEXT.encodeToByteArray())
        var start = 0
        UTF8_FRAGMENT_LENGTHS.map { length ->
            TextSlice.Utf8.borrow(storage, start, start + length).also { start += length }
        }.also { check(start == storage.length) }
    }

    override fun operation() {
        val outcome = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            slices,
            TextDecodingProfile.unbounded,
        )
        val result = outcome as? TextDecodingOutcome.Success ?: error("Decode measurement failed: $outcome")
        check(result.value.snapshot.scalars == EDITABLE_LINE_EXPECTED_SCALARS) {
            "Decoded scalar values did not match the literal mixed-script oracle."
        }
        val observed = result.value.snapshot.sourceRanges.map { it.start.value } +
            result.value.snapshot.sourceRanges.last().endExclusive.value
        check(observed == EDITABLE_LINE_UTF8_BOUNDARIES) {
            "Decoded source ranges did not match the literal source-unit oracle."
        }
        var checksum = result.value.snapshot.scalars.fold(0L) { sum, scalar -> sum + scalar }
        result.value.snapshot.sourceRanges.forEach { range ->
            checksum = checksum xor range.start.value.toLong()
            checksum += range.endExclusive.value
        }
        result.value.diagnostics.forEach { diagnostic ->
            checksum = checksum xor diagnostic.code.hashCode().toLong()
            checksum += diagnostic.sourceRange.start.value + diagnostic.sourceRange.endExclusive.value
        }
        sink(checksum)
        count("scalarsDecoded", result.value.snapshot.scalars.size.toLong())
    }
}

private class BorrowedFragmentedUtf16Decode(private val corpus: FixtureCorpus) : ParagraphScenario(
    name = "BorrowedFragmentedUtf16Decode",
    route = "public decodeUtf16 over four borrowed storage-backed slices of mixed-script text",
    timedBoundary =
        "starts immediately before public decodeUtf16 and ends after scalars, source ranges, and " +
            "diagnostics are consumed and checked against literal independent oracles",
    cacheState = "warm/stateless: borrowed storage and slices are prepared outside timing",
) {
    private val slices: List<TextSlice.Utf16> by lazy {
        val storage = ImmutableCharStorage(EDITABLE_LINE_TEXT.toCharArray())
        var start = 0
        UTF16_FRAGMENT_LENGTHS.map { length ->
            TextSlice.Utf16.borrow(storage, start, start + length).also { start += length }
        }.also { check(start == storage.length) }
    }

    override fun operation() {
        val outcome = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            slices,
            TextDecodingProfile.unbounded,
        )
        val result = outcome as? TextDecodingOutcome.Success ?: error("Decode measurement failed: $outcome")
        check(result.value.snapshot.scalars == EDITABLE_LINE_EXPECTED_SCALARS) {
            "Decoded scalar values did not match the literal mixed-script oracle."
        }
        val observed = result.value.snapshot.sourceRanges.map { it.start.value } +
            result.value.snapshot.sourceRanges.last().endExclusive.value
        check(observed == EDITABLE_LINE_UTF16_BOUNDARIES) {
            "Decoded source ranges did not match the literal source-unit oracle."
        }
        var checksum = result.value.snapshot.scalars.fold(0L) { sum, scalar -> sum + scalar }
        result.value.snapshot.sourceRanges.forEach { range ->
            checksum = checksum xor range.start.value.toLong()
            checksum += range.endExclusive.value
        }
        sink(checksum)
        count("scalarsDecoded", result.value.snapshot.scalars.size.toLong())
    }
}

private fun prepareDejaVu(corpus: FixtureCorpus): FontInstance {
    val bytes = corpus.bytes("/fonts/dejavu/DejaVuSans.ttf")
    val catalog = success(
        Kalligraphie.embedded(
            sourceBytes = bytes,
            provenance = org.graphiks.kalligraphie.api.FontSourceProvenance(declaredName = "DejaVu Sans"),
        ),
    )
    val face = success(catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()))
    return success(face.instantiate(FontInstanceDescriptor(layoutSize = LayoutUnit(2048f))))
}

private fun editableLineRequest(snapshot: TextSnapshot, font: FontInstance): org.graphiks.kalligraphie.EditableLineFacadeRequest =
    org.graphiks.kalligraphie.EditableLineFacadeRequest(
        snapshot = snapshot,
        font = font,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        featurePolicy = HarfBuzzShapingBackend.pinnedFeaturePolicy,
        features = emptyList(),
        verticalMetrics = LineVerticalMetrics(LayoutUnit(1900f), LayoutUnit(500f)),
        materialization = EditableLineMaterialization.LayoutOnly,
    )

/** The frozen DejaVu/HarfBuzz oracle from the original measurement: glyph ids and advances. */
private val FROZEN_GLYPH_IDS = listOf(
    40, 71, 76, 87, 3,
    1390, 5366, 5293,
    3, 5857, 3,
    70, 68, 73, 171,
)

private class ColdMixedBidiLine(private val corpus: FixtureCorpus) : ParagraphScenario(
    name = "ColdMixedBidiLine",
    route = "mixed-script editable line through a fresh font catalog, instance, and session",
    timedBoundary =
        "starts before embedded-font catalog capture and session opening; includes face resolution, " +
            "font instantiation, request creation, layout, public-result consumption, and session closure",
    cacheState = "cold: every sample creates and closes a new session and prepares a new DejaVu font catalog and instance",
) {
    private val snapshot: TextSnapshot by lazy {
        Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(EDITABLE_LINE_TEXT.toCharArray())),
        ).snapshot
    }

    override fun operation() {
        val font = prepareDejaVu(corpus)
        val session = success(EditableLineLayoutSession.open())
        try {
            val result = session.layout(editableLineRequest(snapshot, font))
            val line = (result as? org.graphiks.kalligraphie.api.EditableLineResult.Success)?.line
                ?: error("ColdMixedBidiLine accepted only complete successes; received $result.")
            consumeLine(line, snapshot)
        } finally {
            success(session.close())
        }
    }

    private fun consumeLine(line: org.graphiks.kalligraphie.api.EditableLine, snapshot: TextSnapshot) {
        val glyphs = line.positionedGlyphRuns.flatMap { run -> run.glyphs }
        check(glyphs.map { it.shapedGlyph.glyphId.value } == FROZEN_GLYPH_IDS) {
            "Editable-line glyph identifiers did not match the audited DejaVu/HarfBuzz oracle."
        }
        sink(line.range.hashCode().toLong())
        glyphs.forEach { glyph -> sink(glyph.shapedGlyph.glyphId.value.toLong()) }
        count("shapedGlyphs", glyphs.size.toLong())
    }
}

private class WarmMixedBidiLine(private val corpus: FixtureCorpus) : ParagraphScenario(
    name = "WarmMixedBidiLine",
    route = "mixed-script editable line through one reusable session",
    timedBoundary =
        "starts immediately before reusable-session layout and ends after line, runs, glyphs, carets, " +
            "provenance, diagnostics, and advances are consumed; preparation and closure are excluded",
    cacheState = "warm: one font instance and one session are prepared and seeded by an untimed successful layout, then reused",
) {
    private val snapshot: TextSnapshot by lazy {
        Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(EDITABLE_LINE_TEXT.toCharArray())),
        ).snapshot
    }
    private var session: EditableLineLayoutSession? = null
    private var preparedRequest: org.graphiks.kalligraphie.EditableLineFacadeRequest? = null

    override fun prepare() {
        val font = prepareDejaVu(corpus)
        val opened = success(EditableLineLayoutSession.open())
        val request = editableLineRequest(snapshot, font)
        val result = opened.layout(request)
        val line = (result as? org.graphiks.kalligraphie.api.EditableLineResult.Success)?.line
            ?: error("WarmMixedBidiLine seed failed: $result")
        check(line.positionedGlyphRuns.flatMap { run -> run.glyphs }.map { it.shapedGlyph.glyphId.value } == FROZEN_GLYPH_IDS) {
            "WarmMixedBidiLine seed did not match the audited oracle."
        }
        session = opened
        preparedRequest = request
    }

    override fun operation() {
        val result = checkNotNull(session).layout(checkNotNull(preparedRequest))
        val line = (result as? org.graphiks.kalligraphie.api.EditableLineResult.Success)?.line
            ?: error("WarmMixedBidiLine accepted only complete successes; received $result.")
        val glyphs = line.positionedGlyphRuns.flatMap { run -> run.glyphs }
        check(glyphs.map { it.shapedGlyph.glyphId.value } == FROZEN_GLYPH_IDS) {
            "Editable-line glyph identifiers did not match the audited DejaVu/HarfBuzz oracle."
        }
        sink(line.range.hashCode().toLong())
        glyphs.forEach { glyph -> sink(glyph.shapedGlyph.glyphId.value.toLong()) }
        count("shapedGlyphs", glyphs.size.toLong())
    }

    override fun release() {
        session?.let { session -> success(session.close()) }
        session = null
        preparedRequest = null
    }
}

private class ConsumerCold(
    private val scenario: ConsumerScenario,
) : ParagraphScenario(
    name = "RenderableConsumerCold${scenario.profileSuffix}",
    route = "RENDERABLE consumer journey (${scenario.routeDescription})",
    timedBoundary =
        "starts before embedded catalog creation and ends after the certified paragraph layout is " +
            "consumed; resolver closure is excluded, matching the original harness",
    cacheState = "cold: a new embedded catalog and resolver are created for every sample",
) {
    override fun operation() {
        val opened = openConsumerScenario(scenario)
        try {
            observeConsumerLayout(this, layoutConsumerScenario(opened), opened, scenario.sourceBytes)
        } finally {
            opened.close()
        }
    }
}

private class ConsumerWarm(private val scenario: ConsumerScenario) : ParagraphScenario(
    name = "RenderableConsumerWarm${scenario.profileSuffix}",
    route = "RENDERABLE consumer journey (${scenario.routeDescription})",
    timedBoundary =
        "starts immediately before the public paragraph facade and ends after the certified layout is " +
            "consumed; setup and resolver closure are excluded",
    cacheState = "warm: one catalog and resolver remain open; an untimed first layout seeds the per-face representation cache",
) {
    private var opened: OpenConsumerScenario? = null

    override fun prepare() {
        opened = openConsumerScenario(scenario)
        observeConsumerLayout(this, layoutConsumerScenario(checkNotNull(opened)), checkNotNull(opened), sourceBytes = 0)
    }

    override fun operation() {
        val active = checkNotNull(opened)
        observeConsumerLayout(this, layoutConsumerScenario(active), active, sourceBytes = 0)
    }

    override fun release() {
        opened?.close()
        opened = null
    }
}

private class ParagraphSession(
    private val scenario: ConsumerScenario,
    private val warm: Boolean,
) : ParagraphScenario(
    name = "Session${if (warm) "Warm" else "Cold"}${scenario.profileSuffix}",
    route = "reusable RENDERABLE incremental session (${scenario.routeDescription})",
    timedBoundary = if (warm) {
        "new text revision, session layout and certified-result consumption; catalog, resolver, session setup and closure excluded"
    } else {
        "session opening, new text revision, layout and certified-result consumption; catalog, resolver setup and all closure excluded"
    },
    cacheState = if (warm) {
        "one session/backend with a seeded prepared-font cache; every sample supplies a fresh text version"
    } else {
        "a fresh session/backend per sample; shared catalog and resolver seeded outside timing"
    },
) {
    private var opened: OpenConsumerScenario? = null
    private var reusedSession: IncrementalParagraphLayoutSession? = null

    override fun prepare() {
        opened = openConsumerScenario(scenario)
        // Both profiles start with identical warmed portable render-asset state.
        observeConsumerLayout(this, layoutConsumerScenario(checkNotNull(opened)), checkNotNull(opened), 0)
        if (warm) {
            reusedSession = success(IncrementalParagraphLayoutSession.open())
            observeSessionLayout(checkNotNull(reusedSession), backendReused = false)
        }
    }

    override fun operation() {
        val session = reusedSession ?: success(IncrementalParagraphLayoutSession.open())
        try {
            observeSessionLayout(session, backendReused = warm)
        } finally {
            if (reusedSession == null) session.close()
        }
    }

    private fun observeSessionLayout(session: IncrementalParagraphLayoutSession, backendReused: Boolean) {
        val active = checkNotNull(opened)
        val snapshot = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8(scenario.text.encodeToByteArray())),
        ).snapshot
        val request = when (
            val contract = org.graphiks.kalligraphie.api.createIncrementalLayoutRequest(
                input = org.graphiks.kalligraphie.api.LayoutInput(
                    snapshot,
                    org.graphiks.kalligraphie.api.TypographySnapshot(
                        version = org.graphiks.kalligraphie.api.TypographyVersion.create(),
                        fontCatalog = active.catalog,
                        resolutionPolicy = active.policy,
                        fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
                    ),
                ),
                requestedRange = snapshot.range,
                constraints = org.graphiks.kalligraphie.api.HorizontalParagraphConstraints(
                    region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(8_000f), LayoutUnit(1_000f)),
                    lineMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
                ),
                overscan = org.graphiks.kalligraphie.api.LineOverscan(0),
                previousState = null,
                delta = null,
                cancellationToken = org.graphiks.kalligraphie.api.CancellationToken.none,
            )
        ) {
            is org.graphiks.kalligraphie.api.LayoutContractResult.Success -> contract.value
            is org.graphiks.kalligraphie.api.LayoutContractResult.Failure ->
                error("Invalid session measurement request: ${contract.error}")
        }
        val result = session.layout(
            org.graphiks.kalligraphie.IncrementalParagraphLayoutRequest(
                request = request,
                baseDirection = BaseDirection.LEFT_TO_RIGHT,
                language = scenario.language,
                materialization = EditableLineMaterialization.Renderable(
                    resolver = active.resolver,
                    renderVariant = FontRenderVariantSnapshot.default,
                    requirements = scenario.requirements,
                ),
            ),
        )
        val layout = when (result) {
            is IncrementalLayoutResult.Success -> result.layout
            else -> error("Session measurement failed: $result")
        }
        val usage = session.preparedFontCacheUsage
        check(usage.activeLeases == 0 && usage.idleEntries == scenario.expectedFaceCount)
        val glyphs = layout.lines.flatMap { line -> line.positionedGlyphRuns.flatMap { run -> run.glyphs } }
        check(glyphs.isNotEmpty() && glyphs.all { it.materializationCertificate != null })
        count("certifiedGlyphs", glyphs.size.toLong())
        count("consumerLayouts")
        record("preparedSourceBytes", if (backendReused) 0 else usage.idleSourceBytes)
        record("estimatedPreparedNativeBytes", usage.idleEstimatedNativeBytes)
        record("backendReuses", if (backendReused) 1 else 0)
    }

    override fun release() {
        reusedSession?.let { it.close() }
        reusedSession = null
        opened?.close()
        opened = null
    }
}

private class Handoff(
    private val corpus: FixtureCorpus,
    private val fixture: CorpusFixture,
    private val warm: Boolean,
) : ParagraphScenario(
    name = if (warm) "FontAssetRetainReopenWarm" else "FontAssetRetainReopenCold",
    route = "Liberation Sans stable text as one EditableLine -> public EditableLineLayoutSession.layout -> openLayoutHandle -> retainFontAsset by complete key -> resolve every final certified glyph",
    timedBoundary = if (warm) {
        "fresh text version and complete renderable editable-line certification through renderer-asset and layout-handle closure; persistent catalog/resolver/face/font/public-session preparation, seed and cleanup excluded"
    } else {
        "fresh embedded catalog, resolver, face/font and public editable-line session/backend through complete line certification, handoff, glyph consumption and all owner cleanup"
    },
    cacheState = if (warm) {
        "one reusable public editable-line session with prepared font and resolver, with the real portable path seeded outside timing"
    } else {
        "fresh catalog/resolver/face/font/public-session/backend for every sample"
    },
) {
    private var persistent: HandoffSession? = null

    override fun prepare() {
        // The fixture audit uses independent literal facts, outside every measured sample.
        validateHandoffFixture(this, corpus, fixture)
        if (warm) {
            persistent = openHandoffSession(fixture)
            handoffSample(checkNotNull(persistent))
        }
    }

    override fun operation() {
        handoffSample(persistent)
    }

    private fun handoffSample(session: HandoffSession?) {
        val owned = MeasurementOwners()
        try {
            var active = session
            val line = (active ?: openHandoffSession(fixture).also {
                owned.add { it.close() }
                active = it
            }).layout()
            val handle = success(line.openLayoutHandle(checkNotNull(active).resolver))
            val retained = try {
                val certificates = line.positionedGlyphRuns.flatMap { it.glyphs }
                    .map { checkNotNull(it.materializationCertificate) }
                val byKey = certificates.groupBy { it.assetKey }
                byKey.mapValues { (_, values) ->
                    success(handle.retainFontAsset(values.first())).also { asset ->
                        owned.add { success(asset.close()) }
                    }
                }
            } finally {
                success(handle.close())
            }
            line.positionedGlyphRuns.flatMap { it.glyphs }
                .map { checkNotNull(it.materializationCertificate) }
                .forEach { certificate ->
                    consumeOutlineRepresentation(
                        this,
                        certificate.glyphId,
                        success(checkNotNull(retained[certificate.assetKey]).resolveGlyph(FontGlyphRequest(certificate.glyphId))),
                    )
                }
            if (session == null) count("sourceBytes", fixture.bytes.size.toLong())
        } finally {
            owned.close()
        }
    }

    override fun release() {
        persistent?.close()
        persistent = null
    }
}

// -------------------------------------------------------------------------------------------------
// Per-span styling: one paragraph over three faces, a face span and a `wght` variation span.
// -------------------------------------------------------------------------------------------------

/**
 * The styled paragraph, in logical order, chosen so every span is one word and two words repeat.
 *
 * `Wide` (unprefixed, Work Sans 400), `Libre` (face span, Liberation Sans), `Wide` (variation span,
 * Work Sans 700), `عربي` (Arabic, Amiri last resort).
 */
private const val STYLED_SPAN_TEXT = "Wide Libre Wide \u0639\u0631\u0628\u064A"

private const val STYLED_DEFAULT_WORK_START = 0
private const val STYLED_PREFERRED_LIBERATION_START = 5
private const val STYLED_PREFERRED_LIBERATION_END = 10
private const val STYLED_BOLD_WORK_START = 11
private const val STYLED_BOLD_WORK_END = 15
private const val STYLED_ARABIC_START = 16

internal const val STYLED_SPAN_WORK_SANS_BYTES_PATH = "/fonts/worksans/WorkSans[wght].ttf"

/** The audited SHA-256 of the variable Work Sans fixture the styled runs are shaped through. */
internal const val STYLED_SPAN_WORK_SANS_SHA256 =
    "f50f61f2ba738e239442d40bf1069adb195c224b6a5a73a581fc2f3ed62a9f63"

private val STYLED_SPAN_FONTS = listOf(
    STYLED_SPAN_WORK_SANS_BYTES_PATH to "Work Sans",
    LIBERATION_BYTES_PATH to "Liberation Sans",
    "/fonts/amiri/Amiri-Regular.ttf" to "Amiri Regular",
)

private val STYLED_BOLD_WGHT = FontVariationCoordinates(
    listOf(FontVariationCoordinate(tag = "wght", value = 700f)),
)

/** The three-face catalog, resolver, policy, snapshot and styled spans one sample composes. */
private class StyledSpanFixture(
    val catalog: FontCatalogSnapshot,
    val resolver: FontAssetResolverHandle,
    val policy: FontResolutionPolicySnapshot,
    val snapshot: TextSnapshot,
    val spans: ParagraphStyleSnapshot,
    val requirements: FontAccessRequirementsSnapshot,
    val workSansFace: FontFaceId,
    val liberationFace: FontFaceId,
    val amiriFace: FontFaceId,
    val sourceBytes: Long,
) : AutoCloseable {
    override fun close() {
        success(resolver.close())
    }
}

/** Proves the checked-in variable fixture is the audited Work Sans build before any timing. */
internal fun validateStyledSpanFixture(corpus: FixtureCorpus) {
    check(corpus.sha256Hex(STYLED_SPAN_WORK_SANS_BYTES_PATH) == STYLED_SPAN_WORK_SANS_SHA256) {
        "The styled-span paragraph fixture is not the audited Work Sans variable build."
    }
}

private fun TextSnapshot.styledSpanRange(start: Int, endExclusive: Int): TextRange = TextRange(
    textIndexAtScalarBoundary(start),
    textIndexAtScalarBoundary(endExclusive),
)

private fun openStyledSpanFixture(corpus: FixtureCorpus): StyledSpanFixture {
    val captured = STYLED_SPAN_FONTS.map { (path, declaredName) -> corpus.bytes(path) to declaredName }
    val sources = captured.map { (bytes, declaredName) -> FontSource(bytes, FontSourceProvenance(declaredName)) }
    val catalog = success(Kalligraphie.embedded(sources = sources, cachePolicy = CACHE_POLICY))
    val resolver = success(catalog.openAssetResolver())
    return try {
        val faces = sources.map { source -> FontFaceId(source.id, 0) }
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "styled-span-paragraph",
            version = "1",
            candidates = faces.map(::FontResolutionCandidate),
            lastResortFace = faces.last(),
        )
        val snapshot = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8(STYLED_SPAN_TEXT.encodeToByteArray())),
        ).snapshot
        val spans = ParagraphStyleSnapshot(
            listOf(
                ParagraphStyleSpan(
                    range = snapshot.styledSpanRange(STYLED_PREFERRED_LIBERATION_START, STYLED_PREFERRED_LIBERATION_END),
                    face = faces[1],
                ),
                ParagraphStyleSpan(
                    range = snapshot.styledSpanRange(STYLED_BOLD_WORK_START, STYLED_BOLD_WORK_END),
                    variation = STYLED_BOLD_WGHT,
                ),
            ),
        )
        StyledSpanFixture(
            catalog = catalog,
            resolver = resolver,
            policy = policy,
            snapshot = snapshot,
            spans = spans,
            requirements = trueTypeRequirements(),
            workSansFace = faces[0],
            liberationFace = faces[1],
            amiriFace = faces[2],
            sourceBytes = captured.sumOf { (bytes, _) -> bytes.size.toLong() },
        )
    } catch (failure: Throwable) {
        resolver.close()
        throw failure
    }
}

private fun styledSpanRequest(fixture: StyledSpanFixture): EditableParagraphFacadeRequest =
    EditableParagraphFacadeRequest(
        snapshot = fixture.snapshot,
        constraints = HorizontalParagraphConstraints(
            region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(20_000f), LayoutUnit(2_000f)),
            lineMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
        ),
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        fontCatalog = fixture.catalog,
        resolutionPolicy = fixture.policy,
        fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
        materialization = EditableLineMaterialization.Renderable(
            resolver = fixture.resolver,
            renderVariant = FontRenderVariantSnapshot.default,
            requirements = fixture.requirements,
        ),
        operationProfile = EditorOperationProfile.unbounded,
        styleSpans = fixture.spans,
    )

private fun layoutStyledSpanParagraph(fixture: StyledSpanFixture): ParagraphLayoutResult =
    org.graphiks.kalligraphie.EditableParagraphFacade.layout(styledSpanRequest(fixture))

private fun TextRange.overlapsStyledSpan(other: TextRange): Boolean =
    start < other.endExclusive && other.start < endExclusive

private fun List<PositionedGlyphRun>.singleRunCovering(index: TextIndex): PositionedGlyphRun {
    val matches = filter { run -> run.sourceRun.range.start <= index && index < run.sourceRun.range.endExclusive }
    check(matches.size == 1) { "Expected exactly one styled-span run to cover $index, found ${matches.size}." }
    return matches.single()
}

/**
 * Consumes one styled layout. The spans are asserted rather than assumed: the face span must really
 * promote the middle candidate, the variation span must reach a distinct instance, and the same
 * word must carry a different advance at the two weights.
 */
private fun observeStyledSpanLayout(
    scenario: PortableScenario,
    result: ParagraphLayoutResult,
    fixture: StyledSpanFixture,
    sourceBytes: Long,
) {
    val layout = when (result) {
        is ParagraphLayoutResult.Success -> result.layout
        is ParagraphLayoutResult.Failure -> error("Styled-span measurement failed: ${result.error}")
        is ParagraphLayoutResult.Cancelled -> error("Styled-span measurement was unexpectedly cancelled.")
    }
    val runs = layout.lines.flatMap { line -> line.positionedGlyphRuns }
    val glyphs = runs.flatMap { run -> run.glyphs }
    check(glyphs.isNotEmpty()) { "The styled-span paragraph published no final glyphs." }
    check(glyphs.all { glyph -> glyph.materializationCertificate != null }) {
        "The styled-span paragraph must publish only certified glyphs in renderable mode."
    }

    val defaultRun = runs.singleRunCovering(fixture.snapshot.textIndexAtScalarBoundary(STYLED_DEFAULT_WORK_START))
    val liberationRun = runs.singleRunCovering(fixture.snapshot.textIndexAtScalarBoundary(STYLED_PREFERRED_LIBERATION_START))
    val boldRun = runs.singleRunCovering(fixture.snapshot.textIndexAtScalarBoundary(STYLED_BOLD_WORK_START))
    val arabicRun = runs.singleRunCovering(fixture.snapshot.textIndexAtScalarBoundary(STYLED_ARABIC_START))

    check(defaultRun.fontInstanceKey.face == fixture.workSansFace) {
        "The unprefixed word resolved to ${defaultRun.fontInstanceKey.face} instead of Work Sans."
    }
    check(liberationRun.fontInstanceKey.face == fixture.liberationFace) {
        "The face span resolved to ${liberationRun.fontInstanceKey.face} instead of the preferred Liberation Sans."
    }
    check(boldRun.fontInstanceKey.face == fixture.workSansFace) {
        "The variation span resolved to ${boldRun.fontInstanceKey.face} instead of Work Sans."
    }
    check(arabicRun.fontInstanceKey.face == fixture.amiriFace) {
        "The Arabic fragment resolved to ${arabicRun.fontInstanceKey.face} instead of the last-resort Amiri."
    }
    check(defaultRun.fontInstanceKey.geometry.normalizedAxes.isEmpty()) {
        "The unprefixed Work Sans run carries a variation it never asked for."
    }
    check(boldRun.fontInstanceKey.geometry.normalizedAxes.isNotEmpty()) {
        "The variation-styled Work Sans run carries no variation axis."
    }
    check(defaultRun.fontInstanceKey != boldRun.fontInstanceKey) {
        "The two Work Sans runs share one instance key: the span variation did not reach the instance."
    }

    // The same word is shaped twice, so only the weight can explain an advance difference.
    val defaultW = defaultRun.glyphs.single { glyph ->
        glyph.mappedSourceRange.start == fixture.snapshot.textIndexAtScalarBoundary(STYLED_DEFAULT_WORK_START)
    }
    val boldW = boldRun.glyphs.single { glyph ->
        glyph.mappedSourceRange.start == fixture.snapshot.textIndexAtScalarBoundary(STYLED_BOLD_WORK_START)
    }
    check(defaultW.shapedGlyph.glyphId == boldW.shapedGlyph.glyphId) {
        "The two Wide runs no longer shape the same first glyph; the geometric comparison is meaningless."
    }
    check(boldW.advance.x.value > defaultW.advance.x.value) {
        "The wght=700 span did not widen the same glyph: ${boldW.advance.x.value} and " +
            "${defaultW.advance.x.value} layout units."
    }

    val selectedFaces = runs.map { run -> run.fontInstanceKey.face }.distinct()
    check(selectedFaces.size == 3) {
        "The styled-span paragraph drew ${selectedFaces.size} faces instead of the three policy faces."
    }
    val styledRuns = runs.count { run ->
        fixture.spans.spans.any { span -> span.range.overlapsStyledSpan(run.sourceRun.range) }
    }
    check(styledRuns == 2) {
        "The styled-span paragraph published $styledRuns styled runs instead of the two its spans name."
    }

    runs.forEach { run -> scenario.sink(run.fontInstanceKey.hashCode().toLong()) }
    glyphs.forEach { glyph -> scenario.sink(glyph.shapedGlyph.glyphId.value.toLong()) }
    scenario.count("shapedGlyphs", glyphs.size.toLong())
    scenario.record("selectedFaces", selectedFaces.size.toLong())
    scenario.record("styledRuns", styledRuns.toLong())
    if (sourceBytes > 0) scenario.count("sourceBytes", sourceBytes)
}

/**
 * Cold per-span styling: a fresh three-face catalog, resolver and materialization cache per sample.
 *
 * The public facade opens and closes its own HarfBuzz backend inside the timed boundary on every
 * call; nothing here reuses a shaping session.
 */
private class StyledSpanParagraphCold(private val corpus: FixtureCorpus) : ParagraphScenario(
    name = "StyledSpanParagraphCold",
    route =
        "one styled paragraph through a fresh three-face Work Sans/Liberation Sans/Amiri catalog and " +
            "resolver, composed by the public paragraph facade",
    timedBoundary =
        "starts before embedded-catalog capture and ends after the certified styled layout is consumed " +
            "and the resolver is closed; the public facade opens and closes its own HarfBuzz backend " +
            "inside the boundary on every call, so no shaping session is reused across samples",
    cacheState =
        "cold: a new catalog, resolver and materialization cache are captured and closed for every " +
            "sample; the public facade opens and closes its own HarfBuzz backend per call",
) {
    override fun prepare() {
        validateStyledSpanFixture(corpus)
    }

    override fun operation() {
        val opened = openStyledSpanFixture(corpus)
        try {
            observeStyledSpanLayout(this, layoutStyledSpanParagraph(opened), opened, opened.sourceBytes)
        } finally {
            opened.close()
        }
    }
}

/**
 * Warm per-span styling: the three-face catalog, resolver and materialization cache are reused.
 *
 * Only those caches are warm. The public facade still opens and closes its own HarfBuzz backend on
 * every call, so this profile never reuses a shaping session.
 */
private class StyledSpanParagraphWarm(private val corpus: FixtureCorpus) : ParagraphScenario(
    name = "StyledSpanParagraphWarm",
    route =
        "one reused three-face Work Sans/Liberation Sans/Amiri catalog and resolver composing the same " +
            "styled paragraph through repeated public paragraph facade calls",
    timedBoundary =
        "starts immediately before the public paragraph facade call and ends after the certified styled " +
            "layout is consumed; catalog, resolver and materialization-cache capture, seed and closure " +
            "are excluded, and the public facade still opens and closes its own HarfBuzz backend on " +
            "every call",
    cacheState =
        "warm: one catalog, resolver and materialization cache are seeded outside timing and reused; the " +
            "public facade opens and closes a fresh HarfBuzz backend per call, so only the catalog, " +
            "resolver and materialization caches are warm - no HarfBuzz session is reused",
) {
    private var opened: StyledSpanFixture? = null

    override fun prepare() {
        validateStyledSpanFixture(corpus)
        opened = openStyledSpanFixture(corpus)
        observeStyledSpanLayout(this, layoutStyledSpanParagraph(checkNotNull(opened)), checkNotNull(opened), 0)
    }

    override fun operation() {
        val active = checkNotNull(opened)
        observeStyledSpanLayout(this, layoutStyledSpanParagraph(active), active, 0)
    }

    override fun release() {
        opened?.close()
        opened = null
    }
}

/**
 * The paragraph scenarios in canonical order. The consumer/session fixtures come from the same
 * corpus seam as the portable ones. `ConcurrentResolveWarm` is not here: it belongs to a harness
 * instrument the Java family provides, so it is contributed by
 * `threadedInstrumentScenarios(...)` where that instrument exists.
 */
public fun paragraphScenarios(corpus: FixtureCorpus): List<org.graphiks.kalligraphie.bench.MeasurementScenario> {
    val colr = CorpusFixture("BungeeColor-Regular.ttf", "Bungee Color COLR v0", org.graphiks.kalligraphie.api.GlyphId(43), corpus.bytes("/fonts/bungee-color/BungeeColor-Regular.ttf"))
    val liberation = CorpusFixture("LiberationSans-Regular.ttf", "Liberation Sans Regular", org.graphiks.kalligraphie.api.GlyphId(36), corpus.bytes(LIBERATION_BYTES_PATH))
    val consumerSingle = ConsumerScenario(
        id = "single-font",
        fixtures = listOf(colr),
        text = "A",
        language = "en",
        requirements = colrRequirements(),
    )
    val consumerMixedBidi = ConsumerScenario(
        id = "mixed-bidi",
        fixtures = listOf(colr, liberation),
        text = "A\u05D0",
        language = "he",
        requirements = FontAccessRequirementsSnapshot.renderable(
            listOf(
                colrRequirements().acceptedProfiles.single(),
                outlineProfile(),
            ),
        ),
    )
    return listOf(
        InteractiveEdit(corpus),
        ViewportLayout(corpus),
        IncrementalCancellation(corpus),
        BorrowedFragmentedUtf8Decode(corpus),
        BorrowedFragmentedUtf16Decode(corpus),
        ColdMixedBidiLine(corpus),
        WarmMixedBidiLine(corpus),
        ConsumerCold(consumerSingle),
        ConsumerWarm(consumerSingle),
        ConsumerCold(consumerMixedBidi),
        ConsumerWarm(consumerMixedBidi),
        ParagraphSession(consumerSingle, warm = false),
        ParagraphSession(consumerSingle, warm = true),
        ParagraphSession(consumerMixedBidi, warm = false),
        ParagraphSession(consumerMixedBidi, warm = true),
        Handoff(corpus, liberation, warm = false),
        Handoff(corpus, liberation, warm = true),
        StyledSpanParagraphCold(corpus),
        StyledSpanParagraphWarm(corpus),
    )
}

/** Renders the nested typed failure codes so a web report never prints an opaque `[object Object]`. */
private fun describeIncrementalFailure(result: IncrementalLayoutResult): String {
    val failure = result as? IncrementalLayoutResult.Failure ?: return result.toString()
    val paragraph = failure.error as? org.graphiks.kalligraphie.api.IncrementalLayoutError.ParagraphFailure
    val paragraphError = paragraph?.paragraphError
    val font = paragraphError as? org.graphiks.kalligraphie.api.ParagraphLayoutError.FontFailure
    return "incremental=${failure.error.code} paragraph=${paragraphError?.code} " +
        "font=${font?.fontError?.code} fontMessage=${font?.fontError?.message}"
}
