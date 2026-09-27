package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.Kalligraphie
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ParagraphStyleSnapshot
import org.graphiks.kalligraphie.api.ParagraphStyleSpan
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.shaping.HarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmLineBreakAnalyzer
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.unicode.TextSnapshots

/**
 * Pins per-span variation projection, instance cache identity, and run grouping.
 *
 * The resolution policy is `[Work Sans (variable wght), DejaVu Sans (static, last resort)]`.
 * U+2764 is mapped by DejaVu Sans and not by Work Sans, so it forces a static fallback inside a
 * styled range. The corrupted-`fvar` fixture patches the Work Sans table's reserved field so that
 * `FvarReader` reports a typed data failure rather than an absent axis list.
 */
class StyleSpanVariationTest {
    private val openedBackends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        openedBackends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun aSpanVariationProducesADistinctInstanceKeyNextToDefaultText() {
        val base = fixture("ab")
        val fixture = base.withStyles(
            ParagraphStyleSnapshot(
                listOf(ParagraphStyleSpan(range(base.snapshot, 0, 1), variation = wght(700f))),
            ),
        )

        val line = compose(fixture).lines.single().line

        assertEquals(2, line.positionedGlyphRuns.size)
        val keys = line.positionedGlyphRuns.map { it.fontInstanceKey }
        assertEquals(2, keys.toSet().size)
        assertEquals(1, keys.count { it.geometry.normalizedAxes.isNotEmpty() })
        assertEquals(1, keys.count { it.geometry.normalizedAxes.isEmpty() })
    }

    @Test
    fun adjacentSpansWithDifferentVariationStayAsTwoRuns() {
        val base = fixture("ab")
        val fixture = base.withStyles(
            ParagraphStyleSnapshot(
                listOf(
                    ParagraphStyleSpan(range(base.snapshot, 0, 1), variation = wght(700f)),
                    ParagraphStyleSpan(range(base.snapshot, 1, 2), variation = wght(300f)),
                ),
            ),
        )

        val line = compose(fixture).lines.single().line

        assertEquals(2, line.positionedGlyphRuns.size)
        assertEquals(2, line.positionedGlyphRuns.map { it.fontInstanceKey }.toSet().size)
    }

    @Test
    fun aStaticFallbackProjectsTheSpanVariationAndReportsIt() {
        val base = fixture("A\u2764")
        val fixture = base.withStyles(
            ParagraphStyleSnapshot(
                listOf(ParagraphStyleSpan(base.snapshot.range, variation = wght(700f))),
            ),
        )

        val line = compose(fixture).lines.single().line

        val workSans = line.positionedGlyphRuns.single { it.fontInstanceKey.face == base.workSansFace }
        val fallback = line.positionedGlyphRuns.single { it.fontInstanceKey.face == base.dejaVuFace }
        assertTrue(workSans.fontInstanceKey.geometry.normalizedAxes.isNotEmpty())
        // The static fallback keeps the paragraph variation (none), not the span's wght=700.
        assertTrue(fallback.fontInstanceKey.geometry.normalizedAxes.isEmpty())
        assertEquals(1, line.diagnostics.count { it.code == "font.fallback.span-variation-projected" })
    }

    @Test
    fun anEmptySelectionOnAStaticPreferredFaceIsAcceptedWithoutProjection() {
        val base = fixture("ab")
        val fixture = base.withStyles(
            ParagraphStyleSnapshot(
                listOf(
                    ParagraphStyleSpan(
                        range(base.snapshot, 0, 1),
                        face = base.dejaVuFace,
                        variation = FontVariationCoordinates.default,
                    ),
                ),
            ),
        )

        val line = compose(fixture).lines.single().line

        assertEquals(base.dejaVuFace, line.positionedGlyphRuns.first().fontInstanceKey.face)
        assertTrue(line.diagnostics.none { it.code == "font.fallback.span-variation-projected" })
        assertTrue(line.diagnostics.none { it.code == "font.fallback.span-face-unavailable" })
    }

    @Test
    fun anUnreadableFvarIsRejectedWithItsOriginalDataDiagnostic() {
        val base = fixture(
            "A",
            faces = listOf(FaceFixture("Corrupted Work Sans", corruptFvar(resourceBytes(WORK_SANS)))),
        )
        val fixture = base.withStyles(
            ParagraphStyleSnapshot(
                listOf(ParagraphStyleSpan(base.snapshot.range, variation = wght(700f))),
            ),
        )

        // A static reading of the corrupt face would project the selection and succeed; the typed
        // data failure proves the unreadable table was never collapsed into "no axes".
        val failure = assertIs<ParagraphCompositionResult.Failure>(
            ParagraphComposer.compose(fixture.request, EditableLineMaterialization.LayoutOnly),
        )
        assertTrue(failure.diagnostics.any { it.code == "font.variation.invalid-fvar" })
    }

    private fun compose(fixture: Fixture): ParagraphCompositionResult.Success =
        assertIs(ParagraphComposer.compose(fixture.request, EditableLineMaterialization.LayoutOnly))

    private fun wght(value: Float): FontVariationCoordinates =
        FontVariationCoordinates(listOf(FontVariationCoordinate("wght", value)))

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(endExclusive))

    private fun source(bytes: ByteArray, declaredName: String): FontSource =
        FontSource(bytes, FontSourceProvenance(declaredName))

    private fun resourceBytes(resource: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(resource)) { "Missing font fixture $resource." }.use { it.readBytes() }

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value

    private class FaceFixture(val declaredName: String, val bytes: ByteArray)

    private class Fixture(
        val snapshot: TextSnapshot,
        val request: ParagraphLayoutRequest,
        val faceIds: List<FontFaceId>,
    ) {
        val workSansFace: FontFaceId get() = faceIds[0]
        val dejaVuFace: FontFaceId get() = faceIds[1]

        fun withStyles(styleSpans: ParagraphStyleSnapshot): Fixture = Fixture(
            snapshot = snapshot,
            request = ParagraphLayoutRequest(
                snapshot = request.snapshot,
                sourceRange = request.sourceRange,
                unicodeAnalysis = request.unicodeAnalysis,
                lineBreakAnalysis = request.lineBreakAnalysis,
                constraints = request.constraints,
                baseDirection = request.baseDirection,
                language = request.language,
                featurePolicy = request.featurePolicy,
                features = request.features,
                fontCatalog = request.fontCatalog,
                resolutionPolicy = request.resolutionPolicy,
                fontInstanceDescriptor = request.fontInstanceDescriptor,
                shapingBackend = request.shapingBackend,
                materializationIdentity = request.materializationIdentity,
                operationProfile = request.operationProfile,
                styleSpans = styleSpans,
            ),
            faceIds = faceIds,
        )
    }

    private fun fixture(
        value: String,
        faces: List<FaceFixture> = listOf(
            FaceFixture("Work Sans", resourceBytes(WORK_SANS)),
            FaceFixture("DejaVu Sans", resourceBytes(DEJAVU)),
            FaceFixture("Liberation Sans", resourceBytes(LIBERATION)),
        ),
    ): Fixture {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        val unicodeAnalysis = JvmUnicodeAnalyzer.create().analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, "en"),
        )
        val lineBreakAnalysis = JvmLineBreakAnalyzer.create().analyze(snapshot, unicodeAnalysis)
        val sources = faces.map { face -> source(face.bytes, face.declaredName) }
        val catalog: FontCatalogSnapshot = Kalligraphie.embedded(sources).successValue()
        val faceIds = sources.map { source -> FontFaceId(source.id, 0) }
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "style-span-variation-policy",
            version = "1",
            candidates = faceIds.map(::FontResolutionCandidate),
            lastResortFace = faceIds.last(),
        )
        val backend = HarfBuzzShapingBackend.open().successValue().also(openedBackends::add)
        val request = ParagraphLayoutRequest(
            snapshot = snapshot,
            sourceRange = snapshot.range,
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(8_100f), LayoutUnit(2_050f)),
                lineMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
            ),
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            featurePolicy = backend.identity.semantic.featurePolicy,
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
            shapingBackend = backend,
            materializationIdentity = ParagraphMaterializationIdentity.LayoutOnly,
            operationProfile = EditorOperationProfile.unbounded,
        )
        return Fixture(snapshot, request, faceIds)
    }

    /**
     * Returns Work Sans bytes whose `fvar` reserved field is zeroed.
     *
     * `FvarReader` requires the reserved field to be 2, so the face exposes a typed
     * `font.variation.invalid-fvar` failure while remaining a valid, parseable SFNT.
     */
    private fun corruptFvar(sourceBytes: ByteArray): ByteArray {
        val bytes = sourceBytes.copyOf()
        val tableCount = uint16(bytes, 4)
        var record = 12
        repeat(tableCount) {
            if (String(bytes, record, 4, Charsets.US_ASCII) == "fvar") {
                val offset = uint32(bytes, record + 8)
                bytes[offset + 6] = 0
                bytes[offset + 7] = 0
                return bytes
            }
            record += 16
        }
        error("The Work Sans fixture has no fvar table to corrupt.")
    }

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun uint32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private companion object {
        const val WORK_SANS: String = "/fonts/worksans/WorkSans[wght].ttf"
        const val DEJAVU: String = "/fonts/dejavu/DejaVuSans.ttf"
        const val LIBERATION: String = "/fonts/liberation/LiberationSans-Regular.ttf"
    }
}
