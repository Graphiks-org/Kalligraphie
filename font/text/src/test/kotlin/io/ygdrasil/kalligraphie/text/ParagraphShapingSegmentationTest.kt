package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.uuid.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ygdrasil.kalligraphie.font.FallbackRequest
import io.ygdrasil.kalligraphie.font.FontFace
import io.ygdrasil.kalligraphie.font.FontResolver
import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import io.ygdrasil.kalligraphie.font.ResolvedFontRun
import io.ygdrasil.kalligraphie.font.TypefaceData
import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.text.paragraph.DefaultParagraphShapingSegmenter
import io.ygdrasil.kalligraphie.text.paragraph.PARAGRAPH_CLUSTER_BOUNDARY_VIOLATION_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.paragraph.PARAGRAPH_FALLBACK_UNRESOLVED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.paragraph.ParagraphBuilder
import io.ygdrasil.kalligraphie.text.paragraph.TextStyle

class ParagraphShapingSegmentationTest {
    @Test
    fun paragraphShapingSegmenterCoalescesAdjacentEquivalentTextRunsAndSkipsPlaceholders() {
        val paragraph = ParagraphBuilder()
            .append("ab", TextStyle(fontSize = 12f, locale = "en-US"))
            .append("cd", TextStyle(fontSize = 12f, locale = "en-US"))
            .appendPlaceholder(
                style = io.ygdrasil.kalligraphie.text.paragraph.PlaceholderStyle(
                    width = 16f,
                    height = 10f,
                ),
            )
            .append("ef", TextStyle(fontSize = 12f, locale = "en-US"))
            .build()

        val plan = DefaultParagraphShapingSegmenter().segment(paragraph)

        assertEquals(listOf(0..3, 5..6), plan.requests.map { request -> request.textRange })
        assertEquals(listOf(4..4), plan.placeholderRanges)
        assertEquals(emptyList(), plan.diagnostics)
    }

    @Test
    fun paragraphShapingSegmenterSplitsByResolvedFallbackTypefaceRanges() {
        val latinFace = testFace("550e8400-e29b-41d4-a716-446655440801", "Latin Sans")
        val fallbackFace = testFace("550e8400-e29b-41d4-a716-446655440802", "Fallback Sans")
        val paragraph = ParagraphBuilder()
            .append("abz", TextStyle(fontFamilies = listOf("Latin Sans"), fontSize = 12f, locale = "en-US"))
            .build()

        val plan = DefaultParagraphShapingSegmenter(
            fontResolver = object : FontResolver {
                override fun resolve(request: FallbackRequest): List<ResolvedFontRun> {
                    assertEquals("abz", request.text)
                    return listOf(
                        ResolvedFontRun(start = 0, end = 2, face = latinFace),
                        ResolvedFontRun(start = 2, end = 3, face = fallbackFace),
                    )
                }
            },
        ).segment(paragraph)

        assertEquals(listOf(0..1, 2..2), plan.requests.map { request -> request.textRange })
        assertEquals(
            listOf(latinFace.typeface.id, fallbackFace.typeface.id),
            plan.requests.map { request -> request.typefaceId },
        )
    }

    @Test
    fun paragraphShapingSegmenterWidensStyleBoundariesToClusterCoverage() {
        val baseStyle = TextStyle(fontSize = 12f, locale = "en-US")
        val accentStyle = TextStyle(fontSize = 14f, locale = "en-US")
        val paragraph = ParagraphBuilder()
            .append("a", baseStyle)
            .append("\u0301b", accentStyle)
            .build()

        val plan = DefaultParagraphShapingSegmenter().segment(paragraph)

        assertEquals(listOf(0..1, 2..2), plan.requests.map { request -> request.textRange })
        assertEquals(1, plan.diagnostics.size)
        assertEquals(PARAGRAPH_CLUSTER_BOUNDARY_VIOLATION_DIAGNOSTIC_CODE, plan.diagnostics.single().code)
        assertEquals(0..1, plan.diagnostics.single().textRange)
        assertTrue(plan.requests.first().style === baseStyle)
    }

    @Test
    fun paragraphShapingSegmenterLeavesUnresolvedFallbackRangeUnshapedWithDiagnostic() {
        val paragraph = ParagraphBuilder()
            .append("abc", TextStyle(fontFamilies = listOf("Missing Sans"), fontSize = 12f, locale = "en-US"))
            .build()

        val plan = DefaultParagraphShapingSegmenter(
            fontResolver = object : FontResolver {
                override fun resolve(request: FallbackRequest): List<ResolvedFontRun> = emptyList()
            },
        ).segment(paragraph)

        assertEquals(emptyList(), plan.requests)
        assertEquals(listOf(PARAGRAPH_FALLBACK_UNRESOLVED_DIAGNOSTIC_CODE), plan.diagnostics.map { it.code })
        assertEquals(0..2, plan.diagnostics.single().textRange)
        assertEquals("refusal", plan.diagnostics.single().severity)
    }

    @Test
    fun paragraphShapingRequestDumpMatchesExpectedFixture() {
        val latinFace = testFace("550e8400-e29b-41d4-a716-446655440811", "Liberation Sans")
        val arabicFace = testFace("550e8400-e29b-41d4-a716-446655440812", "Liberation Serif")
        val fallbackFace = testFace("550e8400-e29b-41d4-a716-446655440813", "Fallback Sans")
        val paragraph = ParagraphBuilder(
            paragraphStyle = io.ygdrasil.kalligraphie.text.paragraph.ParagraphStyle(
                textDirection = io.ygdrasil.kalligraphie.text.paragraph.TextDirection.LEFT_TO_RIGHT,
            ),
        )
            .append(
                "ab",
                TextStyle(
                    fontFamilies = listOf("Liberation Sans"),
                    fallbackPreference = io.ygdrasil.kalligraphie.text.paragraph.FallbackPreference.PREFER_DECLARED_FAMILIES,
                    fontSize = 12f,
                    locale = "en-US",
                ),
            )
            .append(
                "س",
                TextStyle(
                    fontFamilies = listOf("Liberation Serif"),
                    fallbackPreference = io.ygdrasil.kalligraphie.text.paragraph.FallbackPreference.PREFER_DECLARED_FAMILIES,
                    fontSize = 14f,
                    locale = "ar",
                ),
            )
            .append(
                "لا",
                TextStyle(
                    fontFamilies = listOf("Liberation Serif"),
                    fallbackPreference = io.ygdrasil.kalligraphie.text.paragraph.FallbackPreference.PREFER_DECLARED_FAMILIES,
                    fontSize = 14f,
                    locale = "ar",
                    variationCoordinates = mapOf("wght" to 625f),
                ),
            )
            .appendPlaceholder(
                style = io.ygdrasil.kalligraphie.text.paragraph.PlaceholderStyle(
                    width = 18f,
                    height = 10f,
                ),
            )
            .append(
                "z",
                TextStyle(
                    fontFamilies = listOf("Liberation Sans"),
                    fallbackPreference = io.ygdrasil.kalligraphie.text.paragraph.FallbackPreference.PREFER_DECLARED_FAMILIES,
                    fontSize = 12f,
                    locale = "en-US",
                ),
            )
            .build()

        val plan = DefaultParagraphShapingSegmenter(
            fontResolver = object : FontResolver {
                override fun resolve(request: FallbackRequest): List<ResolvedFontRun> =
                    when (request.text) {
                        "ab" -> listOf(ResolvedFontRun(start = 0, end = 2, face = latinFace))
                        "س" -> listOf(ResolvedFontRun(start = 0, end = 1, face = arabicFace))
                        "لا" -> listOf(ResolvedFontRun(start = 0, end = 2, face = arabicFace))
                        "z" -> listOf(ResolvedFontRun(start = 0, end = 1, face = fallbackFace))
                        else -> error("Unexpected request text: ${request.text}")
                    }
            },
        ).segment(paragraph)

        val expected = Files.readString(
            projectRoot().resolve("font/fixtures/expected/paragraph/paragraph-shaping-requests.json"),
        )

        assertEquals(expected, plan.dump(paragraph))
    }

    private fun testFace(uuid: String, familyName: String): FontFace {
        val sourceId = FontSourceID(Uuid.parse(uuid.replaceRange(uuid.length - 1, uuid.length, "0")))
        val typefaceId = TypefaceID(Uuid.parse(uuid))
        return FontFace(
            typeface = TypefaceData(
                id = typefaceId,
                source = FontSource(
                    id = sourceId,
                    kind = FontSourceKind.MEMORY,
                    displayName = familyName,
                    bytes = ByteArray(0),
                ),
                familyName = familyName,
                styleName = "Regular",
            ),
        )
    }

    private fun projectRoot(): Path =
        generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath()) { path -> path.parent }
            .first { path -> Files.exists(path.resolve(".git")) }
}
