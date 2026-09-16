package org.graphiks.kalligraphie.raster.logo

import java.nio.file.Files
import org.graphiks.kalligraphie.raster.rasterRepositoryRoot
import org.graphiks.kalligraphie.raster.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalligraphieLogoConformanceTest {
    private val assets = rasterRepositoryRoot().resolve("docs/assets")

    @Test
    fun theLightVariantStillMatchesItsSealedFingerprint() {
        KalligraphieLogoFonts.open().use { fonts ->
            val render = KalligraphieLogo.render(fonts, KalligraphieLogo.Ink)
            assertEquals(
                "84ca6d2856cd6218bba046e9d02c49676e074b198a0c0e9b72f7170506d33fad",
                render.pixelDigest(),
            )
        }
    }

    @Test
    fun theDarkVariantStillMatchesItsSealedFingerprint() {
        KalligraphieLogoFonts.open().use { fonts ->
            val render = KalligraphieLogo.render(fonts, KalligraphieLogo.Paper)
            assertEquals(
                "253dea65ea43c4e7fa72ac811f80d497eaf67e7a77a95d9704089e4bc54ee2ed",
                render.pixelDigest(),
            )
        }
    }

    @Test
    fun theCommittedImagesMatchTheManifest() {
        val manifest = Files.readString(assets.resolve("kalligraphie-logo.manifest.md"))
        val recorded = manifest.lines().mapNotNull { line ->
            ENTRY.matchEntire(line.trim())?.let { match ->
                match.groupValues[1] to (match.groupValues[2] to match.groupValues[3])
            }
        }.toMap()

        assertEquals(setOf("kalligraphie-logo-light.png", "kalligraphie-logo-dark.png"), recorded.keys)
        recorded.forEach { (name, digests) ->
            val bytes = Files.readAllBytes(assets.resolve(name))
            assertEquals(digests.first, sha256(bytes), "$name must match its manifest file digest")
            assertEquals(
                digests.second,
                renderedPixelDigest(name),
                "$name must still render to the pixels its manifest records",
            )
        }
    }

    @Test
    fun theGreatVibesFixtureMatchesItsProvenance() {
        val provenance = Files.readString(
            rasterRepositoryRoot().resolve(
                "kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes/PROVENANCE.md",
            ),
        )
        assertTrue(
            provenance.contains("8d509802186f1b51572531ecf313e8098f9a5bfdfaca93f0c9b34467f9982d15"),
            "the provenance record must pin the bundled font digest",
        )
        assertTrue(
            provenance.contains("61093a21f5e63dedf54222b3c09997e54c0fe43e3851d21386e02ddcbc246d49"),
            "the provenance record must pin the bundled licence digest",
        )
    }

    private fun renderedPixelDigest(name: String): String {
        KalligraphieLogoFonts.open().use { fonts ->
            val ink = if (name.endsWith("light.png")) KalligraphieLogo.Ink else KalligraphieLogo.Paper
            return KalligraphieLogo.render(fonts, ink).pixelDigest()
        }
    }

    private companion object {
        val ENTRY = Regex("""^- (kalligraphie-logo-(?:light|dark)\.png): sha256=([0-9a-f]{64}) bytes=\d+ rgba-sha256=([0-9a-f]{64})$""")
    }
}
