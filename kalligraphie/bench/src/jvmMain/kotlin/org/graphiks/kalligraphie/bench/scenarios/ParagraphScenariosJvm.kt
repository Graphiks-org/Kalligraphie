package org.graphiks.kalligraphie.bench.scenarios

import org.graphiks.kalligraphie.JvmIncrementalParagraphLayoutSession
import org.graphiks.kalligraphie.JvmEditableLineLayoutSession
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.layout.openLayoutHandle
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.IncrementalLayoutResult
import org.graphiks.kalligraphie.api.LayoutDelta
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutStateHandle
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.TextDecodingOutcome
import org.graphiks.kalligraphie.api.TextDecodingProfile
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.shaping.HarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus

/**
 * The JVM paragraph scenarios, in canonical order: the incremental-layout profiles, the
 * editable-line decode and layout profiles, the consumer and session journeys, and the font-asset
 * handoff and concurrent waves. All of them need `END_TO_END_LAYOUT`.
 */

private const val INCREMENTAL_SOURCE_TEXT: String =
    "office cafe\nabc \u0633\u0644\u0627\u0645\nstable paragraph for viewport layout\nfinal line"

private const val INCREMENTAL_TARGET_TEXT: String =
    "office \uD83D\uDE00\nabc \u0633\u0644\u0627\u0645\nstable paragraph for viewport layout\nfinal line"

private val INCREMENTAL_FONTS = listOf(
    "dejavu/DejaVuSans.ttf" to "DejaVu Sans",
    "amiri/Amiri-Regular.ttf" to "Amiri Regular",
)

private class InteractiveEdit(private val corpus: FixtureCorpus) : PortableScenario(
    name = "InteractiveEdit",
    route = "incremental paragraph layout through one session, alternating edit and unedit",
    timedBoundary = "starts before the session layout of the prepared delta and ends after the complete certified result is consumed",
    cacheState = "warm: one untimed uncancelled seed layout, then the harness warmup, populate the session caches",
) {
    private val source = incrementalRealFontFixture(corpus, INCREMENTAL_SOURCE_TEXT, INCREMENTAL_FONTS)
    private val target = source.withText(INCREMENTAL_TARGET_TEXT)

    private var session: JvmIncrementalParagraphLayoutSession? = null
    private var current: IncrementalLayoutResult.Success? = null
    private var currentIsSource = true
    private val forward = incrementalChange(source, target, sourceStart = 7, sourceEnd = 11, targetStart = 7, targetEnd = 8)
    private val reverse = incrementalChange(target, source, sourceStart = 7, sourceEnd = 8, targetStart = 7, targetEnd = 11)

    override fun prepare() {
        val opened = openIncrementalSession()
        current = opened.layout(
            incrementalRequest(source, source.snapshot.incrementalRange(0, 18), overscan = 1),
        ).let { result ->
            result as? IncrementalLayoutResult.Success ?: error("InteractiveEdit seed failed: $result")
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

private class ViewportLayout(private val corpus: FixtureCorpus) : PortableScenario(
    name = "ViewportLayout",
    route = "incremental paragraph layout through one session, alternating viewport ranges",
    timedBoundary = "starts before the session layout of the prepared viewport move and ends after the complete certified result is consumed",
    cacheState = "warm: one untimed uncancelled seed layout, then the harness warmup, populate the session caches",
) {
    private val fixture = incrementalRealFontFixture(corpus, INCREMENTAL_SOURCE_TEXT, INCREMENTAL_FONTS)
    private var session: JvmIncrementalParagraphLayoutSession? = null
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

private class IncrementalCancellation(private val corpus: FixtureCorpus) : PortableScenario(
    name = "Cancellation",
    route = "incremental paragraph layout cancelled from a cooperative in-layout signal",
    timedBoundary =
        "starts before the session layout carrying the cancellation token and ends at the typed " +
            "cancelled return; the token allocation, excluded by the original harness, is included " +
            "because the standard harness has no per-invocation untimed hook",
    cacheState = "warm: one untimed uncancelled seed layout; the cache is warm so cancellation exercises real work",
) {
    private val fixture = incrementalRealFontFixture(corpus, INCREMENTAL_SOURCE_TEXT, INCREMENTAL_FONTS)
    private var session: JvmIncrementalParagraphLayoutSession? = null

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
        record("cancellationDelayNanos", (kotlin.time.TimeSource.Monotonic.markNow() - signal).inWholeNanoseconds)
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

private class BorrowedFragmentedUtf8Decode(private val corpus: FixtureCorpus) : PortableScenario(
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

private class BorrowedFragmentedUtf16Decode(private val corpus: FixtureCorpus) : PortableScenario(
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

private fun editableLineRequest(snapshot: TextSnapshot, font: FontInstance): org.graphiks.kalligraphie.JvmEditableLineFacadeRequest =
    org.graphiks.kalligraphie.JvmEditableLineFacadeRequest(
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

private class ColdMixedBidiLine(private val corpus: FixtureCorpus) : PortableScenario(
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
        val session = success(JvmEditableLineLayoutSession.open())
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

private class WarmMixedBidiLine(private val corpus: FixtureCorpus) : PortableScenario(
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
    private var session: JvmEditableLineLayoutSession? = null
    private var preparedRequest: org.graphiks.kalligraphie.JvmEditableLineFacadeRequest? = null

    override fun prepare() {
        val font = prepareDejaVu(corpus)
        val opened = success(JvmEditableLineLayoutSession.open())
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
) : PortableScenario(
    name = "RenderableConsumerCold${scenario.profileSuffix}",
    route = "JVM RENDERABLE consumer journey (${scenario.routeDescription})",
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

private class ConsumerWarm(private val scenario: ConsumerScenario) : PortableScenario(
    name = "RenderableConsumerWarm${scenario.profileSuffix}",
    route = "JVM RENDERABLE consumer journey (${scenario.routeDescription})",
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
) : PortableScenario(
    name = "Session${if (warm) "Warm" else "Cold"}${scenario.profileSuffix}",
    route = "reusable JVM RENDERABLE incremental session (${scenario.routeDescription})",
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
    private var reusedSession: JvmIncrementalParagraphLayoutSession? = null

    override fun prepare() {
        opened = openConsumerScenario(scenario)
        // Both profiles start with identical warmed portable render-asset state.
        observeConsumerLayout(this, layoutConsumerScenario(checkNotNull(opened)), checkNotNull(opened), 0)
        if (warm) {
            reusedSession = success(JvmIncrementalParagraphLayoutSession.open())
            observeSessionLayout(checkNotNull(reusedSession), backendReused = false)
        }
    }

    override fun operation() {
        val session = reusedSession ?: success(JvmIncrementalParagraphLayoutSession.open())
        try {
            observeSessionLayout(session, backendReused = warm)
        } finally {
            if (reusedSession == null) session.close()
        }
    }

    private fun observeSessionLayout(session: JvmIncrementalParagraphLayoutSession, backendReused: Boolean) {
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
            org.graphiks.kalligraphie.JvmIncrementalParagraphLayoutRequest(
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
) : PortableScenario(
    name = if (warm) "FontAssetRetainReopenWarm" else "FontAssetRetainReopenCold",
    route = "Liberation Sans stable text as one EditableLine -> public JvmEditableLineLayoutSession.layout -> openLayoutHandle -> retainFontAsset by complete key -> resolve every final certified glyph",
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

private class ConcurrentResolve(private val corpus: FixtureCorpus, private val fixture: CorpusFixture) : PortableScenario(
    name = "ConcurrentResolveWarm",
    route = "one renderer-owned Liberation Sans asset from public JvmEditableLineLayoutSession.layout -> openLayoutHandle -> retainFontAsset; 35 fixed distinct nonzero glyphs partitioned round-robin over four persistent workers",
    timedBoundary = "whole-wave wall time from dispatch until all four workers resolve and consume every corpus glyph exactly once; never divided by operations; allocation probes run inside workers",
    cacheState = "session/backend, resolver and layout handle closed before warmup; all corpus glyphs pre-resolved; workers started before timing; shared renderer asset closed after all waves",
) {
    private var asset: org.graphiks.kalligraphie.api.FontRenderAssetHandle? = null
    private var workers: List<java.util.concurrent.ThreadPoolExecutor> = emptyList()

    override fun prepare() {
        validateHandoffFixture(this, corpus, fixture)
        val opened = rendererAssetFromLayout(fixture)
        consumeOutlines(this, opened, HANDOFF_GLYPH_CORPUS)
        asset = opened
        workers = newPersistentWorkers()
    }

    override fun operation() {
        val results = dispatchConcurrentWave(checkNotNull(asset), workers)
        results.forEach { sink(it.checksum) }
        val workerAllocations = if (results.all { it.allocatedBytes != null }) {
            results.sumOf { checkNotNull(it.allocatedBytes) }
        } else {
            null
        }
        if (workerAllocations != null) record("workerAllocatedBytes", workerAllocations)
        count("waves")
    }

    override fun release() {
        workers.forEach(::closeWorker)
        workers = emptyList()
        success(checkNotNull(asset).close())
        asset = null
    }
}

/**
 * The JVM paragraph scenarios in canonical order. The consumer/session fixtures come from the same
 * corpus seam as the portable ones.
 */
public fun paragraphScenariosJvm(corpus: FixtureCorpus): List<org.graphiks.kalligraphie.bench.MeasurementScenario> {
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
        ConcurrentResolve(corpus, liberation),
    )
}
