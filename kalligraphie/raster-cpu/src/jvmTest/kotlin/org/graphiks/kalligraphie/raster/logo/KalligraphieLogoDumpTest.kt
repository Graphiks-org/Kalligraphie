package org.graphiks.kalligraphie.raster.logo

import java.nio.file.Files
import java.nio.file.Path
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.raster.Rgba8Image
import org.graphiks.kalligraphie.raster.rasterRepositoryRoot
import org.graphiks.kalligraphie.raster.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalligraphieLogoDumpTest {
    @Test
    fun writesTheLogoAssetsOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_LOGO") != "true") return
        val assets = rasterRepositoryRoot().resolve("docs/assets")
        Files.createDirectories(assets)

        val commit = gitCommit()
        val outputs = linkedMapOf<String, LogoOutput>()
        KalligraphieLogoFonts.open().use { fonts ->
            outputs["kalligraphie-logo-light.png"] = render(fonts, KalligraphieLogo.Ink)
            outputs["kalligraphie-logo-dark.png"] = render(fonts, KalligraphieLogo.Paper)
            write(assets, outputs, commit)
        }

        for (output in outputs.values) {
            assertTrue(output.image.width > 0 && output.image.height > 0)
        }
    }

    private fun render(fonts: KalligraphieLogoFonts, ink: GlyphColor): LogoOutput {
        val first = KalligraphieLogo.render(fonts, ink)
        val second = KalligraphieLogo.render(fonts, ink)
        assertEquals(
            sha256(first.image.copyPixels()),
            sha256(second.image.copyPixels()),
            "repeated renders must produce identical pixels",
        )
        return LogoOutput(png = first.toPng(), image = first.image, pixelDigest = first.pixelDigest())
    }

    private fun write(
        assets: Path,
        outputs: Map<String, LogoOutput>,
        commit: String,
    ) {
        val manifest = buildString {
            appendLine("# Kalligraphie logo manifest")
            appendLine()
            appendLine("- dump-time HEAD: `$commit`")
            appendLine("- wordmark: `${KalligraphieLogo.Wordmark}`")
            appendLine("- fonts: ${KalligraphieLogoFonts.BadgeFontResource}, ${KalligraphieLogoFonts.WordmarkFontResource}")
            appendLine("- layout size: ${KalligraphieLogoFonts.LogoLayoutSize}")
            appendLine()
            appendLine("## Files")
            appendLine()
            outputs.forEach { (name, output) ->
                Files.write(assets.resolve(name), output.png)
                appendLine(
                    "- $name: sha256=${sha256(output.png)} bytes=${output.png.size} " +
                        "rgba-sha256=${output.pixelDigest}",
                )
            }
        }
        Files.writeString(assets.resolve("kalligraphie-logo.manifest.md"), manifest)
    }

    private fun gitCommit(): String {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{40}"))) {
            "Could not identify the dumped commit: $output"
        }
        return output
    }

    private class LogoOutput(
        val png: ByteArray,
        val image: Rgba8Image,
        val pixelDigest: String,
    )
}
