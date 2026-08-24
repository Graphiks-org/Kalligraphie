package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ygdrasil.kalligraphie.text.paragraph.BasicParagraphLayoutEngine
import io.ygdrasil.kalligraphie.text.paragraph.PARAGRAPH_LAYOUT_ELLIPSIS_GLYPH_MISSING_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.paragraph.ParagraphBuilder
import io.ygdrasil.kalligraphie.text.paragraph.ParagraphStyle
import io.ygdrasil.kalligraphie.text.paragraph.PlaceholderAlignment
import io.ygdrasil.kalligraphie.text.paragraph.PlaceholderStyle
import io.ygdrasil.kalligraphie.text.paragraph.TextDirection
import io.ygdrasil.kalligraphie.text.paragraph.TextStyle
import io.ygdrasil.kalligraphie.text.shaping.GlyphCluster
import io.ygdrasil.kalligraphie.text.shaping.OpenTypeShapingEngine
import io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun
import io.ygdrasil.kalligraphie.text.shaping.ShapingRequest
import io.ygdrasil.kalligraphie.text.shaping.ShapingResult

class ParagraphEllipsisLayoutTest {
    @Test
    fun paragraphLayoutFixtureMatchesExpected() {
        val actual = buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"dumpId\": \"paragraph-layout\",\n")
            append("  \"ownerTickets\": [\"KFONT-M8-004\"],\n")
            append("  \"cases\": [\n")
            ellipsisCases().forEachIndexed { index, (caseId, result) ->
                append("    ")
                append(result.toEllipsisCaseDump(caseId))
                if (index != ellipsisCases().lastIndex) append(",")
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

        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/paragraph/paragraph-layout.json"))

        assertEquals(expected, actual)
    }

    @Test
    fun ellipsizedMultiLineRunReindexesTrimmedGlyphClusters() {
        val result = BasicParagraphLayoutEngine(MultiGlyphClusterEllipsisShapingEngine()).layout(
            ParagraphBuilder(ParagraphStyle(maxLines = 2, ellipsis = "..."))
                .append("aa bb cc dd ee", TextStyle(fontSize = 10f))
                .build(),
            maxWidth = 50f,
        )

        val ellipsizedLine = result.lines.single { line -> line.isEllipsized }
        val trimmedRun = ellipsizedLine.glyphRuns.single()

        assertEquals(
            listOf(0..1, 2..3),
            trimmedRun.clusters.map { cluster -> cluster.glyphRange },
        )
    }

    @Test
    fun placeholderConflictDoesNotMaskMissingEllipsisGlyphDiagnostic() {
        val result = BasicParagraphLayoutEngine(MissingEllipsisGlyphShapingEngine()).layout(
            ParagraphBuilder(ParagraphStyle(maxLines = 1, ellipsis = "..."))
                .append("a", TextStyle(fontSize = 10f))
                .appendPlaceholder(
                    PlaceholderStyle(
                        width = 12f,
                        height = 12f,
                        baselineOffset = 10f,
                        alignment = PlaceholderAlignment.BASELINE,
                    ),
                )
                .append("\nb", TextStyle(fontSize = 10f))
                .build(),
            maxWidth = 24f,
        )

        assertEquals(
            listOf(PARAGRAPH_LAYOUT_ELLIPSIS_GLYPH_MISSING_DIAGNOSTIC_CODE),
            result.diagnostics.map { diagnostic -> diagnostic.code },
        )
    }

    private fun ellipsisCases() = listOf(
        "simple-one-line-overflow" to BasicParagraphLayoutEngine(RecordingEllipsisShapingEngine()).layout(
            ParagraphBuilder(ParagraphStyle(maxLines = 1, ellipsis = "..."))
                .append("aa bb c", TextStyle(fontSize = 10f))
                .build(),
            maxWidth = 50f,
        ),
        "multi-line-overflow" to BasicParagraphLayoutEngine(RecordingEllipsisShapingEngine()).layout(
            ParagraphBuilder(ParagraphStyle(maxLines = 2, ellipsis = "..."))
                .append("aa bb cc dd ee", TextStyle(fontSize = 10f))
                .build(),
            maxWidth = 50f,
        ),
        "mixed-style-overflow" to BasicParagraphLayoutEngine(RecordingEllipsisShapingEngine()).layout(
            ParagraphBuilder(ParagraphStyle(maxLines = 2, ellipsis = "..."))
                .append("aa\n", TextStyle(fontSize = 10f))
                .append("bb c\nd", TextStyle(fontSize = 12f))
                .build(),
            maxWidth = 48f,
        ),
        "bidi-overflow" to BasicParagraphLayoutEngine(BidiAwareRecordingEllipsisShapingEngine()).layout(
            ParagraphBuilder(ParagraphStyle(textDirection = TextDirection.RIGHT_TO_LEFT, maxLines = 2, ellipsis = "..."))
                .append("אב\nגד ה\nו", TextStyle(fontSize = 10f))
                .build(),
            maxWidth = 50f,
        ),
        "placeholder-conflict" to BasicParagraphLayoutEngine(RecordingEllipsisShapingEngine()).layout(
            ParagraphBuilder(ParagraphStyle(maxLines = 1, ellipsis = "..."))
                .append("a", TextStyle(fontSize = 10f))
                .appendPlaceholder(
                    PlaceholderStyle(
                        width = 12f,
                        height = 12f,
                        baselineOffset = 10f,
                        alignment = PlaceholderAlignment.BASELINE,
                    ),
                )
                .append("\nb", TextStyle(fontSize = 10f))
                .build(),
            maxWidth = 24f,
        ),
        "ellipsis-no-room" to BasicParagraphLayoutEngine(RecordingEllipsisShapingEngine()).layout(
            ParagraphBuilder(ParagraphStyle(maxLines = 1, ellipsis = "..."))
                .append("aa bb c", TextStyle(fontSize = 10f))
                .build(),
            maxWidth = 20f,
        ),
        "ellipsis-glyph-missing" to BasicParagraphLayoutEngine(MissingEllipsisGlyphShapingEngine()).layout(
            ParagraphBuilder(ParagraphStyle(maxLines = 1, ellipsis = "..."))
                .append("aa bb c", TextStyle(fontSize = 10f))
                .build(),
            maxWidth = 50f,
        ),
    )

    private fun projectRoot(): Path =
        generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath()) { path -> path.parent }
            .first { path -> Files.exists(path.resolve(".git")) }
}

private fun io.ygdrasil.kalligraphie.text.paragraph.ParagraphLayoutResult.toEllipsisCaseDump(caseId: String): String = buildString {
    append("{\"caseId\": ")
        .append(jsonString(caseId))
        .append(", \"layout\": {\"width\": ")
        .append(width)
        .append(", \"height\": ")
        .append(height)
        .append(", \"didOverflowWidth\": ")
        .append(didOverflowWidth)
        .append(", \"didOverflowHeight\": ")
        .append(didOverflowHeight)
        .append("}, \"placeholderBoxes\": ")
        .append(placeholderBoxes.joinToString(prefix = "[", postfix = "]") { box ->
            "{\"placeholderId\": ${jsonString(box.placeholderId)}, \"sourceRange\": ${jsonString("${box.sourceRange.first}..${box.sourceRange.last}")}, \"lineIndex\": ${box.lineIndex}, \"left\": ${box.left}, \"right\": ${box.right}}"
        })
        .append(", \"lines\": ")
        .append(lines.withIndex().joinToString(prefix = "[", postfix = "]") { (index, line) ->
            "{\"index\": $index, \"textRange\": ${jsonString("${line.textRange.first}..${line.textRange.last}")}, \"width\": ${line.metrics.width}, \"direction\": ${line.boxes.firstOrNull()?.direction ?: 1}, \"isEllipsized\": ${line.isEllipsized}, \"visibleRange\": ${jsonString("${line.visibleRange.first}..${line.visibleRange.last}")}, \"truncatedRange\": ${line.truncatedRange?.let { jsonString("${it.first}..${it.last}") } ?: "null"}, \"ellipsisText\": ${line.ellipsisGlyphRun?.text?.let(::jsonString) ?: "null"}, \"ellipsisAdvance\": ${line.ellipsisGlyphRun?.advanceX ?: 0f}, \"ellipsisFontSize\": ${line.ellipsisGlyphRun?.fontSize ?: 0f}, \"ellipsisSourceStyleRange\": ${line.ellipsisGlyphRun?.sourceStyleRange?.let { jsonString("${it.first}..${it.last}") } ?: "null"}, \"ellipsisBidiLevel\": ${line.ellipsisGlyphRun?.bidiLevel ?: 0}}"
        })
        .append(", \"diagnostics\": ")
        .append(diagnostics.joinToString(prefix = "[", postfix = "]") { diagnostic -> jsonString(diagnostic.code) })
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

private open class RecordingEllipsisShapingEngine : OpenTypeShapingEngine {
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

private class MultiGlyphClusterEllipsisShapingEngine : RecordingEllipsisShapingEngine() {
    override fun shape(request: ShapingRequest): ShapingResult {
        if (request.text == "...") {
            return super.shape(request)
        }
        val glyphIds = mutableListOf<Int>()
        val clusters = mutableListOf<GlyphCluster>()
        request.textRange.forEach { textIndex ->
            if (request.text[textIndex] == ' ') {
                val glyphIndex = glyphIds.size
                glyphIds += glyphIndex
                clusters += GlyphCluster(
                    textRange = textIndex..textIndex,
                    glyphRange = glyphIndex..glyphIndex,
                    advanceX = request.fontSize,
                )
            } else {
                val firstGlyphIndex = glyphIds.size
                glyphIds += firstGlyphIndex
                glyphIds += firstGlyphIndex + 1
                clusters += GlyphCluster(
                    textRange = textIndex..textIndex,
                    glyphRange = firstGlyphIndex..(firstGlyphIndex + 1),
                    advanceX = request.fontSize,
                )
            }
        }
        return ShapingResult(
            glyphRuns = listOf(
                ShapedGlyphRun(
                    glyphIds = glyphIds,
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

private class BidiAwareRecordingEllipsisShapingEngine : RecordingEllipsisShapingEngine() {
    override fun shape(request: ShapingRequest): ShapingResult {
        val result = super.shape(request)
        return result.copy(
            glyphRuns = result.glyphRuns.map { run ->
                run.copy(bidiLevel = if (request.paragraphDirection < 0) 1 else 0)
            },
        )
    }
}

private class MissingEllipsisGlyphShapingEngine : RecordingEllipsisShapingEngine() {
    override fun shape(request: ShapingRequest): ShapingResult =
        if (request.text == "...") {
            ShapingResult(glyphRuns = emptyList(), diagnostics = emptyList())
        } else {
            super.shape(request)
        }
}
