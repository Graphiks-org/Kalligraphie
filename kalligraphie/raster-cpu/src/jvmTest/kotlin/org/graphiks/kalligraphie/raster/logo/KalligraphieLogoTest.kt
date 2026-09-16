package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.GlyphColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalligraphieLogoTest {
    @Test
    fun rendersTheBadgeAsASquareCanvas() {
        KalligraphieLogoFonts.open().use { fonts ->
            val image = KalligraphieLogo.renderBadge(fonts, KalligraphieLogo.Ink).image

            assertEquals(512, image.width, "the badge canvas must be exactly 512 px wide")
            assertEquals(512, image.height, "the badge canvas must be exactly 512 px tall")

            val pixels = image.copyPixels()
            assertEquals(0, pixels[3].toInt() and 0xFF, "the corner must stay transparent")

            val stride = image.width * 4
            val centreRow = image.height / 2
            var opaque = 0
            for (x in 0 until image.width) {
                if ((pixels[centreRow * stride + x * 4 + 3].toInt() and 0xFF) > 200) opaque += 1
            }
            assertTrue(opaque > 0, "the badge must paint ink on its centre row")

            var minX = image.width
            var minY = image.height
            var maxX = -1
            var maxY = -1
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    if ((pixels[(y * image.width + x) * 4 + 3].toInt() and 0xFF) == 0) continue
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            val left = minX
            val right = image.width - 1 - maxX
            val top = minY
            val bottom = image.height - 1 - maxY
            assertTrue(abs(left - right) <= 1, "the badge must be horizontally centred (left=$left right=$right)")
            assertTrue(abs(top - bottom) <= 1, "the badge must be vertically centred (top=$top bottom=$bottom)")
        }
    }

    @Test
    fun rendersTheWordmarkWithATransparentBackground() {
        KalligraphieLogoFonts.open().use { fonts ->
            val image = KalligraphieLogo.renderWordmark(fonts, KalligraphieLogo.Ink).image

            assertTrue(image.width in 1_150..1_250, "width was ${image.width}")
            assertTrue(image.height in 200..600, "height was ${image.height}")

            val pixels = image.copyPixels()
            val corner = 0
            assertEquals(0, pixels[corner + 3].toInt() and 0xFF, "the corner must stay transparent")

            val stride = image.width * 4
            val centreRow = image.height / 2
            var opaque = 0
            for (x in 0 until image.width) {
                if ((pixels[centreRow * stride + x * 4 + 3].toInt() and 0xFF) > 200) opaque += 1
            }
            assertTrue(opaque > 0, "the wordmark must paint ink on its centre row")
        }
    }

    @Test
    fun lightAndDarkVariantsShareOneAlphaChannel() {
        KalligraphieLogoFonts.open().use { fonts ->
            val parts: Map<String, (KalligraphieLogoFonts, GlyphColor) -> LogoRender> = linkedMapOf(
                "badge" to KalligraphieLogo::renderBadge,
                "wordmark" to KalligraphieLogo::renderWordmark,
            )
            for ((part, render) in parts) {
                val light = render(fonts, KalligraphieLogo.Ink)
                val dark = render(fonts, KalligraphieLogo.Paper)

                assertEquals(light.image.width, dark.image.width, "the $part dimensions must match")
                assertEquals(light.image.height, dark.image.height, "the $part dimensions must match")

                val lightPixels = light.image.copyPixels()
                val darkPixels = dark.image.copyPixels()
                assertTrue(
                    lightPixels.indices.filter { index -> index % 4 == 3 }
                        .all { index -> lightPixels[index] == darkPixels[index] },
                    "both $part variants must share one alpha channel",
                )
                assertTrue(
                    lightPixels.indices.filter { index -> index % 4 != 3 }
                        .any { index -> lightPixels[index] != darkPixels[index] },
                    "the two $part variants must differ in colour",
                )
            }
        }
    }

    @Test
    fun paintsTheFilledSquareAndKnocksTheLetterOut() {
        KalligraphieLogoFonts.open().use { fonts ->
            val pixels = KalligraphieLogo.renderBadge(fonts, KalligraphieLogo.Ink).image.copyPixels()

            var ink = 0
            var paper = 0
            for (index in pixels.indices step 4) {
                if ((pixels[index + 3].toInt() and 0xFF) < 250) continue
                val red = pixels[index].toInt() and 0xFF
                val green = pixels[index + 1].toInt() and 0xFF
                val blue = pixels[index + 2].toInt() and 0xFF
                if (red == 0 && green == 0 && blue == 0) ink += 1
                if (red == 255 && green == 255 && blue == 255) paper += 1
            }

            assertTrue(ink > 0, "the filled square must paint ink")
            assertTrue(paper > 0, "the badge letter must be knocked out in the paper colour")
        }
    }

    @Test
    fun rendersTheWordmarkUpright() {
        KalligraphieLogoFonts.open().use { fonts ->
            val image = KalligraphieLogo.renderWordmark(fonts, KalligraphieLogo.Ink).image
            val pixels = image.copyPixels()
            val width = image.width
            val height = image.height
            fun alpha(x: Int, y: Int): Int = pixels[(y * width + x) * 4 + 3].toInt() and 0xFF

            var ink = 0
            var weighted = 0.0
            for (y in 0 until height) {
                var row = 0
                for (x in 0 until width) if (alpha(x, y) > 128) row += 1
                ink += row
                weighted += row * (y + 0.5)
            }
            assertTrue(ink > 0, "the wordmark must paint ink")
            val centroid = weighted / (ink * height)

            // The rasterizer keeps the source orientation, so the composed image must be
            // flipped exactly once. The upright script sits at about 0.542; a vertically
            // mirrored render, which is what an unflipped composite produces, lands at 0.458.
            assertTrue(centroid > 0.52, "the wordmark must not be mirrored vertically (centroid $centroid)")
        }
    }
}
