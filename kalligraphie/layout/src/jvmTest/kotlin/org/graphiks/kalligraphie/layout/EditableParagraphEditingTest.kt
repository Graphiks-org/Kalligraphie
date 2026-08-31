package org.graphiks.kalligraphie.layout

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CaretPosition
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.LogicalNavigationDirection
import org.graphiks.kalligraphie.api.ParagraphLayout
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.VisualNavigationDirection
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalog
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalogEntry
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmLineBreakAnalyzer
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.unicode.TextSnapshots

class EditableParagraphEditingTest {
    private val openedBackends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        openedBackends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun navigationCrossesLinesAndKeepsParagraphCoordinates() {
        val fixture = fixture("one two three", width = 3_000f, height = 4_000f)
        val layout = layout(fixture)
        val first = layout.lines[0]
        val second = layout.lines[1]
        val endOfFirst = first.allCaretCandidates.single { it.position.index == first.range.endExclusive }
        val startOfSecond = second.allCaretCandidates.single { it.position.index == second.range.start }

        assertEquals(
            second.allCaretCandidates.single { it.position.index == fixture.snapshot.textIndexAtScalarBoundary(5) }.position,
            layout.nextLogical(endOfFirst.position, LogicalNavigationDirection.FORWARD),
        )
        assertSame(startOfSecond, layout.nextVisual(endOfFirst, VisualNavigationDirection.FORWARD))
        assertEquals(second.lineBox.top, startOfSecond.geometry.start.y)
        assertEquals(second.lineBox.bottom, startOfSecond.geometry.end.y)
    }

    @Test
    fun selectionReturnsOnlyBidiRunFragmentsAndHitTestingChoosesTheDeterministicLine() {
        val fixture = fixture(
            "abc \u05D0\u05D1\u05D2   \u05E9\u05DC\u05D5\u05DD",
            width = 4_500f,
            height = 3_000f,
            language = "he",
            fontResource = "/fonts/liberation/LiberationSans-Regular.ttf",
            fontName = "Liberation Sans Regular",
        )
        val layout = layout(fixture)
        val first = layout.lines[0]
        val second = layout.lines[1]
        val anchor = first.allCaretCandidates.single { it.position.index == first.range.start }.position
        val focus = second.allCaretCandidates.single { it.position.index == second.range.endExclusive }.position
        val fragments = layout.selectionGeometry(anchor, focus)
        val firstStart = first.allCaretCandidates.first()
        val secondEnd = second.allCaretCandidates.last()

        assertEquals(4, fragments.size)
        assertEquals(listOf(first.lineBox.top, first.lineBox.top, first.lineBox.top, second.lineBox.top), fragments.map { it.top })
        assertTrue(fragments.none { rectangle ->
            rectangle.left == first.lineBox.left && rectangle.right == first.lineBox.right
        })
        assertSame(firstStart, layout.hitTest(LayoutPoint(firstStart.geometry.start.x, LayoutUnit(0f))))
        assertSame(secondEnd, layout.hitTest(LayoutPoint(secondEnd.geometry.start.x, LayoutUnit(4_000f))))
        assertSame(
            firstStart,
            layout.hitTest(LayoutPoint(firstStart.geometry.start.x, first.lineBox.bottom)),
        )
    }

    private fun layout(fixture: Fixture): ParagraphLayout = assertIs<ParagraphLayoutResult.Success>(
        ParagraphComposer.layout(fixture.request, EditableLineMaterialization.LayoutOnly),
    ).layout

    private fun fixture(
        value: String,
        width: Float,
        height: Float,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        language: String = "en",
        fontResource: String = "/fonts/dejavu/DejaVuSans.ttf",
        fontName: String = "DejaVu Sans",
    ): Fixture {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        val unicodeAnalysis = JvmUnicodeAnalyzer.create().analyze(snapshot, UnicodeAnalysisRequest(baseDirection, language))
        val lineBreakAnalysis = JvmLineBreakAnalyzer.create().analyze(snapshot, unicodeAnalysis)
        val source = FontSource(
            checkNotNull(javaClass.getResourceAsStream(fontResource)).use { it.readBytes() },
            FontSourceProvenance(fontName),
        )
        val generation = FontCatalogGeneration("paragraph-editing-test-v1")
        val catalog = EmbeddedFontCatalog(
            generation,
            listOf(EmbeddedFontCatalogEntry(source, SfntReader.readMetadata(source).successValue())),
        )
        val face = FontFaceId(source.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "paragraph-editing-test-policy",
            version = "1",
            candidates = listOf(FontResolutionCandidate(face)),
            lastResortFace = face,
        )
        val backend = JvmHarfBuzzShapingBackend.open().successValue().also(openedBackends::add)
        val request = ParagraphLayoutRequest(
            snapshot = snapshot,
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(100f + width), LayoutUnit(50f + height)),
                lineMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
            ),
            baseDirection = baseDirection,
            language = language,
            featurePolicy = backend.identity.featurePolicy,
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
            shapingBackend = backend,
            materializationIdentity = ParagraphMaterializationIdentity.LayoutOnly,
        )
        return Fixture(snapshot, request)
    }

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value

    private data class Fixture(val snapshot: TextSnapshot, val request: ParagraphLayoutRequest)
}
