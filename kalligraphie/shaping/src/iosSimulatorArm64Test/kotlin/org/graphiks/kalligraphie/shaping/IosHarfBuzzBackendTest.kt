package org.graphiks.kalligraphie.shaping

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GdefLigatureCaretState
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingResourceProfile
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.unicode.TextSnapshots

/**
 * B4: proves the iOS simulator target loads the real bundled HarfBuzz binding rather than the
 * removed stub and reproduces the frozen JVM-oracle output (glyph ids, clusters, advances/positions
 * and GDEF ligature carets) byte-exactly through the shared [canonicalShapingGolden] serializer.
 *
 * The host JVM cannot load an iOS `.a`; this simulator suite is the iOS validation path. The shared
 * fixture corpus is embedded into the test binary at build time (see `IosFixtureLoader`), so the
 * suite resolves the same `/fonts/...` entries the JVM and Android suites read.
 */
class IosHarfBuzzBackendTest {
    private val backends = mutableListOf<ShapingBackend>()

    @Test
    fun opensTheBundledIosBindingInsteadOfTheRemovedStub() {
        val opened = HarfBuzzShapingBackend.open()
        // The removed stub returned Failure(font.shaping-native-platform-unsupported); the real
        // bundled binding must open successfully on iOS.
        assertIs<FontOperationResult.Success<*>>(opened)
        val backend = (opened as FontOperationResult.Success<ShapingBackend>).value
        backends += backend

        assertEquals("harfbuzz-jvm", backend.identity.semantic.backendId)
        assertEquals("harfbuzz", backend.identity.semantic.engineId)
        assertEquals("14.3.0", backend.identity.semantic.engineVersion)
        assertEquals("ot", backend.identity.semantic.shaperId)
        assertEquals(HarfBuzzShapingBackend.pinnedFeaturePolicy, backend.identity.semantic.featurePolicy)
        assertTrue(backend.identity.semantic.configurationFingerprint.contains("monotone-characters"))
    }

    @Test
    fun shapesTheLatinLigatureToTheFrozenGolden() {
        val prepared = text("fi")
        val run = backend().shape(
            request(
                prepared,
                fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                ShapingDirection.LEFT_TO_RIGHT,
                OpenTypeScript("Latn"),
                "en",
                0,
                features = listOf(OpenTypeFeature("liga", 1)),
            ),
        ).successValue()

        assertEquals(listOf(GlyphId(5042)), run.glyphs.map { glyph -> glyph.glyphId })
        assertEquals(listOf(LayoutUnit(1290f)), run.glyphs.map { glyph -> glyph.xAdvance })
        assertEquals(listOf(ShaperClusterToken(0)), run.glyphs.map { glyph -> glyph.clusterToken })
        assertEquals(1, run.clusters.size)
        assertEquals(2, run.clusters.single().scalarRanges.size)
        assertEquals(GdefLigatureCaretState.ABSENT, run.ligatureCaretFacts.single().state)
        assertEquals(LATIN_LIGATURE_GOLDEN, canonicalShapingGolden(prepared.snapshot, run))
    }

    @Test
    fun shapesAnArabicItemWithItsParagraphContextLikeTheJvmOracle() {
        val prepared = text("ببب")
        val font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val item = range(prepared, 1, 2)
        fun shapeItem(itemRange: TextRange, contextRange: TextRange) = backend().shape(
            request(
                prepared, font, ShapingDirection.RIGHT_TO_LEFT, OpenTypeScript("Arab"), "ar", 1,
                itemRange = itemRange, contextRange = contextRange,
                graphemeRanges = prepared.scalarRanges()
                    .filter { it.start >= itemRange.start && it.endExclusive <= itemRange.endExclusive },
            ),
        ).successValue()

        val full = shapeItem(prepared.snapshot.range, prepared.snapshot.range)
        val isolated = shapeItem(item, item)
        val contextual = shapeItem(item, prepared.snapshot.range)
        val fullToken = full.clusters.single { it.sourceRange == item }.token
        val medial = full.glyphs.single { fullToken in it.clusterTokens }

        assertEquals(GlyphId(5260), medial.glyphId)
        assertEquals(medial.glyphId, contextual.glyphs.single().glyphId)
        assertTrue(isolated.glyphs.single().glyphId != contextual.glyphs.single().glyphId)
        assertTrue(medial.safetyFlags.unsafeToConcat)
        assertEquals(medial.safetyFlags.unsafeToConcat, contextual.glyphs.single().safetyFlags.unsafeToConcat)
        assertEquals(item, contextual.range)
        assertEquals(listOf(item), contextual.clusters.map { it.sourceRange })
        assertEquals(listOf(ShaperClusterToken(0)), contextual.glyphs.single().clusterTokens)
    }

    @Test
    fun shapesTheAmiriLigatureWithFrozenGdefCaretGoldens() {
        val prepared = text("ffi")
        val run = backend().shape(
            request(
                prepared,
                fontInstance("/fonts/amiri/Amiri-Regular.ttf", "Amiri Regular", LayoutUnit(1000f)),
                ShapingDirection.LEFT_TO_RIGHT,
                OpenTypeScript("Latn"),
                "en",
                0,
                featurePolicy = HarfBuzzShapingBackend.pinnedFeaturePolicy,
            ),
        ).successValue()

        assertEquals(listOf(GlyphId(6631)), run.glyphs.map { glyph -> glyph.glyphId })
        assertEquals(listOf(LayoutUnit(795f)), run.glyphs.map { glyph -> glyph.xAdvance })
        val fact = run.ligatureCaretFacts.single()
        assertEquals(GdefLigatureCaretState.AVAILABLE, fact.state)
        assertEquals(listOf(index(prepared, 1), index(prepared, 2)), fact.logicalSourceBoundaries)
        assertEquals(listOf(LayoutUnit(269f), LayoutUnit(537f)), fact.positions)
        assertEquals(AMIRI_LIGATURE_GOLDEN, canonicalShapingGolden(prepared.snapshot, run))
    }

    @Test
    fun shapesHebrewRightToLeftToTheFrozenGolden() {
        val prepared = text("שלום")
        val run = backend().shape(
            request(
                prepared,
                fontInstance("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans"),
                ShapingDirection.RIGHT_TO_LEFT,
                OpenTypeScript("Hebr"),
                "he",
                1,
            ),
        ).successValue()

        assertEquals(listOf(1293, 1285, 1292, 1305), run.glyphs.map { glyph -> glyph.glyphId.value })
        assertEquals(listOf(3, 2, 1, 0), run.glyphs.map { glyph -> glyph.clusterToken.value })
        assertEquals(listOf(1389f, 532f, 1085f, 1495f), run.glyphs.map { glyph -> glyph.xAdvance.value })
        assertEquals(listOf(0, 2, 2, 2), run.glyphs.map(::safetyMask))
        assertEquals(HEBREW_RTL_GOLDEN, canonicalShapingGolden(prepared.snapshot, run))
    }

    @Test
    fun shapesACombiningMarkToTheFrozenPositionGoldens() {
        val prepared = text("x\u0301")
        val run = backend().shape(
            request(
                prepared,
                fontInstance("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans"),
                ShapingDirection.LEFT_TO_RIGHT,
                OpenTypeScript("Latn"),
                "en",
                0,
                graphemeRanges = listOf(range(prepared, 0, 2)),
            ),
        ).successValue()

        assertEquals(listOf(GlyphId(91), GlyphId(707)), run.glyphs.map { glyph -> glyph.glyphId })
        assertEquals(LayoutUnit(-249f), run.glyphs[1].xOffset)
        assertEquals(LayoutUnit(-340f), run.glyphs[1].yOffset)
        assertEquals(COMBINING_MARK_GOLDEN, canonicalShapingGolden(prepared.snapshot, run))
    }

    @Test
    fun rejectsUnadjustedGdefCaretsForTheKernedLigatureFixtureLikeTheJvmOracle() {
        val prepared = text("fiV")
        val run = backend().shape(
            request(
                prepared,
                fontInstance(
                    "/fonts/gdef-kern/GdefKerningFixture.ttf",
                    "Kalligraphie GDEF Kerning Fixture",
                    LayoutUnit(1000f),
                ),
                ShapingDirection.LEFT_TO_RIGHT,
                OpenTypeScript("Latn"),
                "en",
                0,
            ),
        ).successValue()

        assertEquals(listOf(GlyphId(3), GlyphId(4)), run.glyphs.map { glyph -> glyph.glyphId })
        assertEquals(listOf(LayoutUnit(800f), LayoutUnit(600f)), run.glyphs.map { glyph -> glyph.xAdvance })
        val fact = run.ligatureCaretFacts.single()
        assertEquals(GdefLigatureCaretState.INCONSISTENT, fact.state)
        assertEquals(listOf(index(prepared, 1)), fact.logicalSourceBoundaries)
        assertEquals(emptyList(), fact.positions)
    }

    @Test
    fun releasesEveryPreparedFontAcrossAlternatingCycles() {
        // A one-entry budget alternating two real fonts forces a native font→face→blob release on
        // every shape. A wrong close order surfaces the kffi blob error
        // "no live descendant to release"; two cycles prove the order is correct and repeatable.
        val backend = HarfBuzzShapingBackend.open(
            PreparedFontCachePolicy.default.copy(maxEntries = 1),
        ).successValue()
        backends += backend
        val dejaVu = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val liberation = fontInstance("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans")

        repeat(2) {
            assertIs<FontOperationResult.Success<*>>(
                backend.shape(
                    request(
                        text("fi"),
                        dejaVu,
                        ShapingDirection.LEFT_TO_RIGHT,
                        OpenTypeScript("Latn"),
                        "en",
                        0,
                        features = listOf(OpenTypeFeature("liga", 1)),
                    ),
                ),
            )
            val mark = text("x\u0301")
            assertIs<FontOperationResult.Success<*>>(
                backend.shape(
                    request(
                        mark,
                        liberation,
                        ShapingDirection.LEFT_TO_RIGHT,
                        OpenTypeScript("Latn"),
                        "en",
                        0,
                        graphemeRanges = listOf(range(mark, 0, 2)),
                    ),
                ),
            )
        }

        assertIs<FontOperationResult.Success<Unit>>(backend.close())
        assertIs<FontOperationResult.Success<Unit>>(backend.close())
        val closed = assertIs<FontOperationResult.Failure>(
            backend.shape(
                request(
                    text("fi"),
                    dejaVu,
                    ShapingDirection.LEFT_TO_RIGHT,
                    OpenTypeScript("Latn"),
                    "en",
                    0,
                ),
            ),
        )
        assertIs<FontError.ResourceClosed>(closed.error)
    }

    @Test
    fun concurrentShapingPublishesOneCompleteAuditedRunAcrossTwoPrepareReleaseCycles() = runTest {
        val font = fontInstance("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans")
        val prepared = text("שלום")

        // Two open/prepare → concurrent shape → close/release cycles prove the backend's native
        // lifecycle is correct and repeatable under real contention (Dispatchers.Default is a
        // genuine multi-worker pool on Kotlin/Native under the new memory model).
        repeat(2) {
            val backend = backend()
            val observations = coroutineScope {
                (0 until WORKER_COUNT).map {
                    async(Dispatchers.Default) {
                        canonicalShapingGolden(
                            prepared.snapshot,
                            backend.shape(
                                request(
                                    prepared,
                                    font,
                                    ShapingDirection.RIGHT_TO_LEFT,
                                    OpenTypeScript("Hebr"),
                                    "he",
                                    1,
                                ),
                            ).successValue(),
                        )
                    }
                }.awaitAll()
            }

            assertEquals(List(WORKER_COUNT) { HEBREW_RTL_GOLDEN }, observations)
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @AfterTest
    fun closeOpenedBackends() {
        backends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    private fun backend(): ShapingBackend = HarfBuzzShapingBackend.open().successValue().also(backends::add)

    private fun request(
        prepared: PreparedText,
        font: FontInstance,
        direction: ShapingDirection,
        script: OpenTypeScript,
        language: String,
        bidiLevel: Int,
        featurePolicy: ShapingFeaturePolicy = HarfBuzzShapingBackend.pinnedFeaturePolicy,
        features: List<OpenTypeFeature> = emptyList(),
        graphemeRanges: List<TextRange> = prepared.scalarRanges(),
        resourceProfile: ShapingResourceProfile = ShapingResourceProfile.unbounded,
        itemRange: TextRange = prepared.snapshot.range,
        contextRange: TextRange = itemRange,
    ): ShapingRequest = ShapingRequest(
        snapshot = prepared.snapshot,
        itemRange = itemRange,
        contextRange = contextRange,
        font = font,
        direction = direction,
        script = script,
        language = language,
        bidiLevel = bidiLevel,
        bot = itemRange.start == contextRange.start,
        eot = itemRange.endExclusive == contextRange.endExclusive,
        featurePolicy = featurePolicy,
        features = features,
        graphemeClusters = graphemeRanges,
        resourceProfile = resourceProfile,
    )

    private fun text(value: String): PreparedText {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        return PreparedText(snapshot)
    }

    private fun fontInstance(
        resource: String,
        declaredName: String,
        layoutSize: LayoutUnit = LayoutUnit(2048f),
    ): FontInstance {
        val source = FontSource(IosFixtureLoader.fixtureBytes(resource), FontSourceProvenance(declaredName))
        val catalog = Kalligraphie.embedded(listOf(source)).successValue()
        val face = catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()).successValue()
        return face.instantiate(FontInstanceDescriptor(layoutSize = layoutSize)).successValue()
    }

    private fun range(text: PreparedText, start: Int, endExclusive: Int): TextRange =
        TextRange(index(text, start), index(text, endExclusive))

    private fun index(text: PreparedText, ordinal: Int) = text.snapshot.textIndexAtScalarBoundary(ordinal)

    private fun safetyMask(glyph: org.graphiks.kalligraphie.api.ShapedGlyph): Int =
        (if (glyph.safetyFlags.unsafeToBreak) 1 else 0) or (if (glyph.safetyFlags.unsafeToConcat) 2 else 0)

    private fun <T> FontOperationResult<T>.successValue(): T =
        assertIs<FontOperationResult.Success<T>>(this).value

    private class PreparedText(val snapshot: TextSnapshot) {
        fun scalarRanges(): List<TextRange> = snapshot.scalars.indices.map { scalar ->
            TextRange(snapshot.textIndexAtScalarBoundary(scalar), snapshot.textIndexAtScalarBoundary(scalar + 1))
        }
    }

    private companion object {
        const val WORKER_COUNT = 8

        /**
         * Frozen goldens produced by the JVM reference backend (the spec §9.3 oracle). They are
         * byte-identical to the values frozen by `HarfBuzzPortableBackendTest` and the Android
         * device suite; the iOS bundled binding must reproduce them byte for byte. Every metric is
         * the exact IEEE-754 bit pattern, so there is no numeric tolerance.
         */
        val LATIN_LIGATURE_GOLDEN: String = """
            range=0..2
            direction=LEFT_TO_RIGHT
            script=Latn
            language=en
            bidiLevel=0
            bot=true;eot=true
            glyphs=1
            glyph[0] id=5042 xAdv=1151418368 yAdv=0 xOff=0 yOff=0 ubr=0 utc=0 tokens=0
            clusters=1
            cluster[0] src=0..2 scalars=[0..1,1..2] boundaries=[0,1,2]
            carets=1
            caret[glyph=0] state=ABSENT boundaries=[1] positions=[]
        """.trimIndent() + "\n"

        val AMIRI_LIGATURE_GOLDEN: String = """
            range=0..3
            direction=LEFT_TO_RIGHT
            script=Latn
            language=en
            bidiLevel=0
            bot=true;eot=true
            glyphs=1
            glyph[0] id=6631 xAdv=1145487360 yAdv=0 xOff=0 yOff=0 ubr=0 utc=0 tokens=0
            clusters=1
            cluster[0] src=0..3 scalars=[0..1,1..2,2..3] boundaries=[0,1,2,3]
            carets=1
            caret[glyph=0] state=AVAILABLE boundaries=[1,2] positions=[1132888064,1141260288]
        """.trimIndent() + "\n"

        val HEBREW_RTL_GOLDEN: String = """
            range=0..4
            direction=RIGHT_TO_LEFT
            script=Hebr
            language=he
            bidiLevel=1
            bot=true;eot=true
            glyphs=4
            glyph[0] id=1293 xAdv=1152229376 yAdv=0 xOff=0 yOff=0 ubr=0 utc=0 tokens=3
            glyph[1] id=1285 xAdv=1141178368 yAdv=0 xOff=0 yOff=0 ubr=0 utc=1 tokens=2
            glyph[2] id=1292 xAdv=1149739008 yAdv=0 xOff=0 yOff=0 ubr=0 utc=1 tokens=1
            glyph[3] id=1305 xAdv=1153097728 yAdv=0 xOff=0 yOff=0 ubr=0 utc=1 tokens=0
            clusters=4
            cluster[0] src=0..1 scalars=[0..1] boundaries=[0,1]
            cluster[1] src=1..2 scalars=[1..2] boundaries=[1,2]
            cluster[2] src=2..3 scalars=[2..3] boundaries=[2,3]
            cluster[3] src=3..4 scalars=[3..4] boundaries=[3,4]
            carets=0
        """.trimIndent() + "\n"

        val COMBINING_MARK_GOLDEN: String = """
            range=0..2
            direction=LEFT_TO_RIGHT
            script=Latn
            language=en
            bidiLevel=0
            bot=true;eot=true
            glyphs=2
            glyph[0] id=91 xAdv=1149239296 yAdv=0 xOff=0 yOff=0 ubr=0 utc=1 tokens=0
            glyph[1] id=707 xAdv=0 yAdv=0 xOff=-1015480320 yOff=-1012269056 ubr=1 utc=1 tokens=1
            clusters=2
            cluster[0] src=0..1 scalars=[0..1] boundaries=[0]
            cluster[1] src=1..2 scalars=[1..2] boundaries=[2]
            carets=0
        """.trimIndent() + "\n"
    }
}
