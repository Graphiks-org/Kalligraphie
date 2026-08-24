package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ygdrasil.kalligraphie.font.FontSlant
import io.ygdrasil.kalligraphie.font.FontStyle
import io.ygdrasil.kalligraphie.font.TypefacePaletteSelection
import io.ygdrasil.kalligraphie.text.paragraph.BasicParagraphLayoutEngine
import io.ygdrasil.kalligraphie.text.paragraph.EllipsisPolicy
import io.ygdrasil.kalligraphie.text.paragraph.FallbackPreference
import io.ygdrasil.kalligraphie.text.paragraph.ParagraphBuilder
import io.ygdrasil.kalligraphie.text.paragraph.ParagraphStyle
import io.ygdrasil.kalligraphie.text.paragraph.PlaceholderAlignment
import io.ygdrasil.kalligraphie.text.paragraph.PlaceholderBaseline
import io.ygdrasil.kalligraphie.text.paragraph.PlaceholderStyle
import io.ygdrasil.kalligraphie.text.paragraph.SyntheticStylePolicy
import io.ygdrasil.kalligraphie.text.paragraph.TextAlign
import io.ygdrasil.kalligraphie.text.paragraph.TextDecorationSpec
import io.ygdrasil.kalligraphie.text.paragraph.TextDecorationStyle
import io.ygdrasil.kalligraphie.text.paragraph.TextDirection
import io.ygdrasil.kalligraphie.text.paragraph.TextHeightBehavior
import io.ygdrasil.kalligraphie.text.paragraph.TextStyle
import io.ygdrasil.kalligraphie.text.shaping.GlyphCluster
import io.ygdrasil.kalligraphie.text.shaping.OpenTypeShapingEngine
import io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun
import io.ygdrasil.kalligraphie.text.shaping.ShapingDiagnostic
import io.ygdrasil.kalligraphie.text.shaping.ShapingRequest
import io.ygdrasil.kalligraphie.text.shaping.ShapingResult

class ParagraphStyleContractTest {
    @Test
    fun paragraphStyleContractDumpCapturesExpandedInputFacts() {
        val dump = richParagraphBuilder().build().dumpInput()

        assertContains(dump, "\"schema\": \"kanvas.paragraph.input.v1\"")
        assertContains(dump, "\"unicodeVersion\": \"16.0.0\"")
        assertContains(dump, "\"textAlign\": \"center\"")
        assertContains(dump, "\"textDirection\": \"rtl\"")
        assertContains(dump, "\"ellipsisPolicy\": \"end\"")
        assertContains(dump, "\"fontFamilies\": [\"Liberation Sans\", \"Noto Sans\"]")
        assertContains(dump, "\"fontFamilies\": [\"Liberation Serif\"]")
        assertContains(dump, "\"fallbackPreference\": \"prefer-declared-families\"")
        assertContains(dump, "\"fontWeight\": 600")
        assertContains(dump, "\"fontSlant\": \"italic\"")
        assertContains(dump, "\"scriptHint\": \"Cyrl\"")
        assertContains(dump, "\"variationCoordinates\": [{\"axisTag\": \"wdth\", \"value\": 95.0}, {\"axisTag\": \"wght\", \"value\": 625.0}]")
        assertContains(dump, "\"palette\": {\"index\": 2, \"overrides\": [\"gid=7:#ff00ffff\"]}")
        assertContains(dump, "\"decoration\": {\"underline\": true, \"overline\": false, \"lineThrough\": true, \"style\": \"double\", \"thicknessMultiplier\": 1.25}")
        assertContains(dump, "\"placeholders\": [")
        assertContains(dump, "{\"range\": \"5..5\", \"width\": 18.0, \"height\": 10.0, \"baselineOffset\": 8.0, \"alignment\": \"baseline\", \"baseline\": \"alphabetic\", \"participatesInLineHeight\": true}")
        assertTrue(Regex("\"inputHash\": \"[0-9a-f]{64}\"").containsMatchIn(dump))
    }

    @Test
    fun paragraphStyleContractGoldenMatchesRepoFixture() {
        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/paragraph/paragraph-input.json"))

        assertEquals(expected, richParagraphBuilder().build().dumpInput())
    }

    @Test
    fun paragraphStyleContractSnapshotRemainsImmutableAcrossLaterBuilderMutations() {
        val builder = richParagraphBuilder()
        val first = builder.build()
        val firstHash = first.inputHash

        builder.append(
            "!",
            TextStyle(
                fontFamilies = listOf("Liberation Mono"),
                fontSize = 11f,
            ),
        )
        val second = builder.build()

        assertEquals(firstHash, first.inputHash)
        assertFalse(first.dumpInput().contains("Liberation Mono"))
        assertTrue(second.dumpInput().contains("Liberation Mono"))
    }

    @Test
    fun paragraphStyleContractRefusesInvalidConstraintsBeforeShaping() {
        val shapingEngine = RecordingShapingEngine()
        val paragraph = ParagraphBuilder(
            ParagraphStyle(textHeightBehavior = TextHeightBehavior.STRUT),
        ).append(
            "bad",
            TextStyle(
                fontSize = -1f,
                variationCoordinates = mapOf("wght" to Float.NaN),
            ),
        ).appendPlaceholder(
            PlaceholderStyle(
                width = 12f,
                height = 8f,
                alignment = PlaceholderAlignment.BASELINE,
                baseline = PlaceholderBaseline.IDEOGRAPHIC,
            ),
        ).build()

        val result = BasicParagraphLayoutEngine(shapingEngine).layout(paragraph, maxWidth = 64f)

        assertTrue(result.layoutRefused)
        assertTrue(shapingEngine.requests.isEmpty())
        assertEquals(
            setOf("text.paragraph.invalid-constraint", "text.paragraph.unsupported-policy"),
            result.diagnostics.map { it.code }.toSet(),
        )
    }

    @Test
    fun paragraphStyleContractRejectsMissingPlaceholderBaselineWhenAlignmentRequiresIt() {
        val paragraph = ParagraphBuilder()
            .append("a", TextStyle(fontSize = 10f))
            .appendPlaceholder(
                PlaceholderStyle(
                    width = 12f,
                    height = 8f,
                    alignment = PlaceholderAlignment.BASELINE,
                    baseline = null,
                ),
            )
            .build()

        assertEquals(
            listOf("text.paragraph.invalid-constraint"),
            paragraph.inputDiagnostics.map { it.code },
        )
    }

    private fun richParagraphBuilder(): ParagraphBuilder =
        ParagraphBuilder(
            ParagraphStyle(
                textAlign = TextAlign.CENTER,
                textDirection = TextDirection.RIGHT_TO_LEFT,
                maxLines = 2,
                ellipsis = "...",
                ellipsisPolicy = EllipsisPolicy.END,
                lineHeight = 20f,
                textHeightBehavior = TextHeightBehavior.FONT_METRICS,
                defaultLocale = "sr-Latn",
            ),
        ).append(
            "hello",
            TextStyle(
                fontFamilies = listOf("Liberation Sans", "Noto Sans"),
                fallbackPreference = FallbackPreference.PREFER_DECLARED_FAMILIES,
                fontSize = 14f,
                fontStyle = FontStyle(weight = 600, width = 5, slant = FontSlant.ITALIC),
                syntheticStylePolicy = SyntheticStylePolicy.DISALLOW,
                locale = "sr-Latn",
                scriptHint = "Cyrl",
                features = mapOf("liga" to 0, "ss01" to 1),
                variationCoordinates = mapOf("wght" to 625f, "wdth" to 95f),
                palette = TypefacePaletteSelection(index = 2, overrides = listOf("gid=7:#ff00ffff")),
                colorRgba = 0xff223344,
                decoration = TextDecorationSpec(
                    underline = true,
                    lineThrough = true,
                    style = TextDecorationStyle.DOUBLE,
                    thicknessMultiplier = 1.25f,
                ),
                letterSpacing = 0.5f,
                wordSpacing = 1.25f,
                heightMultiplier = 1.2f,
            ),
        ).appendPlaceholder(
            PlaceholderStyle(
                width = 18f,
                height = 10f,
                baselineOffset = 8f,
                alignment = PlaceholderAlignment.BASELINE,
                baseline = PlaceholderBaseline.ALPHABETIC,
            ),
        ).append(
            "world",
            TextStyle(
                fontFamilies = listOf("Liberation Serif"),
                fontSize = 16f,
                locale = "en-US",
                features = mapOf("kern" to 1),
            ),
        )

    private class RecordingShapingEngine(
        private val diagnostics: List<ShapingDiagnostic> = emptyList(),
    ) : OpenTypeShapingEngine {
        val requests = mutableListOf<ShapingRequest>()

        override fun shape(request: ShapingRequest): ShapingResult {
            requests += request
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
                diagnostics = diagnostics,
            )
        }
    }

    private fun projectRoot(): Path =
        generateSequence(Paths.get("").toAbsolutePath()) { path -> path.parent }
            .firstOrNull { path -> Files.exists(path.resolve("settings.gradle.kts")) }
            ?: error("Could not locate project root from ${Paths.get("").toAbsolutePath()}")
}
