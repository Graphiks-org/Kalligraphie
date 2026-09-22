package org.graphiks.kalligraphie.shaping

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontGeometryParameters
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection

/**
 * Proves the bundled iOS HarfBuzz binding applies the instance's normalized variation location to
 * the prepared font (`hb_font_set_var_coords_normalized`, reached through the 3-arg platform
 * `prepare`) and reproduces the JVM/Android frozen advances on the shared
 * `NotoSansJP-VerticalFixture.ttf`: the `HVAR` advance (`660` at `wght = 900`), the default (`574`),
 * the non-endpoint `avar` transport (`622` at design `wght = 500`) and GPOS `kern` variation
 * (`563, 574` at `wght = 100` vs `653, 660` at `wght = 900`).
 *
 * This mirrors the four variation methods of [AndroidHarfBuzzDeviceTest] on the iOS simulator. The
 * variable fixture is embedded into the test binary by the `iosFixtureCorpus` Gradle task beside
 * the non-variable faces the golden suite reads (see [IosFixtureLoader]); an all-zero (explicit
 * design-default) location is asserted to be a no-op, so a binding that ignored the location could
 * not pass the non-default arms.
 */
class IosHarfBuzzVariationTest {
    private val backends = mutableListOf<ShapingBackend>()

    @Test
    fun harfBuzzAdvanceAtTheInstanceEqualsOurMetricsAtTheSameLocation() {
        val instance = instanceAtWght(1.0f)
        val run = backend().shape(request(text("A"), instance, ShapingDirection.LEFT_TO_RIGHT, OpenTypeScript("Latn"), "en", 0)).successValue()
        assertEquals(listOf(660f), run.glyphs.map { it.xAdvance.value })
        assertEquals(660, instance.advanceWidthOf(GlyphId(1)))
    }

    @Test
    fun theDefaultInstanceStillShapesAtTheDesignDefault() {
        val instance = instanceAtWght(null)
        val run = backend().shape(request(text("A"), instance, ShapingDirection.LEFT_TO_RIGHT, OpenTypeScript("Latn"), "en", 0)).successValue()
        assertEquals(listOf(574f), run.glyphs.map { it.xAdvance.value })
        assertEquals(574, instance.advanceWidthOf(GlyphId(1)))
    }

    @Test
    fun anExplicitDesignDefaultLocationIsANoOp() {
        val instance = instanceAtWght(0.0f)
        val run = backend().shape(request(text("A"), instance, ShapingDirection.LEFT_TO_RIGHT, OpenTypeScript("Latn"), "en", 0)).successValue()
        assertEquals(listOf(574f), run.glyphs.map { it.xAdvance.value })
        assertEquals(574, instance.advanceWidthOf(GlyphId(1)))
    }

    @Test
    fun gposKerningVariesWithTheLocation() {
        val backend = backend()
        val low = backend.shape(request(text("AA"), instanceAtWght(0.0f), ShapingDirection.LEFT_TO_RIGHT, OpenTypeScript("Latn"), "en", 0)).successValue()
        val high = backend.shape(request(text("AA"), instanceAtWght(1.0f), ShapingDirection.LEFT_TO_RIGHT, OpenTypeScript("Latn"), "en", 0)).successValue()
        assertEquals(listOf(563f, 574f), low.glyphs.map { it.xAdvance.value })
        assertEquals(listOf(653f, 660f), high.glyphs.map { it.xAdvance.value })
    }

    @Test
    fun avarTransportReachesHarfBuzzAtANonEndpointLocation() {
        val instance = instanceAtDesignWght(500f)
        val run = backend().shape(request(text("A"), instance, ShapingDirection.LEFT_TO_RIGHT, OpenTypeScript("Latn"), "en", 0)).successValue()
        assertEquals(listOf(622f), run.glyphs.map { it.xAdvance.value })
        assertEquals(622, instance.advanceWidthOf(GlyphId(1)))
    }

    @AfterTest
    fun closeOpenedBackends() {
        backends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    private fun backend(): ShapingBackend = HarfBuzzShapingBackend.open().successValue().also(backends::add)

    private fun instanceAtWght(normalized: Float?): FontInstance {
        val source = FontSource(
            IosFixtureLoader.fixtureBytes("/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"),
            FontSourceProvenance("Noto Sans JP vertical fixture"),
        )
        val catalog = Kalligraphie.embedded(listOf(source)).successValue()
        val face = catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()).successValue()
        val geometry = normalized?.let {
            FontGeometryParameters(normalizedAxes = listOf(FontAxisCoordinate("wght", it)))
        } ?: FontGeometryParameters()
        return face.instantiate(FontInstanceDescriptor(layoutSize = LayoutUnit(1000f), geometry = geometry)).successValue()
    }

    private fun instanceAtDesignWght(design: Float): FontInstance {
        val source = FontSource(
            IosFixtureLoader.fixtureBytes("/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"),
            FontSourceProvenance("Noto Sans JP vertical fixture"),
        )
        val catalog = Kalligraphie.embedded(listOf(source)).successValue()
        val face = catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()).successValue()
        return face.instantiate(
            FontInstanceDescriptor(
                layoutSize = LayoutUnit(1000f),
                variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", design))),
            ),
        ).successValue()
    }

    private fun FontInstance.advanceWidthOf(glyphId: GlyphId): Int =
        assertIs<FontOperationResult.Success<GlyphMetrics>>(metrics(glyphId)).value.advanceWidthDesignUnits
}
