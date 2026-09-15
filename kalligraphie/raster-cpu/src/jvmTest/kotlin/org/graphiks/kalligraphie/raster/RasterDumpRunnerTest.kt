package org.graphiks.kalligraphie.raster

import java.nio.file.Files
import java.nio.file.Path
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import kotlin.test.Test
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
            val image = assertIs<RasterResult.Success<A8Image>>(
                GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm = 64.0)),
            ).value
            images["liberation-a-64.pgm"] = pgm(image)
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
            Files.write(target, bytes)
            Files.write(second, bytes)
            assertTrue(Files.mismatch(target, second) == -1L, "repeated dumps must be identical for $name")
            Files.delete(second)
        }
        Files.writeString(outputDirectory.resolve("manifest.md"), manifest)
    }

    private fun pgm(image: A8Image): ByteArray {
        val header = "P5\n${image.width} ${image.height}\n255\n".toByteArray(Charsets.US_ASCII)
        return header + image.copyPixels()
    }

    private fun gitCommit(): String =
        ProcessBuilder("git", "rev-parse", "HEAD").start().inputStream.bufferedReader().readText().trim()
}
