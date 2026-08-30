package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontFaceRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.GdefLigatureCaretState
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalog
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import org.graphiks.kalligraphie.unicode.TextSnapshots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HarfBuzzJvmBackendTest {
    @Test
    fun directBackendReportsItsPinnedIdentityAndShapesAnAuditedLatinLigature() {
        val backend = backend()

        assertEquals("harfbuzz-jvm", backend.identity.backendId)
        assertEquals("14.3.0", backend.identity.nativeVersion)
        assertEquals("9f2f03173b7fee860cc00d999857d09fa4a362e2", backend.identity.nativeSourceRevision)
        assertEquals(expectedNativeArtifactId(), backend.identity.nativeArtifactId)
        assertEquals(expectedNativeArtifactSha256(), backend.identity.nativeArtifactSha256)
        assertTrue(backend.identity.configurationFingerprint.contains("monotone-characters"))

        val shaped = shape(
            backend = backend,
            text = "fi",
            font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            features = listOf(OpenTypeFeature("liga", 1)),
        )

        assertEquals(listOf(GlyphId(5042)), shaped.glyphs.map { it.glyphId })
        assertEquals(listOf(LayoutUnit(1290f)), shaped.glyphs.map { it.xAdvance })
        assertEquals(listOf(ShaperClusterToken(0)), shaped.glyphs.map { it.clusterToken })
    }

    @Test
    fun disabledStandardLigatureUsesTheFrozenSeparateGlyphsAndAdvances() {
        val shaped = shape(
            backend = backend(),
            text = "fi",
            font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            features = listOf(OpenTypeFeature("liga", 0)),
        )

        assertEquals(listOf(GlyphId(73), GlyphId(76)), shaped.glyphs.map { it.glyphId })
        assertEquals(listOf(LayoutUnit(721f), LayoutUnit(569f)), shaped.glyphs.map { it.xAdvance })
        assertEquals(listOf(ShaperClusterToken(0), ShaperClusterToken(1)), shaped.glyphs.map { it.clusterToken })
    }

    @Test
    fun nonDesignLayoutSizePreservesFrozenFractionalAdvance() {
        val shaped = shape(
            backend = backend(),
            text = "fi",
            font = fontInstance(
                "/fonts/dejavu/DejaVuSans.ttf",
                "DejaVu Sans",
                layoutSize = LayoutUnit(1000f),
            ),
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            features = listOf(OpenTypeFeature("liga", 1)),
        )

        assertEquals(LayoutUnit(629.8828f), shaped.glyphs.single().xAdvance)
    }

    @Test
    fun ligaturePreservesBidirectionalTextClusterGlyphProjectionsAndAuditedGdefAbsence() {
        val backend = backend()
        val prepared = text("fi")
        val shaped = backend.shape(
            request(
                prepared = prepared,
                font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                features = listOf(OpenTypeFeature("liga", 1)),
            ),
        ).successValue()

        val firstScalar = range(prepared, 0, 1)
        val secondScalar = range(prepared, 1, 2)
        val merged = range(prepared, 0, 2)
        val token = ShaperClusterToken(0)
        assertEquals(listOf(token), shaped.mappings.clustersForSource(firstScalar))
        assertEquals(listOf(token), shaped.mappings.clustersForSource(secondScalar))
        assertEquals(listOf(firstScalar, secondScalar), shaped.mappings.sourcesForCluster(token))
        assertEquals(listOf(0), shaped.mappings.glyphsForCluster(token))
        assertEquals(listOf(token), shaped.mappings.clustersForGlyph(0))
        assertEquals(merged, shaped.clusters.single().sourceRange)
        assertEquals(listOf(firstScalar, secondScalar), shaped.clusters.single().scalarRanges)
        assertEquals(
            listOf(index(prepared, 0), index(prepared, 1), index(prepared, 2)),
            shaped.clusters.single().admissibleGraphemeBoundaries,
        )
        assertEquals(GdefLigatureCaretState.ABSENT, shaped.ligatureCaretFacts.single().state)
    }

    @Test
    fun combiningMarkPreservesFrozenPositionAndSeparateSourceRelations() {
        val backend = backend()
        val prepared = text("x\u0301")
        val shaped = backend.shape(
            request(
                prepared = prepared,
                font = fontInstance("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans"),
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                graphemeRanges = listOf(range(prepared, 0, 2)),
            ),
        ).successValue()

        assertEquals(listOf(GlyphId(91), GlyphId(707)), shaped.glyphs.map { it.glyphId })
        assertEquals(LayoutUnit(-249f), shaped.glyphs[1].xOffset)
        assertEquals(LayoutUnit(-340f), shaped.glyphs[1].yOffset)
        assertEquals(listOf(ShaperClusterToken(0)), shaped.mappings.clustersForSource(range(prepared, 0, 1)))
        assertEquals(listOf(ShaperClusterToken(1)), shaped.mappings.clustersForSource(range(prepared, 1, 2)))
        assertEquals(listOf(0), shaped.mappings.glyphsForCluster(ShaperClusterToken(0)))
        assertEquals(listOf(1), shaped.mappings.glyphsForCluster(ShaperClusterToken(1)))
        assertEquals(listOf(range(prepared, 1, 2)), shaped.clusters[1].scalarRanges)
        assertEquals(listOf(index(prepared, 2)), shaped.clusters[1].admissibleGraphemeBoundaries)
        assertTrue(index(prepared, 1) !in shaped.clusters[1].admissibleGraphemeBoundaries)
    }

    @Test
    fun explicitRtlRunRetainsFrozenGlyphOrderFlagsLevelAndDirection() {
        val backend = backend()
        val font = fontInstance("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans")
        val shaped = shape(
            backend = backend,
            text = "שלום",
            font = font,
            direction = ShapingDirection.RIGHT_TO_LEFT,
            script = OpenTypeScript("Hebr"),
            language = "he",
            bidiLevel = 1,
        )

        assertEquals(ShapingDirection.RIGHT_TO_LEFT, shaped.direction)
        assertEquals(1, shaped.bidiLevel)
        assertEquals(OpenTypeScript("Hebr"), shaped.script)
        assertEquals("he", shaped.language)
        assertTrue(shaped.bot)
        assertTrue(shaped.eot)
        assertEquals(font.key, shaped.fontInstanceKey)
        assertEquals(backend.identity, shaped.backendIdentity)
        assertEquals(listOf(1293, 1285, 1292, 1305), shaped.glyphs.map { it.glyphId.value })
        assertEquals(listOf(3, 2, 1, 0), shaped.glyphs.map { it.clusterToken.value })
        assertEquals(listOf(0, 2, 2, 2), shaped.glyphs.map { glyph -> glyph.safetyFlags.mask() })
    }

    @Test
    fun randomFeatureIsRejectedBeforeNativeShaping() {
        val backend = backend()
        val result = backend.shape(
            request(
                prepared = text("fi"),
                font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                features = listOf(OpenTypeFeature("rand", 1)),
            ),
        )

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.shaping-feature-not-deterministic", failure.error.code)
    }

    @Test
    fun j1FontInstanceTransfersAdefensiveOpenTypeCopyToTheBackend() {
        val instance = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val extracted = instance.copyOpenTypeData().successValue()
        val changedCopy = extracted.copyBytes()
        changedCopy[0] = (changedCopy[0].toInt() xor 0x7F).toByte()

        val shaped = shape(
            backend = backend(),
            text = "fi",
            font = instance,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            features = listOf(OpenTypeFeature("liga", 1)),
        )

        assertEquals(GlyphId(5042), shaped.glyphs.single().glyphId)
    }

    @Test
    fun invalidRequestDoesNotInventALeftToRightDefault() {
        val prepared = text("a")

        assertFailsWith<IllegalArgumentException> {
            ShapingRequest(
                snapshot = prepared.snapshot,
                range = prepared.snapshot.range,
                font = fontInstance("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
                direction = ShapingDirection.RIGHT_TO_LEFT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                bot = true,
                eot = true,
                features = emptyList(),
                graphemeClusters = listOf(prepared.snapshot.range),
            )
        }
    }

    @Test
    fun scriptTagsRejectNonAsciiLetters() {
        assertFailsWith<IllegalArgumentException> { OpenTypeScript("Łatn") }
    }

    @Test
    fun unsupportedPlatformReturnsATypedFailureWithoutNativeFallback() {
        val result = HarfBuzzNativeLoader.load(HarfBuzzPlatform(osName = "Plan 9", architecture = "mips64"))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.shaping-native-platform-unsupported", failure.error.code)
    }

    private fun backend(): ShapingBackend = JvmHarfBuzzShapingBackend.open().successValue()

    private fun shape(
        backend: ShapingBackend,
        text: String,
        font: FontInstance,
        direction: ShapingDirection,
        script: OpenTypeScript,
        language: String,
        bidiLevel: Int,
        features: List<OpenTypeFeature> = emptyList(),
    ) = backend.shape(
        request(text(text), font, direction, script, language, bidiLevel, features = features),
    ).successValue()

    private fun request(
        prepared: PreparedText,
        font: FontInstance,
        direction: ShapingDirection,
        script: OpenTypeScript,
        language: String,
        bidiLevel: Int,
        features: List<OpenTypeFeature> = emptyList(),
        graphemeRanges: List<TextRange> = prepared.scalarRanges(),
    ): ShapingRequest =
        ShapingRequest(
            snapshot = prepared.snapshot,
            range = prepared.snapshot.range,
            font = font,
            direction = direction,
            script = script,
            language = language,
            bidiLevel = bidiLevel,
            bot = true,
            eot = true,
            features = features,
            graphemeClusters = graphemeRanges,
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
        val source = FontSource(fixtureBytes(resource), FontSourceProvenance(declaredName))
        val parsed = SfntReader.readMetadata(source).successValue()
        val catalog = EmbeddedFontCatalog(source, parsed)
        val face = catalog.resolveFace(FontFaceRequest(0), FontAccessRequirementsSnapshot.layoutOnly()).successValue()
        return face.instantiate(FontInstanceDescriptor(layoutSize = layoutSize)).successValue()
    }

    private fun fixtureBytes(resource: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }

    private fun range(text: PreparedText, start: Int, endExclusive: Int): TextRange =
        TextRange(index(text, start), index(text, endExclusive))

    private fun index(text: PreparedText, ordinal: Int) = text.snapshot.textIndexAtScalarBoundary(ordinal)

    private fun <T> FontOperationResult<T>.successValue(): T =
        assertIs<FontOperationResult.Success<T>>(this).value

    private fun expectedNativeArtifactId(): String = when (System.getProperty("os.name") to System.getProperty("os.arch")) {
        "Mac OS X" to "aarch64" -> "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-macos-arm64/libharfbuzz.dylib"
        "Mac OS X" to "x86_64" -> "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-macos/libharfbuzz.dylib"
        "Linux" to "aarch64" -> "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux-arm64/libharfbuzz.so"
        "Linux" to "amd64" -> "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux/libharfbuzz.so"
        else -> error("Unexpected shaping test platform.")
    }

    private fun expectedNativeArtifactSha256(): String = when (System.getProperty("os.name") to System.getProperty("os.arch")) {
        "Mac OS X" to "aarch64" -> "302418f6ec10fee5e69fbe8b79f3b47e008f081ee88c912d19d2a9d820e7b9da"
        "Mac OS X" to "x86_64" -> "4f83ffccaf2a92e4658db8353ac7d529c52d5e4d34027a92cf9870487e1bc68b"
        "Linux" to "aarch64" -> "b1c7c67034297763e0ce46f3749c4da33a4bb4064929868446cb5a3d81dc26bc"
        "Linux" to "amd64" -> "9a5e3576912c2f8c8b2533d4a264fec1eac9667adfd64f7e71e80179ba118614"
        else -> error("Unexpected shaping test platform.")
    }

    private class PreparedText(val snapshot: org.graphiks.kalligraphie.api.TextSnapshot) {
        fun scalarRanges(): List<TextRange> = snapshot.scalars.indices.map { scalar ->
            TextRange(snapshot.textIndexAtScalarBoundary(scalar), snapshot.textIndexAtScalarBoundary(scalar + 1))
        }
    }

    private fun org.graphiks.kalligraphie.api.ShapingSafetyFlags.mask(): Int =
        (if (unsafeToBreak) 1 else 0) or (if (unsafeToConcat) 2 else 0)
}
