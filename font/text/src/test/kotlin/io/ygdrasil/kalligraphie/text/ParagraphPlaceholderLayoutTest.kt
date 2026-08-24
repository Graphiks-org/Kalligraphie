package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import io.ygdrasil.kalligraphie.text.paragraph.BasicParagraphLayoutEngine
import io.ygdrasil.kalligraphie.text.paragraph.LineMetrics
import io.ygdrasil.kalligraphie.text.paragraph.ParagraphBuilder
import io.ygdrasil.kalligraphie.text.paragraph.PlaceholderAlignment
import io.ygdrasil.kalligraphie.text.paragraph.PlaceholderBox
import io.ygdrasil.kalligraphie.text.paragraph.PlaceholderStyle
import io.ygdrasil.kalligraphie.text.paragraph.TextStyle
import io.ygdrasil.kalligraphie.text.shaping.GlyphCluster
import io.ygdrasil.kalligraphie.text.shaping.OpenTypeShapingEngine
import io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun
import io.ygdrasil.kalligraphie.text.shaping.ShapingRequest
import io.ygdrasil.kalligraphie.text.shaping.ShapingResult

class ParagraphPlaceholderLayoutTest {
    @Test
    fun placeholderLayoutFixtureMatchesExpected() {
        val engine = BasicParagraphLayoutEngine(RecordingShapingEngine())
        val actual = buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"dumpId\": \"placeholder-layout\",\n")
            append("  \"ownerTickets\": [\"KFONT-M8-006\"],\n")
            append("  \"cases\": [\n")
            placeholderCases().forEachIndexed { index, (caseId, paragraph) ->
                val result = engine.layout(paragraph, maxWidth = 200f)
                append("    ")
                append(result.toPlaceholderCaseDump(caseId))
                if (index != placeholderCases().lastIndex) append(",")
                append("\n")
            }
            append("  ],\n")
            append("  \"nonClaims\": [\n")
            append("    \"no-complete-target-support-claim\",\n")
            append("    \"no-complete-paragraph-layout-claim\",\n")
            append("    \"no-selection-hit-test-claim\",\n")
            append("    \"no-skia-paragraph-parity-claim\"\n")
            append("  ]\n")
            append("}\n")
        }

        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/paragraph/placeholder-layout.json"))

        assertEquals(expected, actual)
    }

    @Test
    fun placeholderLayoutAdjustsLineMetricsForBaselineAlignedPlaceholder() {
        val paragraph = ParagraphBuilder()
            .append("a", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 14f,
                    baselineOffset = 10f,
                    alignment = PlaceholderAlignment.BASELINE,
                ),
            )
            .build()

        val result = BasicParagraphLayoutEngine(RecordingShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(LineMetrics(ascent = -10f, descent = 4f, leading = 0f, width = 22f, baseline = 10f), result.lines.single().metrics)
        assertEquals(
            listOf(
                PlaceholderBox(
                    placeholderId = "ph-000",
                    sourceRange = 1..1,
                    lineIndex = 0,
                    left = 10f,
                    top = 0f,
                    right = 22f,
                    bottom = 14f,
                    baselineOffset = 10f,
                    alignment = "baseline",
                    baseline = "alphabetic",
                    participatesInLineHeight = true,
                ),
            ),
            result.placeholderBoxes,
        )
    }

    @Test
    fun placeholderLayoutDoesNotStretchLineMetricsWhenParticipationIsDisabled() {
        val paragraph = ParagraphBuilder()
            .append("a", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 20f,
                    alignment = PlaceholderAlignment.BELOW_BASELINE,
                    participatesInLineHeight = false,
                ),
            )
            .build()

        val result = BasicParagraphLayoutEngine(RecordingShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(LineMetrics(ascent = -8f, descent = 2f, leading = 0f, width = 22f, baseline = 8f), result.lines.single().metrics)
        assertEquals(8f, result.placeholderBoxes.single().top)
        assertEquals(28f, result.placeholderBoxes.single().bottom)
    }

    @Test
    fun paragraphLayoutDumpIncludesResolvedPlaceholderBoxes() {
        val paragraph = ParagraphBuilder()
            .append("a", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 14f,
                    baselineOffset = 10f,
                    alignment = PlaceholderAlignment.BASELINE,
                ),
            )
            .build()

        val dump = BasicParagraphLayoutEngine(RecordingShapingEngine()).layout(paragraph, maxWidth = 200f).dump()

        assertContains(dump, "\"placeholderBoxes\": [")
        assertContains(dump, "\"placeholderId\": \"ph-000\"")
        assertContains(dump, "\"sourceRange\": \"1..1\"")
        assertContains(dump, "\"left\": 10.0")
        assertContains(dump, "\"top\": 0.0")
        assertContains(dump, "\"right\": 22.0")
        assertContains(dump, "\"bottom\": 14.0")
        assertContains(dump, "\"participatesInLineHeight\": true")
        assertContains(dump, "\"width\": 22.0")
    }

    @Test
    fun placeholderLayoutConsumesMultiCodeUnitShapedPrefixOnlyOnce() {
        val paragraph = ParagraphBuilder()
            .append("fi", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 14f,
                    baselineOffset = 10f,
                    alignment = PlaceholderAlignment.BASELINE,
                ),
            )
            .build()

        val result = BasicParagraphLayoutEngine(LigaturePrefixShapingEngine()).layout(paragraph, maxWidth = 200f)

        assertEquals(15f, result.placeholderBoxes.single().left)
        assertEquals(27f, result.placeholderBoxes.single().right)
    }

    private fun placeholderCases() = listOf(
        "baseline-participates" to ParagraphBuilder()
            .append("a", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 14f,
                    baselineOffset = 10f,
                    alignment = PlaceholderAlignment.BASELINE,
                ),
            )
            .build(),
        "above-baseline" to ParagraphBuilder()
            .append("a", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 8f,
                    height = 6f,
                    alignment = PlaceholderAlignment.ABOVE_BASELINE,
                ),
            )
            .build(),
        "below-baseline" to ParagraphBuilder()
            .append("a", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 8f,
                    height = 8f,
                    alignment = PlaceholderAlignment.BELOW_BASELINE,
                ),
            )
            .build(),
        "middle-non-participating" to ParagraphBuilder()
            .append("a", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 10f,
                    height = 16f,
                    alignment = PlaceholderAlignment.MIDDLE,
                    baseline = null,
                    participatesInLineHeight = false,
                ),
            )
            .build(),
    )

    private fun projectRoot(): Path =
        generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath()) { path -> path.parent }
            .first { path -> Files.exists(path.resolve(".git")) }
}

private fun io.ygdrasil.kalligraphie.text.paragraph.ParagraphLayoutResult.toPlaceholderCaseDump(caseId: String): String = buildString {
    append("{\"caseId\": ")
        .append(jsonString(caseId))
        .append(", \"inputHash\": ")
        .append(jsonString(paragraph.inputHash))
        .append(", \"lineMetrics\": ")
        .append(lines.joinToString(prefix = "[", postfix = "]") { line ->
            "{\"ascent\": ${line.metrics.ascent}, \"descent\": ${line.metrics.descent}, \"leading\": ${line.metrics.leading}, \"width\": ${line.metrics.width}, \"baseline\": ${line.metrics.baseline}}"
        })
        .append(", \"placeholderBoxes\": ")
        .append(placeholderBoxes.joinToString(prefix = "[", postfix = "]") { box ->
            "{\"placeholderId\": ${jsonString(box.placeholderId)}, \"sourceRange\": ${jsonString("${box.sourceRange.first}..${box.sourceRange.last}")}, \"lineIndex\": ${box.lineIndex}, \"left\": ${box.left}, \"top\": ${box.top}, \"right\": ${box.right}, \"bottom\": ${box.bottom}, \"baselineOffset\": ${box.baselineOffset}, \"alignment\": ${jsonString(box.alignment)}, \"baseline\": ${box.baseline?.let(::jsonString) ?: "null"}, \"participatesInLineHeight\": ${box.participatesInLineHeight}}"
        })
        .append(", \"diagnostics\": ")
        .append(diagnostics.joinToString(prefix = "[", postfix = "]") { diagnostic ->
            "{\"code\": ${jsonString(diagnostic.code)}, \"severity\": ${jsonString(diagnostic.severity)}, \"textRange\": ${diagnostic.textRange?.let { jsonString("${it.first}..${it.last}") } ?: "null"}}"
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

private class RecordingShapingEngine : OpenTypeShapingEngine {
    override fun shape(request: ShapingRequest): ShapingResult {
        val clusters = request.textRange.mapIndexed { glyphIndex, textIndex ->
            GlyphCluster(
                textRange = textIndex..textIndex,
                glyphRange = glyphIndex..glyphIndex,
                advanceX = request.fontSize,
            )
        }
        return ShapingResult(
            glyphRuns = listOf(
                ShapedGlyphRun(
                    glyphIds = clusters.indices.toList(),
                    clusters = clusters,
                    advanceX = clusters.sumOf { it.advanceX.toDouble() }.toFloat(),
                    typefaceId = request.typefaceId,
                    fontSize = request.fontSize,
                ),
            ),
            diagnostics = emptyList(),
        )
    }
}

private class LigaturePrefixShapingEngine : OpenTypeShapingEngine {
    override fun shape(request: ShapingRequest): ShapingResult {
        val glyphRun = when (request.text.substring(request.textRange.first, request.textRange.last + 1)) {
            "fi" -> ShapedGlyphRun(
                glyphIds = listOf(42),
                clusters = listOf(
                    GlyphCluster(
                        textRange = request.textRange.first..request.textRange.last,
                        glyphRange = 0..0,
                        advanceX = 15f,
                    ),
                ),
                advanceX = 15f,
                typefaceId = request.typefaceId,
                fontSize = request.fontSize,
            )
            else -> {
                val clusters = request.textRange.mapIndexed { glyphIndex, textIndex ->
                    GlyphCluster(
                        textRange = textIndex..textIndex,
                        glyphRange = glyphIndex..glyphIndex,
                        advanceX = request.fontSize,
                    )
                }
                ShapedGlyphRun(
                    glyphIds = clusters.indices.toList(),
                    clusters = clusters,
                    advanceX = clusters.sumOf { it.advanceX.toDouble() }.toFloat(),
                    typefaceId = request.typefaceId,
                    fontSize = request.fontSize,
                )
            }
        }
        return ShapingResult(
            glyphRuns = listOf(glyphRun),
            diagnostics = emptyList(),
        )
    }
}
