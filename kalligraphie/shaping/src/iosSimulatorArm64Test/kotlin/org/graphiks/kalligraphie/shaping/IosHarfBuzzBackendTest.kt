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
import org.graphiks.kalligraphie.api.TextRange

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

    @Test
    fun reportsTheBundledIosSimulatorProvenance() {
        val provenance = backend().identity.provenance

        assertEquals("ios", provenance.operatingSystem)
        // The simulator test process runs the iosSimulatorArm64 slice; the portable identity
        // normalizes the Kotlin/Native target architecture to "arm64".
        assertEquals("arm64", provenance.architecture)
        assertEquals("harfbuzz", provenance.sourceProject)
        assertEquals(IOS_SOURCE_REVISION, provenance.sourceRevision)
        assertEquals(IOS_SIMULATOR_ARTIFACT_ID, provenance.artifactId)
        assertEquals(IOS_SIMULATOR_ARTIFACT_SHA256, provenance.artifactSha256)
        assertEquals(IOS_SIMULATOR_BUILD_CHAIN_IDENTITY, provenance.buildChainIdentity)
    }

    @AfterTest
    fun closeOpenedBackends() {
        backends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    private fun backend(): ShapingBackend = HarfBuzzShapingBackend.open().successValue().also(backends::add)

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

    private companion object {
        const val WORKER_COUNT = 8

        /**
         * Frozen kffi-harfbuzz iOS **simulator** (`iosSimulatorArm64`) slice provenance.
         *
         * These values are pinned by the published `org.graphiks:kffi-harfbuzz-iossimulatorarm64`
         * snapshot and the Xcode/CMake build chain that produced it. Re-publishing that snapshot —
         * or moving to a new Xcode toolchain — changes [IOS_SIMULATOR_ARTIFACT_SHA256] (and
         * possibly the artifactId/build chain), so refresh all of these constants together with the
         * binding upgrade. The device slice (`kffi-harfbuzz-iosarm64`, sha256
         * `d3393c61a7276578f203e6b7115d2ea549311d5d0be0d302963652c70e0a18b7`) is not addressable
         * from the simulator test process.
         */
        const val IOS_SOURCE_REVISION = "4c2aa804671d7276e8a0eb95da07202ead05c843"
        const val IOS_SIMULATOR_ARTIFACT_ID =
            "org.graphiks:kffi-harfbuzz-iossimulatorarm64:1.0.0-SNAPSHOT:iosSimulatorArm64/libharfbuzz.a"
        const val IOS_SIMULATOR_ARTIFACT_SHA256 =
            "f3c5e805c72362362e1b8f467dbd4f27ba07fb4f1f68619858c76de662764cb2"
        const val IOS_SIMULATOR_BUILD_CHAIN_IDENTITY =
            "cmake-4.4.3;xcode-26.6;appleclang-21.0.0;iphonesimulator-sdk-26.5;deployment-target-15.0"
    }
}
