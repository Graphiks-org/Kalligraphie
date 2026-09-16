package org.graphiks.kalligraphie.raster.logo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalligraphieLogoTest {
    @Test
    fun rendersABadgedLockupWithTransparentBackground() {
        KalligraphieLogoFonts.open().use { fonts ->
            val render = KalligraphieLogo.render(fonts, KalligraphieLogo.Ink)

            assertTrue(render.image.width in 1_150..1_250, "width was ${render.image.width}")
            assertTrue(render.image.height in 200..600, "height was ${render.image.height}")

            val pixels = render.image.copyPixels()
            val corner = 0
            assertEquals(0, pixels[corner + 3].toInt() and 0xFF, "the corner must stay transparent")

            val stride = render.image.width * 4
            val centreRow = render.image.height / 2
            var opaque = 0
            for (x in 0 until render.image.width) {
                if ((pixels[centreRow * stride + x * 4 + 3].toInt() and 0xFF) > 200) opaque += 1
            }
            assertTrue(opaque > 0, "the lockup must paint ink on its centre row")
        }
    }

    @Test
    fun lightAndDarkVariantsShareOneAlphaChannel() {
        KalligraphieLogoFonts.open().use { fonts ->
            val light = KalligraphieLogo.render(fonts, KalligraphieLogo.Ink)
            val dark = KalligraphieLogo.render(fonts, KalligraphieLogo.Paper)

            assertEquals(light.image.width, dark.image.width)
            assertEquals(light.image.height, dark.image.height)

            val lightPixels = light.image.copyPixels()
            val darkPixels = dark.image.copyPixels()
            assertTrue(
                lightPixels.indices.filter { index -> index % 4 == 3 }
                    .all { index -> lightPixels[index] == darkPixels[index] },
                "both variants must share one alpha channel",
            )
            assertTrue(
                lightPixels.indices.filter { index -> index % 4 != 3 }
                    .any { index -> lightPixels[index] != darkPixels[index] },
                "the two variants must differ in colour",
            )
        }
    }

    @Test
    fun paintsTheFilledSquareAndKnocksTheLetterOut() {
        KalligraphieLogoFonts.open().use { fonts ->
            val pixels = KalligraphieLogo.render(fonts, KalligraphieLogo.Ink).image.copyPixels()

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

            assertTrue(ink > 0, "the square and the wordmark must paint ink")
            assertTrue(paper > 0, "the badge letter must be knocked out in the paper colour")
        }
    }
}
