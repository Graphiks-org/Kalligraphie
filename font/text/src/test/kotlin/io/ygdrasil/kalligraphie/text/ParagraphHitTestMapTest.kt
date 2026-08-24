package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ygdrasil.kalligraphie.text.paragraph.BasicParagraphLayoutEngine
import io.ygdrasil.kalligraphie.text.paragraph.ParagraphBuilder
import io.ygdrasil.kalligraphie.text.paragraph.ParagraphStyle
import io.ygdrasil.kalligraphie.text.paragraph.PlaceholderAlignment
import io.ygdrasil.kalligraphie.text.paragraph.PlaceholderStyle
import io.ygdrasil.kalligraphie.text.paragraph.SelectionBox
import io.ygdrasil.kalligraphie.text.paragraph.SelectionQueryResult
import io.ygdrasil.kalligraphie.text.paragraph.SelectionRange
import io.ygdrasil.kalligraphie.text.paragraph.TextPosition
import io.ygdrasil.kalligraphie.text.paragraph.TextDirection
import io.ygdrasil.kalligraphie.text.paragraph.TextStyle
import io.ygdrasil.kalligraphie.text.shaping.GlyphCluster
import io.ygdrasil.kalligraphie.text.shaping.OpenTypeShapingEngine
import io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun
import io.ygdrasil.kalligraphie.text.shaping.ShapingRequest
import io.ygdrasil.kalligraphie.text.shaping.ShapingResult

class ParagraphHitTestMapTest {
    @Test
    fun selectionAndHitTestFixtureMatchesExpected() {
        val engine = BasicParagraphLayoutEngine(ClusterAwareShapingEngine())
        val actual = buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"dumpId\": \"hit-test-map\",\n")
            append("  \"ownerTickets\": [\"KFONT-M8-005\"],\n")
            append("  \"cases\": [\n")
            hitTestCases().forEachIndexed { index, (caseId, paragraph, selection, probes) ->
                val result = engine.layout(paragraph, maxWidth = 200f)
                append("    ")
                append(result.toHitTestCaseDump(caseId, selection, probes))
                if (index != hitTestCases().lastIndex) append(",")
                append("\n")
            }
            append("  ],\n")
            append("  \"nonClaims\": [\n")
            append("    \"no-complete-target-support-claim\",\n")
            append("    \"no-complete-paragraph-layout-claim\",\n")
            append("    \"no-complete-bidi-visual-ordering-claim\",\n")
            append("    \"no-skia-paragraph-parity-claim\"\n")
            append("  ]\n")
            append("}\n")
        }

        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/paragraph/hit-test-map.json"))

        assertEquals(expected, actual)
    }

    @Test
    fun selectionBoxesCrossLinesAndExposePlaceholderIds() {
        val paragraph = ParagraphBuilder()
            .append("a\n", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 14f,
                    baselineOffset = 10f,
                    alignment = PlaceholderAlignment.BASELINE,
                ),
            )
            .append("b", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)
        val selection = result.selectionBoxes(
            SelectionRange(
                start = TextPosition(offset = 0),
                end = TextPosition(offset = 4),
            ),
        )

        assertEquals(
            SelectionQueryResult(
                boxes = listOf(
                    SelectionBox(
                        sourceRange = 0..0,
                        lineIndex = 0,
                        left = 0f,
                        top = 0f,
                        right = 10f,
                        bottom = 10f,
                    ),
                    SelectionBox(
                        sourceRange = 2..2,
                        lineIndex = 1,
                        left = 0f,
                        top = 10f,
                        right = 12f,
                        bottom = 24f,
                        placeholderId = "ph-000",
                    ),
                    SelectionBox(
                        sourceRange = 3..3,
                        lineIndex = 1,
                        left = 12f,
                        top = 10f,
                        right = 22f,
                        bottom = 24f,
                    ),
                ),
            ),
            selection,
        )
    }

    @Test
    fun hitTestSnapsToClusterAndPlaceholderBoundaries() {
        val paragraph = ParagraphBuilder()
            .append("a\u0301", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 14f,
                    baselineOffset = 10f,
                    alignment = PlaceholderAlignment.BASELINE,
                ),
            )
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(
            TextPosition(offset = 2, affinity = "upstream"),
            result.hitTest(pointX = 8f, pointY = 5f).entry?.position,
        )
        assertEquals(
            TextPosition(offset = 2, affinity = "downstream"),
            result.hitTest(pointX = 11f, pointY = 1f).entry?.position,
        )
        assertEquals("ph-000", result.hitTest(pointX = 11f, pointY = 1f).entry?.placeholderId)
    }

    @Test
    fun hitTestSnapsToEmojiClusterBoundaries() {
        val paragraph = ParagraphBuilder()
            .append("a\uD83D\uDE00", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(
            TextPosition(offset = 1, affinity = "downstream"),
            result.hitTest(pointX = 12f, pointY = 5f).entry?.position,
        )
        assertEquals(
            TextPosition(offset = 3, affinity = "upstream"),
            result.hitTest(pointX = 18f, pointY = 5f).entry?.position,
        )
    }

    @Test
    fun hitTestClampsFiniteOutOfBoundsPointsToNearestCaretStop() {
        val paragraph = ParagraphBuilder()
            .append("ab", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(
            TextPosition(offset = 0, affinity = "downstream"),
            result.hitTest(pointX = -5f, pointY = 5f).entry?.position,
        )
        assertEquals(false, result.hitTest(pointX = -5f, pointY = 5f).entry?.isInsideText)
        assertEquals(
            TextPosition(offset = 2, affinity = "upstream"),
            result.hitTest(pointX = 25f, pointY = 5f).entry?.position,
        )
        assertEquals(false, result.hitTest(pointX = 25f, pointY = 5f).entry?.isInsideText)
    }

    @Test
    fun hitTestUsesOverflowingPlaceholderBoundsWhenPlaceholderDoesNotParticipateInLineHeight() {
        val paragraph = ParagraphBuilder()
            .append("a", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 20f,
                    baselineOffset = 2f,
                    alignment = PlaceholderAlignment.BELOW_BASELINE,
                    participatesInLineHeight = false,
                ),
            )
            .append("\nb", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)
        val hit = result.hitTest(pointX = 11f, pointY = 15f).entry

        assertEquals(0, hit?.lineIndex)
        assertEquals(TextPosition(offset = 1, affinity = "downstream"), hit?.position)
        assertEquals("ph-000", hit?.placeholderId)
        assertEquals(true, hit?.isInsideText)
    }

    @Test
    fun hitTestRespectsVisualOrderForMixedDirectionLine() {
        val paragraph = ParagraphBuilder()
            .append("ab \u05D0\u05D1", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(
            TextPosition(offset = 5, affinity = "upstream"),
            result.hitTest(pointX = 32f, pointY = 5f).entry?.position,
        )
        assertEquals(
            TextPosition(offset = 4, affinity = "downstream"),
            result.hitTest(pointX = 38f, pointY = 5f).entry?.position,
        )
        assertEquals(
            TextPosition(offset = 4, affinity = "upstream"),
            result.hitTest(pointX = 42f, pointY = 5f).entry?.position,
        )
        assertEquals(
            TextPosition(offset = 3, affinity = "downstream"),
            result.hitTest(pointX = 48f, pointY = 5f).entry?.position,
        )
    }

    @Test
    fun selectionBoxesKeepPlaceholderInVisualOrderForMixedDirectionLine() {
        val paragraph = ParagraphBuilder()
            .append("ab \u05D0", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 14f,
                    baselineOffset = 10f,
                    alignment = PlaceholderAlignment.BASELINE,
                ),
            )
            .append("\u05D1", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(
            SelectionQueryResult(
                boxes = listOf(
                    SelectionBox(
                        sourceRange = 0..2,
                        lineIndex = 0,
                        left = 0f,
                        top = 0f,
                        right = 30f,
                        bottom = 14f,
                    ),
                    SelectionBox(
                        sourceRange = 5..5,
                        lineIndex = 0,
                        left = 30f,
                        top = 0f,
                        right = 40f,
                        bottom = 14f,
                        direction = -1,
                    ),
                    SelectionBox(
                        sourceRange = 4..4,
                        lineIndex = 0,
                        left = 40f,
                        top = 0f,
                        right = 52f,
                        bottom = 14f,
                        direction = -1,
                        placeholderId = "ph-000",
                    ),
                    SelectionBox(
                        sourceRange = 3..3,
                        lineIndex = 0,
                        left = 52f,
                        top = 0f,
                        right = 62f,
                        bottom = 14f,
                        direction = -1,
                    ),
                ),
            ),
            result.selectionBoxes(
                SelectionRange(
                    start = TextPosition(offset = 0),
                    end = TextPosition(offset = 6),
                ),
            ),
        )
        assertEquals(TextPosition(offset = 6, affinity = "upstream"), result.hitTest(pointX = 32f, pointY = 5f).entry?.position)
        assertEquals("ph-000", result.hitTest(pointX = 45f, pointY = 5f).entry?.placeholderId)
        assertEquals(TextPosition(offset = 5, affinity = "upstream"), result.hitTest(pointX = 45f, pointY = 5f).entry?.position)
        assertEquals(TextPosition(offset = 4, affinity = "upstream"), result.hitTest(pointX = 55f, pointY = 5f).entry?.position)
    }

    @Test
    fun hitTestRespectsVisualOrderForRtlParagraphWithLtrIsland() {
        val paragraph = ParagraphBuilder(
            ParagraphStyle(textDirection = TextDirection.RIGHT_TO_LEFT),
        )
            .append("\u05D0\u05D1 ab", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(
            TextPosition(offset = 3, affinity = "downstream"),
            result.hitTest(pointX = 2f, pointY = 5f).entry?.position,
        )
        assertEquals(
            TextPosition(offset = 4, affinity = "downstream"),
            result.hitTest(pointX = 12f, pointY = 5f).entry?.position,
        )
        assertEquals(
            TextPosition(offset = 2, affinity = "upstream"),
            result.hitTest(pointX = 32f, pointY = 5f).entry?.position,
        )
        assertEquals(
            TextPosition(offset = 1, affinity = "upstream"),
            result.hitTest(pointX = 42f, pointY = 5f).entry?.position,
        )
    }

    @Test
    fun selectionBoxesKeepPlaceholderInsideRtlParagraphLtrIsland() {
        val paragraph = ParagraphBuilder(
            ParagraphStyle(textDirection = TextDirection.RIGHT_TO_LEFT),
        )
            .append("\u05D0 ", TextStyle(fontSize = 10f))
            .append("a", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 14f,
                    baselineOffset = 10f,
                    alignment = PlaceholderAlignment.BASELINE,
                ),
            )
            .append("b", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(
            SelectionQueryResult(
                boxes = listOf(
                    SelectionBox(
                        sourceRange = 2..2,
                        lineIndex = 0,
                        left = 0f,
                        top = 0f,
                        right = 10f,
                        bottom = 14f,
                    ),
                    SelectionBox(
                        sourceRange = 3..3,
                        lineIndex = 0,
                        left = 10f,
                        top = 0f,
                        right = 22f,
                        bottom = 14f,
                        placeholderId = "ph-000",
                    ),
                    SelectionBox(
                        sourceRange = 4..4,
                        lineIndex = 0,
                        left = 22f,
                        top = 0f,
                        right = 32f,
                        bottom = 14f,
                    ),
                    SelectionBox(
                        sourceRange = 0..1,
                        lineIndex = 0,
                        left = 32f,
                        top = 0f,
                        right = 52f,
                        bottom = 14f,
                        direction = -1,
                    ),
                ),
            ),
            result.selectionBoxes(
                SelectionRange(
                    start = TextPosition(offset = 0),
                    end = TextPosition(offset = 5),
                ),
            ),
        )
        assertEquals("ph-000", result.hitTest(pointX = 12f, pointY = 5f).entry?.placeholderId)
        assertEquals(TextPosition(offset = 3, affinity = "downstream"), result.hitTest(pointX = 12f, pointY = 5f).entry?.position)
        assertEquals(TextPosition(offset = 4, affinity = "downstream"), result.hitTest(pointX = 25f, pointY = 5f).entry?.position)
        assertEquals(TextPosition(offset = 1, affinity = "upstream"), result.hitTest(pointX = 45f, pointY = 5f).entry?.position)
    }

    @Test
    fun selectionBoxesDoNotLeakBidiDirectionAcrossHardBreaks() {
        val paragraph = ParagraphBuilder()
            .append("\u05D0\u05D1\n", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 14f,
                    baselineOffset = 10f,
                    alignment = PlaceholderAlignment.BASELINE,
                ),
            )
            .append("c", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)
        val secondLineBoxes = result.selectionBoxes(
            SelectionRange(
                start = TextPosition(offset = 3),
                end = TextPosition(offset = 5),
            ),
        )

        assertEquals(
            listOf(
                SelectionBox(
                    sourceRange = 3..3,
                    lineIndex = 1,
                    left = 0f,
                    top = 10f,
                    right = 12f,
                    bottom = 24f,
                    placeholderId = "ph-000",
                ),
                SelectionBox(
                    sourceRange = 4..4,
                    lineIndex = 1,
                    left = 12f,
                    top = 10f,
                    right = 22f,
                    bottom = 24f,
                ),
            ),
            secondLineBoxes.boxes,
        )
        assertEquals("ph-000", result.hitTest(pointX = 2f, pointY = 15f).entry?.placeholderId)
        assertEquals(TextPosition(offset = 3, affinity = "downstream"), result.hitTest(pointX = 2f, pointY = 15f).entry?.position)
    }

    @Test
    fun softWrappedLeadingPlaceholderKeepsParagraphWideVisualOrderBeforeRtlContinuation() {
        val paragraph = ParagraphBuilder()
            .append("abc", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 14f,
                    baselineOffset = 10f,
                    alignment = PlaceholderAlignment.BASELINE,
                ),
            )
            .append("\u05D0\u05D1", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 32f)
        val secondLineBoxes = result.selectionBoxes(
            SelectionRange(
                start = TextPosition(offset = 3),
                end = TextPosition(offset = 6),
            ),
        )

        assertEquals(
            listOf(
                SelectionBox(
                    sourceRange = 3..3,
                    lineIndex = 1,
                    left = 0f,
                    top = 10f,
                    right = 12f,
                    bottom = 24f,
                    placeholderId = "ph-000",
                ),
                SelectionBox(
                    sourceRange = 4..5,
                    lineIndex = 1,
                    left = 12f,
                    top = 10f,
                    right = 32f,
                    bottom = 24f,
                    direction = -1,
                ),
            ),
            secondLineBoxes.boxes,
        )
        assertEquals(
            TextPosition(offset = 3, affinity = "downstream"),
            result.hitTest(pointX = 2f, pointY = 15f).entry?.position,
        )
        assertEquals("ph-000", result.hitTest(pointX = 2f, pointY = 15f).entry?.placeholderId)
        assertEquals(
            TextPosition(offset = 6, affinity = "upstream"),
            result.hitTest(pointX = 14f, pointY = 15f).entry?.position,
        )
    }

    @Test
    fun leadingPlaceholderKeepsBaseDirectionBeforeRtlRun() {
        val paragraph = ParagraphBuilder()
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 14f,
                    baselineOffset = 10f,
                    alignment = PlaceholderAlignment.BASELINE,
                ),
            )
            .append("\u05D0\u05D1", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(
            SelectionQueryResult(
                boxes = listOf(
                    SelectionBox(
                        sourceRange = 0..0,
                        lineIndex = 0,
                        left = 0f,
                        top = 0f,
                        right = 12f,
                        bottom = 14f,
                        placeholderId = "ph-000",
                    ),
                    SelectionBox(
                        sourceRange = 1..2,
                        lineIndex = 0,
                        left = 12f,
                        top = 0f,
                        right = 32f,
                        bottom = 14f,
                        direction = -1,
                    ),
                ),
            ),
            result.selectionBoxes(
                SelectionRange(
                    start = TextPosition(offset = 0),
                    end = TextPosition(offset = 3),
                ),
            ),
        )
        assertEquals("ph-000", result.hitTest(pointX = 5f, pointY = 5f).entry?.placeholderId)
        assertEquals(TextPosition(offset = 0, affinity = "downstream"), result.hitTest(pointX = 5f, pointY = 5f).entry?.position)
        assertEquals(TextPosition(offset = 3, affinity = "upstream"), result.hitTest(pointX = 15f, pointY = 5f).entry?.position)
    }

    @Test
    fun selectionAndHitTestRejectInvalidInputsDeterministically() {
        val paragraph = ParagraphBuilder()
            .append("ab", TextStyle(fontSize = 10f))
            .build()

        val result = BasicParagraphLayoutEngine(ClusterAwareShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(
            listOf("text.paragraph.invalid-selection-range"),
            result.selectionBoxes(
                SelectionRange(
                    start = TextPosition(offset = 3),
                    end = TextPosition(offset = 5),
                ),
            ).diagnostics.map { it.code },
        )
        assertEquals(
            listOf("text.paragraph.hit-test-point-non-finite"),
            result.hitTest(pointX = Float.NaN, pointY = 0f).diagnostics.map { it.code },
        )
    }

    private fun hitTestCases() = listOf(
        HitTestCase(
            caseId = "multiline-placeholder-selection",
            paragraph = ParagraphBuilder()
                .append("a\n", TextStyle(fontSize = 10f))
                .appendPlaceholder(
                    PlaceholderStyle(
                        width = 12f,
                        height = 14f,
                        baselineOffset = 10f,
                        alignment = PlaceholderAlignment.BASELINE,
                    ),
                )
                .append("b", TextStyle(fontSize = 10f))
                .build(),
            selection = SelectionRange(
                start = TextPosition(offset = 0),
                end = TextPosition(offset = 4),
            ),
            probes = listOf(1f to 1f, 1f to 12f, 13f to 12f),
        ),
        HitTestCase(
            caseId = "combining-mark-and-placeholder",
            paragraph = ParagraphBuilder()
                .append("a\u0301", TextStyle(fontSize = 10f))
                .appendPlaceholder(
                    PlaceholderStyle(
                        width = 12f,
                        height = 14f,
                        baselineOffset = 10f,
                        alignment = PlaceholderAlignment.BASELINE,
                    ),
                )
                .build(),
            selection = SelectionRange(
                start = TextPosition(offset = 0),
                end = TextPosition(offset = 3),
            ),
            probes = listOf(2f to 5f, 8f to 5f, 11f to 1f, 21f to 1f),
        ),
        HitTestCase(
            caseId = "emoji-cluster-boundaries",
            paragraph = ParagraphBuilder()
                .append("a\uD83D\uDE00", TextStyle(fontSize = 10f))
                .build(),
            selection = SelectionRange(
                start = TextPosition(offset = 0),
                end = TextPosition(offset = 3),
            ),
            probes = listOf(-5f to 5f, 12f to 5f, 18f to 5f, 25f to 5f),
        ),
        HitTestCase(
            caseId = "mixed-bidi-visual-order",
            paragraph = ParagraphBuilder()
                .append("ab \u05D0\u05D1", TextStyle(fontSize = 10f))
                .build(),
            selection = SelectionRange(
                start = TextPosition(offset = 0),
                end = TextPosition(offset = 5),
            ),
            probes = listOf(32f to 5f, 42f to 5f, 48f to 5f),
        ),
        HitTestCase(
            caseId = "non-participating-placeholder-overflow",
            paragraph = ParagraphBuilder()
                .append("a", TextStyle(fontSize = 10f))
                .appendPlaceholder(
                    PlaceholderStyle(
                        width = 12f,
                        height = 20f,
                        baselineOffset = 2f,
                        alignment = PlaceholderAlignment.BELOW_BASELINE,
                        participatesInLineHeight = false,
                    ),
                )
                .append("\nb", TextStyle(fontSize = 10f))
                .build(),
            selection = SelectionRange(
                start = TextPosition(offset = 0),
                end = TextPosition(offset = 4),
            ),
            probes = listOf(11f to 15f, 21f to 15f, 1f to 15f),
        ),
    )

    private fun projectRoot(): Path =
        generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath()) { path -> path.parent }
            .first { path -> Files.exists(path.resolve(".git")) }
}

private data class HitTestCase(
    val caseId: String,
    val paragraph: io.ygdrasil.kalligraphie.text.paragraph.Paragraph,
    val selection: SelectionRange,
    val probes: List<Pair<Float, Float>>,
)

private fun io.ygdrasil.kalligraphie.text.paragraph.ParagraphLayoutResult.toHitTestCaseDump(
    caseId: String,
    selection: SelectionRange,
    probes: List<Pair<Float, Float>>,
): String = buildString {
    val hitTestMap = hitTestMap()
    val selectionResult = selectionBoxes(selection)
    append("{\"caseId\": ")
        .append(jsonString(caseId))
        .append(", \"inputHash\": ")
        .append(jsonString(paragraph.inputHash))
        .append(", \"caretStops\": ")
        .append(hitTestMap.caretStops.joinToString(prefix = "[", postfix = "]") { stop ->
            "{\"offset\": ${stop.position.offset}, \"affinity\": ${jsonString(stop.position.affinity)}, \"lineIndex\": ${stop.lineIndex}, \"x\": ${stop.x}, \"top\": ${stop.top}, \"bottom\": ${stop.bottom}, \"placeholderId\": ${stop.placeholderId?.let(::jsonString) ?: "null"}}"
        })
        .append(", \"selectionBoxes\": ")
        .append(selectionResult.boxes.joinToString(prefix = "[", postfix = "]") { box ->
            "{\"sourceRange\": ${jsonString("${box.sourceRange.first}..${box.sourceRange.last}")}, \"lineIndex\": ${box.lineIndex}, \"left\": ${box.left}, \"top\": ${box.top}, \"right\": ${box.right}, \"bottom\": ${box.bottom}, \"placeholderId\": ${box.placeholderId?.let(::jsonString) ?: "null"}}"
        })
        .append(", \"hitEntries\": ")
        .append(probes.joinToString(prefix = "[", postfix = "]") { (x, y) ->
            val hit = hitTest(x, y)
            val entry = hit.entry
            "{\"point\": [${x}, ${y}], \"offset\": ${entry?.position?.offset ?: -1}, \"affinity\": ${entry?.position?.affinity?.let(::jsonString) ?: "null"}, \"lineIndex\": ${entry?.lineIndex ?: -1}, \"clusterRange\": ${entry?.clusterRange?.let { jsonString("${it.first}..${it.last}") } ?: "null"}, \"placeholderId\": ${entry?.placeholderId?.let(::jsonString) ?: "null"}, \"isInsideText\": ${entry?.isInsideText ?: false}, \"diagnostics\": ${hit.diagnostics.joinToString(prefix = "[", postfix = "]") { diagnostic -> jsonString(diagnostic.code) }}}"
        })
        .append(", \"diagnostics\": ")
        .append(hitTestMap.diagnostics.joinToString(prefix = "[", postfix = "]") { diagnostic ->
            jsonString(diagnostic.code)
        })
        .append("}")
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            else -> append(char)
        }
    }
    append('"')
}

private class ClusterAwareShapingEngine : OpenTypeShapingEngine {
    override fun shape(request: ShapingRequest): ShapingResult {
        val clusters = mutableListOf<GlyphCluster>()
        var glyphIndex = 0
        var index = request.textRange.first
        while (index <= request.textRange.last) {
            val cluster = request.text.clusterRangeAt(index).coerceAtMost(request.textRange.last)
            clusters += GlyphCluster(
                textRange = index..cluster,
                glyphRange = glyphIndex..glyphIndex,
                advanceX = request.fontSize,
            )
            glyphIndex += 1
            index = cluster + 1
        }
        return ShapingResult(
            glyphRuns = listOf(
                ShapedGlyphRun(
                    glyphIds = clusters.indices.toList(),
                    clusters = clusters,
                    advanceX = clusters.sumOf { it.advanceX.toDouble() }.toFloat(),
                    bidiLevel = request.textRange.bidiLevelIn(request.text),
                    typefaceId = request.typefaceId,
                    fontSize = request.fontSize,
                ),
            ),
            diagnostics = emptyList(),
        )
    }
}

private fun String.clusterRangeAt(index: Int): Int {
    val firstCodePointEnd = codePointEndAt(index)
    var clusterEnd = firstCodePointEnd
    var nextIndex = clusterEnd + 1
    while (nextIndex < length) {
        val nextCodePoint = codePointAt(nextIndex)
        if (!nextCodePoint.isCombiningMarkOrDefaultIgnorable()) break
        clusterEnd = codePointEndAt(nextIndex)
        nextIndex = clusterEnd + 1
    }
    return clusterEnd
}

private fun String.codePointAt(index: Int): Int {
    val high = this[index]
    return if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        Character.toCodePoint(high, this[index + 1])
    } else {
        high.code
    }
}

private fun String.codePointEndAt(index: Int): Int =
    if (this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) index + 1 else index

private fun IntRange.bidiLevelIn(text: String): Int {
    var index = first
    while (index <= last) {
        val codePoint = text.codePointAt(index)
        when (Character.getDirectionality(codePoint).toInt()) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt(),
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt(),
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING.toInt(),
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE.toInt(),
            -> return 1
        }
        index = text.codePointEndAt(index) + 1
    }
    return 0
}

private fun Int.isCombiningMarkOrDefaultIgnorable(): Boolean =
    this in 0x0300..0x036F ||
        this in 0x1AB0..0x1AFF ||
        this in 0x1DC0..0x1DFF ||
        this in 0x20D0..0x20FF ||
        this in 0xFE20..0xFE2F ||
        this == 0x200C ||
        this == 0x200D ||
        this in 0xFE00..0xFE0F ||
        this in 0xE0100..0xE01EF
