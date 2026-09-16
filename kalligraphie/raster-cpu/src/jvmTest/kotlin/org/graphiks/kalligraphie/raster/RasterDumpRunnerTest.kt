package org.graphiks.kalligraphie.raster

import java.nio.file.Files
import java.nio.file.Path
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RasterDumpRunnerTest {
    @Test
    fun writesDeterministicDumpsOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_RASTER_DUMPS") != "true") return
        val configuredOutput = Path.of(
            checkNotNull(System.getenv("KALLIGRAPHIE_RASTER_DUMPS_OUTPUT")) {
                "KALLIGRAPHIE_RASTER_DUMPS_OUTPUT is required when dumps are enabled."
            },
        )
        require(configuredOutput.isAbsolute) { "KALLIGRAPHIE_RASTER_DUMPS_OUTPUT must be an absolute path." }
        val outputDirectory = configuredOutput.normalize()
        require(!outputDirectory.startsWith(repositoryRoot())) {
            "KALLIGRAPHIE_RASTER_DUMPS_OUTPUT must be outside the repository; received $outputDirectory."
        }
        Files.createDirectories(outputDirectory)

        val dumps = LinkedHashMap<String, Dump>()

        fun add(name: String, render: () -> Dump) {
            check(name !in dumps) { "duplicate dump name: $name" }
            val first = render()
            val second = render()
            assertTrue(
                first.bytes.contentEquals(second.bytes),
                "repeated rendering must be identical for $name",
            )
            dumps[name] = first
        }

        add("liberation-a-64.pgm") { rawGlyphDump() }
        add("emoji-two-64.ppm") { rawPaintDump() }

        add("sheet-liberation-latin-32.pgm") {
            GlyphSheetDumps.outlineSheet(
                fontPath = "/fonts/liberation/LiberationSans-Regular.ttf",
                codepoints = LATIN,
                pixelsPerEm = 32.0,
            )
        }
        add("sheet-liberation-greek-32.pgm") {
            GlyphSheetDumps.outlineSheet(
                fontPath = "/fonts/liberation/LiberationSans-Regular.ttf",
                codepoints = GREEK,
                pixelsPerEm = 32.0,
            )
        }
        add("sheet-liberation-cyrillic-32.pgm") {
            GlyphSheetDumps.outlineSheet(
                fontPath = "/fonts/liberation/LiberationSans-Regular.ttf",
                codepoints = CYRILLIC,
                pixelsPerEm = 32.0,
            )
        }
        add("sheet-amiri-arabic-32.pgm") {
            GlyphSheetDumps.outlineSheet(
                fontPath = "/fonts/amiri/Amiri-Regular.ttf",
                codepoints = ARABIC,
                pixelsPerEm = 32.0,
            )
        }
        add("sheet-noto-devanagari-32.pgm") {
            GlyphSheetDumps.outlineSheet(
                fontPath = "/fonts/noto-devanagari/NotoSansDevanagari-Regular.ttf",
                codepoints = DEVANAGARI,
                pixelsPerEm = 32.0,
            )
        }
        add("sheet-bungee-latin-48.ppm") {
            GlyphSheetDumps.paintSheet(
                fontPath = "/fonts/bungee-color/BungeeColor-Regular.ttf",
                codepoints = LATIN_LETTERS,
                pixelsPerEm = 48.0,
                paletteIndex = 0,
            )
        }
        add("sheet-emoji-two-64.ppm") {
            GlyphSheetDumps.paintSheet(
                fontPath = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf",
                codepoints = EMOJI,
                pixelsPerEm = 64.0,
                paletteIndex = 0,
            )
        }
        add("glyph-ebdt-format1-16.ppm") {
            GlyphSheetDumps.bitmapDump(
                fontPath = "/fonts/skia-ebdt-format1/ebdt_fmt1.ttf",
                codepoint = 0x1F600,
            )
        }

        add("line-latin-48.pgm") { ComposedLineDumps.line(text = "Kalligraphie", language = "en") }
        add("line-greek-48.pgm") { ComposedLineDumps.line(text = "Καλλιγραφία", language = "el") }
        add("line-cyrillic-48.pgm") { ComposedLineDumps.line(text = "Каллиграфия", language = "ru") }
        add("line-arabic-48.pgm") {
            ComposedLineDumps.line(
                text = "الخط العربي",
                language = "ar",
                baseDirection = BaseDirection.RIGHT_TO_LEFT,
            )
        }
        add("line-devanagari-48.pgm") { ComposedLineDumps.line(text = "देवनागरी", language = "hi") }
        add("line-mixed-48.pgm") {
            ComposedLineDumps.line(
                text = "Kalligraphie — Ελληνικά — Кириллица — العربية — देवनागरी",
                language = "en",
                requiredFaces = 3,
            )
        }

        check(dumps.size == 16) { "expected 16 dumps, composed ${dumps.size}" }

        val manifest = buildString {
            appendLine("# Raster dump manifest")
            appendLine()
            appendLine("- git commit: `${gitCommit()}`")
            appendLine(
                "- fonts: Amiri-Regular.ttf, BungeeColor-Regular.ttf, EmojiTwoCOLRv0.ttf, " +
                    "LiberationSans-Regular.ttf, NotoSansDevanagari-Regular.ttf, ebdt_fmt1.ttf",
            )
            appendLine("- images: ${dumps.size}")
            appendLine(
                "- orientation: composed sheets and lines are flipped vertically; " +
                    "raw glyph dumps keep source orientation; the EBDT strike is image-oriented and drawn unflipped",
            )
            appendLine("- pixels per em: 32 (outline sheets), 48 (lines, Bungee sheet), 64 (raw glyph dumps, EmojiTwo sheet), 16 (EBDT strike)")
            dumps.entries.sortedBy { entry -> entry.key }.forEach { entry ->
                val note = entry.value.note
                    .takeIf { note -> note.isNotEmpty() }
                    ?.let { note -> " ($note)" }
                    .orEmpty()
                appendLine("- ${entry.key}: sha256=${sha256(entry.value.bytes)} bytes=${entry.value.bytes.size}$note")
            }
        }

        for ((name, dump) in dumps) {
            val target = outputDirectory.resolve(name)
            val second = outputDirectory.resolve("$name.second")
            try {
                Files.write(target, dump.bytes)
                Files.write(second, dump.bytes)
                assertTrue(Files.mismatch(target, second) == -1L, "repeated dumps must be identical for $name")
            } finally {
                Files.deleteIfExists(second)
            }
        }
        Files.writeString(outputDirectory.resolve("manifest.md"), manifest)
    }

    private fun rawGlyphDump(): Dump = openRasterFixture(
        fixtureBytes("/fonts/liberation/LiberationSans-Regular.ttf"),
        outlineRequirements(),
    ).use { fixture ->
        val glyph = assertIs<FontOperationResult.Success<GlyphResolution>>(
            fixture.instance.resolveGlyph(0x41),
        ).value.glyphId
        val outline = assertIs<GlyphRepresentation.Outline>(
            assertIs<FontOperationResult.Success<GlyphRepresentation>>(
                fixture.asset.resolveGlyph(FontGlyphRequest(glyph)),
            ).value,
        ).outline
        val image = assertIs<RasterResult.Success<A8Image>>(
            GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm = 64.0)),
        ).value
        Dump(bytes = pgm(image))
    }

    private fun rawPaintDump(): Dump = openRasterFixture(
        fixtureBytes("/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf"),
        FontAccessRequirementsSnapshot.renderable(listOf(paintProfile())),
        renderVariant = FontRenderVariantSnapshot(cpalPaletteIndex = 0),
    ).use { fixture ->
        val glyph = assertIs<FontOperationResult.Success<GlyphResolution>>(
            fixture.instance.resolveGlyph(0x1F600),
        ).value.glyphId
        val paint = assertIs<GlyphRepresentation.Paint>(
            assertIs<FontOperationResult.Success<GlyphRepresentation>>(
                fixture.asset.resolveGlyph(FontGlyphRequest(glyph)),
            ).value,
        ).paint
        val solidOutline = assertIs<GlyphPaintNode.SolidOutline>(
            paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().firstOrNull(),
            "the emoji paint graph must contain a solid outline",
        )
        val image = assertIs<RasterResult.Success<Rgba8Image>>(
            GlyphRasterizer.rasterizePaint(
                paint,
                PaintRasterRequest(pixelsPerEm = 64.0, unitsPerEm = solidOutline.outline.unitsPerEm),
            ),
        ).value
        Dump(
            bytes = ppm(image),
            note = "RGB over white: out = (c * a + 255 * (255 - a) + 127) / 255",
        )
    }

    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.exists(candidate.resolve(".git"))) return candidate
            candidate = candidate.parent
        }
        error("Could not locate the repository root from the test working directory.")
    }

    private fun gitCommit(): String {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{40}"))) {
            "Could not identify the dumped commit: $output"
        }
        return output
    }

    private companion object {
        val LATIN_LETTERS: List<Int> = (0x41..0x5A).toList()
        val LATIN: List<Int> = LATIN_LETTERS + (0x61..0x7A) + (0x30..0x39)
        // U+03A2 is unassigned.
        val GREEK: List<Int> = (0x391..0x3A9).filter { codepoint -> codepoint != 0x3A2 } + (0x3B1..0x3C9)
        val CYRILLIC: List<Int> = (0x410..0x42F).toList() + (0x430..0x44F).toList()
        // Core Arabic letters only: Persian/Urdu variants (U+063B–U+063F) and tatweel (U+0640) are outside the curated set.
        val ARABIC: List<Int> = (0x621..0x63A).toList() + (0x641..0x64A).toList()
        val DEVANAGARI: List<Int> = (0x905..0x939).toList() + (0x966..0x96F).toList()
        // U+1F602 (7 layers) and U+1F604 (10 layers) exceed the shared paint profile maxPaths=6; omitted deliberately.
        val EMOJI: List<Int> = (0x1F600..0x1F607).filter { codepoint ->
            codepoint != 0x1F602 && codepoint != 0x1F604
        }
    }
}
