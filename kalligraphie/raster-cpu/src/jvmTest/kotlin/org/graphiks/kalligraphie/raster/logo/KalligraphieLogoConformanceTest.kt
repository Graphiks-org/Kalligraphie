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
    fun theBadgeLightVariantStillMatchesItsSealedFingerprint() {
        KalligraphieLogoFonts.open().use { fonts ->
            assertEquals(
                "d6abacb7e942d7f487679b1b94f08b567c8168b9a3930888d27b695909912ef7",
                KalligraphieLogo.renderBadge(fonts, KalligraphieLogo.Ink).pixelDigest(),
            )
        }
    }

    @Test
    fun theBadgeDarkVariantStillMatchesItsSealedFingerprint() {
        KalligraphieLogoFonts.open().use { fonts ->
            assertEquals(
                "554cb2751a85f96f8c0be0510d14f85d3998e778db19066c5b2cdd4273adaa62",
                KalligraphieLogo.renderBadge(fonts, KalligraphieLogo.Paper).pixelDigest(),
            )
        }
    }

    @Test
    fun theWordmarkLightVariantStillMatchesItsSealedFingerprint() {
        KalligraphieLogoFonts.open().use { fonts ->
            assertEquals(
                "debc6ee75877e81e00fba06a8b8b2d891f5c227fd04b3000827ace690399823f",
                KalligraphieLogo.renderWordmark(fonts, KalligraphieLogo.Ink).pixelDigest(),
            )
        }
    }

    @Test
    fun theWordmarkDarkVariantStillMatchesItsSealedFingerprint() {
        KalligraphieLogoFonts.open().use { fonts ->
            assertEquals(
                "c7fd5505dd5477541e12383db5f411e34450b9e9a9cc636ebcb5b39f0744e717",
                KalligraphieLogo.renderWordmark(fonts, KalligraphieLogo.Paper).pixelDigest(),
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

        assertEquals(4, recorded.size, "the manifest must list exactly the four logo variants")
        assertEquals(
            setOf(
                "kalligraphie-logo-light.png",
                "kalligraphie-logo-dark.png",
                "kalligraphie-wordmark-light.png",
                "kalligraphie-wordmark-dark.png",
            ),
            recorded.keys,
        )
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
        val directory = rasterRepositoryRoot().resolve(
            "kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes",
        )
        val provenance = Files.readString(directory.resolve("PROVENANCE.md"))
        val fontDigest = sha256(Files.readAllBytes(directory.resolve("GreatVibes-Regular.ttf")))
        val licenceDigest = sha256(Files.readAllBytes(directory.resolve("OFL.txt")))

        assertTrue(
            Regex("""`GreatVibes-Regular\.ttf`: `$fontDigest`""").containsMatchIn(provenance),
            "the provenance record must attribute the bundled font digest to the font file",
        )
        assertTrue(
            Regex("""\[[^\]]*\]\(OFL\.txt\).*`$licenceDigest`""").containsMatchIn(provenance),
            "the provenance record must attribute the bundled licence digest to the licence file",
        )
    }

    private fun renderedPixelDigest(name: String): String {
        val (part, ink) = when (name) {
            "kalligraphie-logo-light.png" -> KalligraphieLogo::renderBadge to KalligraphieLogo.Ink
            "kalligraphie-logo-dark.png" -> KalligraphieLogo::renderBadge to KalligraphieLogo.Paper
            "kalligraphie-wordmark-light.png" -> KalligraphieLogo::renderWordmark to KalligraphieLogo.Ink
            "kalligraphie-wordmark-dark.png" -> KalligraphieLogo::renderWordmark to KalligraphieLogo.Paper
            else -> error("unmapped logo variant $name")
        }
        KalligraphieLogoFonts.open().use { fonts ->
            return part(fonts, ink).pixelDigest()
        }
    }

    private companion object {
        val ENTRY = Regex(
            """^- (kalligraphie-(?:logo|wordmark)-(?:light|dark)\.png): sha256=([0-9a-f]{64}) bytes=\d+ rgba-sha256=([0-9a-f]{64})$""",
        )
    }
}
