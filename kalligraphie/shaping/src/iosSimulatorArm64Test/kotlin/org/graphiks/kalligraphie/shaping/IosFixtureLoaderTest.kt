package org.graphiks.kalligraphie.shaping

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B3: proves the shared fixture corpus is readable inside the iOS simulator test binary.
 *
 * The frozen byte lengths are the exact on-disk sizes of the `test-fixtures/fonts/...` sources; the
 * embedded corpus must reproduce every source length and the sfnt scaler-type magic, so a stale or
 * truncated embedding fails loudly instead of silently shaping different bytes.
 */
class IosFixtureLoaderTest {
    @Test
    fun decodesEveryEmbeddedFixtureWithItsSourceLengthAndSfntMagic() {
        val expectedLengths = mapOf(
            "/fonts/dejavu/DejaVuSans.ttf" to 757_076,
            "/fonts/liberation/LiberationSans-Regular.ttf" to 410_712,
            "/fonts/amiri/Amiri-Regular.ttf" to 431_116,
            "/fonts/gdef-kern/GdefKerningFixture.ttf" to 1_772,
            "/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf" to 3_408,
        )

        assertEquals(expectedLengths.keys, IosFixtureCorpus.paths)

        expectedLengths.forEach { (resourcePath, expectedLength) ->
            val bytes = IosFixtureLoader.fixtureBytes(resourcePath)
            assertEquals(expectedLength, bytes.size, "length mismatch for $resourcePath")
            // sfnt scaler type 0x00010000 big-endian: every fixture carries TrueType outlines.
            assertContentEquals(
                byteArrayOf(0x00, 0x01, 0x00, 0x00),
                bytes.copyOfRange(0, 4),
                "sfnt magic mismatch for $resourcePath",
            )
        }
    }

    @Test
    fun returnsTheSameCachedArrayAcrossCalls() {
        val first = IosFixtureLoader.fixtureBytes("/fonts/dejavu/DejaVuSans.ttf")
        val second = IosFixtureLoader.fixtureBytes("/fonts/dejavu/DejaVuSans.ttf")
        assertTrue(first === second, "the loader must decode each fixture once")
    }
}
