@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.shaping

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
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.VerticalGlyphMetrics
import org.graphiks.kalligraphie.unicode.TextSnapshots
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HarfBuzzVariableLocationTest {
    private val backends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        backends.forEach { assertIs<FontOperationResult.Success<Unit>>(it.close()) }
    }

    /**
     * The exit criterion: our metric at the instance equals HarfBuzz's advance at the same
     * location. Audited outside the implementation (HarfBuzz 14.4.0):
     *   hb-shape NotoSansJP-VerticalFixture.ttf 'A' --variations=wght=900 -> ax 660
     *   hb-shape NotoSansJP-VerticalFixture.ttf 'A'                       -> ax 574
     */
    @Test
    fun harfBuzzAdvanceAtTheInstanceEqualsOurMetricsAtTheSameLocation() {
        val backend = backend()
        val instance = instanceAtWght(1.0f)

        val shaped = shape(backend, "A", instance)
        assertEquals(listOf(660f), shaped.glyphs.map { it.xAdvance.value })
        assertEquals(660, instance.metricsOf(GlyphId(1)).advanceWidthDesignUnits)
    }

    @Test
    fun theDefaultInstanceStillShapesAtTheDesignDefault() {
        val backend = backend()
        val instance = instanceAtWght(null)

        val shaped = shape(backend, "A", instance)
        assertEquals(listOf(574f), shaped.glyphs.map { it.xAdvance.value })
        assertEquals(574, instance.metricsOf(GlyphId(1)).advanceWidthDesignUnits)
    }

    /**
     * GPOS variation: the first of two kerned `A`s is 563 at wght=100 and 653 at wght=900, a
     * difference (90) larger than the plain advance difference (86), so the `kern` adjustment
     * itself varies with the location. A silently-unvaried shape would report 563 at both.
     */
    @Test
    fun gposKerningVariesWithTheLocation() {
        val backend = backend()

        val low = shape(backend, "AA", instanceAtWght(0.0f))
        val high = shape(backend, "AA", instanceAtWght(1.0f))

        assertEquals(listOf(563f, 574f), low.glyphs.map { it.xAdvance.value })
        assertEquals(listOf(653f, 660f), high.glyphs.map { it.xAdvance.value })
    }

    /**
     * `avar` transport at a non-endpoint. This fixture's `avar` maps design-normalized `0.5` to
     * `0.55999755859375`, so a design selection of `wght = 500` must reach HarfBuzz as the
     * post-`avar` value and advance `622`. A pre-/post-`avar` mix-up (shaping at normalized `0.5`)
     * yields a different advance; normalized `0.0`/`1.0` are `avar` fixed points and cannot detect
     * it. Audited outside the implementation (HarfBuzz 14.4.0, region is linear; fontTools agrees):
     *   hb-shape NotoSansJP-VerticalFixture.ttf 'A' --variations=wght=500 -> ax 622
     * The design selection is normalized through the face's own `fvar`/`avar` at `instantiate`,
     * so this also pins that the `avar` value (not the raw `fvar` normalization) is what arrives.
     */
    @Test
    fun avarTransportReachesHarfBuzzAtANonEndpointLocation() {
        val backend = backend()
        val instance = instanceAtDesignWght(500f)

        assertEquals(listOf(0.55999755859375f), instance.normalizedWghtLocation())
        val shaped = shape(backend, "A", instance)
        assertEquals(listOf(622f), shaped.glyphs.map { it.xAdvance.value })
        assertEquals(622, instance.metricsOf(GlyphId(1)).advanceWidthDesignUnits)
    }

    /**
     * The exit criterion's vertical half, advance only: a top-to-bottom-shaped `A`'s `yAdvance` equals
     * our `verticalMetrics()` advance at the same location. The engine normalizes HarfBuzz's negative
     * upward-positive advance through `toPhysicalVerticalCoordinate`, so both quantities are positive
     * downward-positive layout units. Audited outside the implementation (HarfBuzz 14.4.0):
     *   hb-shape NotoSansJP-VerticalFixture.ttf 'A' --direction=ttb                        -> ay -1000
     *   hb-shape NotoSansJP-VerticalFixture.ttf 'A' --direction=ttb --variations=wght=900   -> ay -1000
     *   hb-shape NotoSansJP-VerticalFixture.ttf 'A' --direction=ttb --variations=wght=500   -> ay -1000
     * This fixture has `vmtx['A'].advanceHeight = 1000` and no `VVAR`, so the advance is constant; the
     * cross-check pins route agreement, not variation. A shaped `yAdvance` is faithful here because the
     * single glyph has no GPOS vertical kern pair (the fixture carries only a horizontal `kern`).
     */
    @Test
    fun harfBuzzVerticalAdvanceAtTheInstanceEqualsOurMetricsAtTheSameLocation() {
        val backend = backend()

        val default = instanceAtWght(null)
        assertEquals(1000f, shape(backend, "A", default, ShapingDirection.TOP_TO_BOTTOM).glyphs.single().yAdvance.value)
        assertEquals(1000f, default.verticalMetricsOf(GlyphId(1)).advanceHeight.value)

        val heavy = instanceAtWght(1.0f)
        assertEquals(1000f, shape(backend, "A", heavy, ShapingDirection.TOP_TO_BOTTOM).glyphs.single().yAdvance.value)
        assertEquals(1000f, heavy.verticalMetricsOf(GlyphId(1)).advanceHeight.value)

        val mid = instanceAtDesignWght(500f)
        assertEquals(listOf(0.55999755859375f), mid.normalizedWghtLocation())
        assertEquals(1000f, shape(backend, "A", mid, ShapingDirection.TOP_TO_BOTTOM).glyphs.single().yAdvance.value)
        assertEquals(1000f, mid.verticalMetricsOf(GlyphId(1)).advanceHeight.value)
    }

    /**
     * The vertical location transport. `KalligraphieVarVVAR.ttf` has `vmtx['A'].advanceHeight = 1000`
     * and a `VVAR` advance-height delta of `+200` at the axis maximum, interpolated by the `(0,1,1)`
     * region scalar. Independently audited (fontTools 4.65.0 `VarStoreInstancer`; HarfBuzz 14.4.0):
     *   normalized 0.0 -> 1000 (hb ay -1000); 0.5 -> 1100 (hb ay -1100); 1.0 -> 1200 (hb ay -1200).
     * The advance therefore genuinely moves with the location; design equals normalized here
     * (`wght` 0/0/1000, no `avar`).
     */
    @Test
    fun theVerticalAdvanceVariesWithTheLocation() {
        val backend = backend()
        val fixture = "/fonts/kalligraphie-var-vvar/KalligraphieVarVVAR.ttf"

        val default = instanceAtNormalized(fixture, null)
        assertEquals(1000f, shape(backend, "A", default, ShapingDirection.TOP_TO_BOTTOM).glyphs.single().yAdvance.value)
        assertEquals(1000f, default.verticalMetricsOf(GlyphId(1)).advanceHeight.value)

        val half = instanceAtNormalized(fixture, 0.5f)
        assertEquals(1100f, shape(backend, "A", half, ShapingDirection.TOP_TO_BOTTOM).glyphs.single().yAdvance.value)
        assertEquals(1100f, half.verticalMetricsOf(GlyphId(1)).advanceHeight.value)

        val full = instanceAtNormalized(fixture, 1.0f)
        assertEquals(1200f, shape(backend, "A", full, ShapingDirection.TOP_TO_BOTTOM).glyphs.single().yAdvance.value)
        assertEquals(1200f, full.verticalMetricsOf(GlyphId(1)).advanceHeight.value)
    }

    private fun backend(): ShapingBackend = HarfBuzzShapingBackend.open().successValue().also(backends::add)

    private fun shape(
        backend: ShapingBackend,
        text: String,
        font: FontInstance,
        direction: ShapingDirection = ShapingDirection.LEFT_TO_RIGHT,
    ): ShapedGlyphRun {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(text.toCharArray())),
        ).snapshot
        val range = snapshot.range
        val graphemeRanges = snapshot.scalars.indices.map { scalar ->
            TextRange(snapshot.textIndexAtScalarBoundary(scalar), snapshot.textIndexAtScalarBoundary(scalar + 1))
        }
        return backend.shape(
            ShapingRequest(
                snapshot = snapshot,
                itemRange = range,
                contextRange = range,
                font = font,
                direction = direction,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                bot = true,
                eot = true,
                featurePolicy = HarfBuzzShapingBackend.pinnedFeaturePolicy,
                features = emptyList<OpenTypeFeature>(),
                graphemeClusters = graphemeRanges,
            ),
        ).successValue()
    }

    private fun instanceAtNormalized(fixture: String, normalized: Float?): FontInstance {
        val source = FontSource(fixtureBytes(fixture), FontSourceProvenance(fixture))
        val catalog = Kalligraphie.embedded(listOf(source)).successValue()
        val face = catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()).successValue()
        val geometry = normalized?.let {
            FontGeometryParameters(normalizedAxes = listOf(FontAxisCoordinate("wght", it)))
        } ?: FontGeometryParameters()
        return face.instantiate(
            FontInstanceDescriptor(layoutSize = LayoutUnit(1000f), geometry = geometry),
        ).successValue()
    }

    private fun instanceAtDesign(fixture: String, design: Float): FontInstance {
        val source = FontSource(fixtureBytes(fixture), FontSourceProvenance(fixture))
        val catalog = Kalligraphie.embedded(listOf(source)).successValue()
        val face = catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()).successValue()
        return face.instantiate(
            FontInstanceDescriptor(
                layoutSize = LayoutUnit(1000f),
                variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", design))),
            ),
        ).successValue()
    }

    private fun instanceAtWght(normalized: Float?): FontInstance =
        instanceAtNormalized("/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf", normalized)

    private fun instanceAtDesignWght(design: Float): FontInstance =
        instanceAtDesign("/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf", design)

    private fun FontInstance.normalizedWghtLocation(): List<Float> =
        assertIs<FontOperationResult.Success<List<Float>>>(normalizedVariationLocation()).value

    private fun FontInstance.metricsOf(glyphId: GlyphId): GlyphMetrics =
        assertIs<FontOperationResult.Success<GlyphMetrics>>(metrics(glyphId)).value

    private fun FontInstance.verticalMetricsOf(glyphId: GlyphId): VerticalGlyphMetrics =
        assertIs<FontOperationResult.Success<VerticalGlyphMetrics>>(verticalMetrics(glyphId)).value

    private fun fixtureBytes(resource: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value
}
