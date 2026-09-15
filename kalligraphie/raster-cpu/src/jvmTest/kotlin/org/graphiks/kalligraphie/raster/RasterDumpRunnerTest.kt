package org.graphiks.kalligraphie.raster

import java.nio.file.Files
import java.nio.file.Path
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RasterDumpRunnerTest {
    @Test
    fun writesDeterministicDumpsOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_RASTER_DUMPS") != "true") return
        val outputDirectory = Path.of(
            checkNotNull(System.getenv("KALLIGRAPHIE_RASTER_DUMPS_OUTPUT")) {
                "KALLIGRAPHIE_RASTER_DUMPS_OUTPUT is required when dumps are enabled."
            },
        )
        require(outputDirectory.isAbsolute) { "KALLIGRAPHIE_RASTER_DUMPS_OUTPUT must be an absolute path." }
        Files.createDirectories(outputDirectory)

        val images = mutableMapOf<String, ByteArray>()
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
            images["liberation-a-64.pgm"] = pgm(first)
        }

        val manifest = buildString {
            appendLine("# Raster dump manifest")
            appendLine()
            appendLine("- git commit: `${gitCommit()}`")
            appendLine("- fonts: LiberationSans-Regular.ttf")
            appendLine("- pixels per em: 64")
            images.entries.sortedBy { entry -> entry.key }.forEach { entry ->
                appendLine("- ${entry.key}: sha256=${sha256(entry.value)} bytes=${entry.value.size}")
            }
        }

        for ((name, bytes) in images) {
            val target = outputDirectory.resolve(name)
            val second = outputDirectory.resolve("$name.second")
            try {
                Files.write(target, bytes)
                Files.write(second, bytes)
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

    private fun gitCommit(): String {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{40}"))) {
            "Could not identify the dumped commit: $output"
        }
        return output
    }
}
