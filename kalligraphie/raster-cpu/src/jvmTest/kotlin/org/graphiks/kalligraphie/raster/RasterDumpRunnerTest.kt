package org.graphiks.kalligraphie.raster

import java.nio.file.Files
import java.nio.file.Path
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.raster.rasterRepositoryRoot
import kotlin.test.Test
import kotlin.test.assertEquals
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
        require(!outputDirectory.startsWith(rasterRepositoryRoot())) {
            "KALLIGRAPHIE_RASTER_DUMPS_OUTPUT must be outside the repository; received $outputDirectory."
        }
        Files.createDirectories(outputDirectory)

        val dumps = mutableMapOf<String, Dump>()
        openRasterFixture(
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
            val request = OutlineRasterRequest(pixelsPerEm = 64.0)
            val first = assertIs<RasterResult.Success<A8Image>>(
                GlyphRasterizer.rasterizeOutline(outline, request),
            ).value
            val second = assertIs<RasterResult.Success<A8Image>>(
                GlyphRasterizer.rasterizeOutline(outline, request),
            ).value
            assertEquals(first, second, "repeated rasterization must be identical")
            dumps["liberation-a-64.pgm"] = Dump(pgm(first))
        }

        openRasterFixture(
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
            val request = PaintRasterRequest(pixelsPerEm = 64.0, unitsPerEm = solidOutline.outline.unitsPerEm)
            val first = assertIs<RasterResult.Success<Rgba8Image>>(
                GlyphRasterizer.rasterizePaint(paint, request),
            ).value
            val second = assertIs<RasterResult.Success<Rgba8Image>>(
                GlyphRasterizer.rasterizePaint(paint, request),
            ).value
            assertEquals(first, second, "repeated rasterization must be identical")
            dumps["emoji-two-64.ppm"] = Dump(
                bytes = ppm(first),
                note = "RGB over white: out = (c * a + 255 * (255 - a) + 127) / 255",
            )
        }

        val manifest = buildString {
            appendLine("# Raster dump manifest")
            appendLine()
            appendLine("- git commit: `${gitCommit()}`")
            appendLine("- fonts: EmojiTwoCOLRv0.ttf, LiberationSans-Regular.ttf")
            appendLine("- pixels per em: 64")
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

    private fun pgm(image: A8Image): ByteArray {
        val header = "P5\n${image.width} ${image.height}\n255\n".toByteArray(Charsets.US_ASCII)
        return header + image.copyPixels()
    }

    private fun ppm(image: Rgba8Image): ByteArray {
        val header = "P6\n${image.width} ${image.height}\n255\n".toByteArray(Charsets.US_ASCII)
        val pixels = image.copyPixels()
        val composited = ByteArray(image.width * image.height * 3)
        for (index in 0 until image.width * image.height) {
            val alpha = pixels[index * 4 + 3].toInt() and 0xFF
            for (channel in 0 until 3) {
                val color = pixels[index * 4 + channel].toInt() and 0xFF
                composited[index * 3 + channel] =
                    ((color * alpha + 255 * (255 - alpha) + 127) / 255).toByte()
            }
        }
        return header + composited
    }

    private fun gitCommit(): String {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{40}"))) {
            "Could not identify the dumped commit: $output"
        }
        return output
    }

    private class Dump(
        val bytes: ByteArray,
        val note: String = "",
    )
}
