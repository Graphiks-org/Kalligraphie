package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ygdrasil.kalligraphie.text.paragraph.DefaultUax14LineBreaker
import io.ygdrasil.kalligraphie.text.paragraph.LINE_BREAK_ALLOWED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.paragraph.LINE_BREAK_LOCALE_REFINEMENT_UNAVAILABLE_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.paragraph.LineBreakKind
import io.ygdrasil.kalligraphie.text.paragraph.ParagraphBuilder
import io.ygdrasil.kalligraphie.text.paragraph.ParagraphStyle
import io.ygdrasil.kalligraphie.text.paragraph.TextStyle

class ParagraphLineBreakingTest {
    @Test
    fun uax14LineBreakerDumpMatchesExpectedFixture() {
        val breaker = DefaultUax14LineBreaker()
        val fixture = breaker.dumpFixtures(lineBreakFixtureCases())

        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/paragraph/line-breaks.json"))

        assertEquals(expected, fixture)
    }

    @Test
    fun uax14LineBreakerSuppressesOptionalBreaksWhenSoftWrapIsDisabled() {
        val breaker = DefaultUax14LineBreaker()
        val paragraph = ParagraphBuilder(
            paragraphStyle = ParagraphStyle(softWrap = false),
        ).append("aa bb\ncc", TextStyle(fontSize = 10f)).build()

        val map = breaker.analyze(paragraph)

        assertTrue(map.opportunities.none { it.kind == LineBreakKind.ALLOWED })
        assertTrue(map.opportunities.any { it.kind == LineBreakKind.MANDATORY && it.reason == "BK" })
        assertEquals(listOf(0..4, 6..7), breaker.breakLines(paragraph, maxWidth = 50f))
        assertEquals(listOf(0..4, 6..7), breaker.breakLines(paragraph, maxWidth = 20f))
    }

    @Test
    fun uax14LineBreakerKeepsGraphemeClustersIntactAndFlagsLocaleRefinementGaps() {
        val breaker = DefaultUax14LineBreaker()
        val paragraph = ParagraphBuilder(
            paragraphStyle = ParagraphStyle(defaultLocale = "th", softWrap = true),
        ).append("a\u0301 ไทย", TextStyle(fontSize = 10f, locale = "th")).build()

        val map = breaker.analyze(paragraph)

        assertTrue(map.opportunities.none { opportunity -> opportunity.offset == 1 && opportunity.kind == LineBreakKind.ALLOWED })
        assertEquals(
            listOf(LINE_BREAK_LOCALE_REFINEMENT_UNAVAILABLE_DIAGNOSTIC_CODE),
            map.diagnostics.map { it.code }.distinct(),
        )
        assertTrue(
            map.opportunities.any { opportunity ->
                opportunity.offset == 3 &&
                    opportunity.kind == LineBreakKind.ALLOWED &&
                    opportunity.reason == LINE_BREAK_ALLOWED_DIAGNOSTIC_CODE
            },
        )
    }

    @Test
    fun uax14LineBreakerAllowsFallbackBreaksBetweenCjkClusters() {
        val breaker = DefaultUax14LineBreaker()
        val paragraph = ParagraphBuilder().append("日本語テキスト", TextStyle(fontSize = 10f)).build()

        val map = breaker.analyze(paragraph)

        assertTrue(map.opportunities.any { it.offset == 1 && it.kind == LineBreakKind.ALLOWED && it.reason == "LB31" })
        assertTrue(map.opportunities.any { it.offset == 6 && it.kind == LineBreakKind.ALLOWED && it.reason == "LB31" })
    }

    private fun projectRoot(): Path =
        generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath()) { path -> path.parent }
            .first { path -> Files.exists(path.resolve(".git")) }

    private fun lineBreakFixtureCases(): List<Pair<String, io.ygdrasil.kalligraphie.text.paragraph.Paragraph>> =
        Files.readAllLines(projectRoot().resolve("font/fixtures/fonts/paragraph/paragraph-line-breaking-fixture.txt"))
            .filter { line -> line.isNotBlank() }
            .map { line ->
                val separator = line.indexOf(": ")
                require(separator >= 0) { "Invalid line break fixture line: $line" }
                val header = line.substring(0, separator)
                val text = line.substring(separator + 2)
                    .replace("\\n", "\n")
                val localePrefix = " (locale="
                val localeStart = header.indexOf(localePrefix)
                val caseId = if (localeStart >= 0) header.substring(0, localeStart) else header
                val locale = if (localeStart >= 0) {
                    header.substring(localeStart + localePrefix.length, header.lastIndexOf(')'))
                } else {
                    null
                }
                caseId to ParagraphBuilder(
                    paragraphStyle = ParagraphStyle(defaultLocale = locale),
                ).append(
                    text,
                    TextStyle(fontSize = 10f, locale = locale),
                ).build()
            }
}
