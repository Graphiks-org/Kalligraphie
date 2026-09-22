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
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.unicode.TextSnapshots
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HarfBuzzVariationLocationTest {
    private val backends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        backends.forEach { assertIs<FontOperationResult.Success<Unit>>(it.close()) }
    }

    /**
     * A non-default instance either applies its location or fails closed — it never shapes silently
     * unvaried. With the republished binding (Branch A) `shape()` returns the applied `660` at
     * `wght = 1.0`; against a binding that cannot apply the location it returns the typed
     * `font.shaping-variation-unsupported`. A silently-unvaried `574` fails either arm.
     *
     * Branch A cannot reach the failure arm through this native binding, so that half of the
     * invariant is pinned branch-independently by the `false`-flag double in
     * [VariableLocationPlumbingTest] (`anUnsupportedBindingRejectsANonDefaultLocation`), which never
     * loads a native binding.
     */
    @Test
    fun aNonDefaultInstanceAppliesItsLocationOrFailsClosed() {
        val backend = HarfBuzzShapingBackend.open().successValue().also(backends::add)
        when (val result = backend.shape(request(nonDefaultInstance()))) {
            is FontOperationResult.Failure ->
                assertEquals("font.shaping-variation-unsupported", result.error.code)
            is FontOperationResult.Success ->
                assertEquals(listOf(660f), result.value.glyphs.map { it.xAdvance.value })
            is FontOperationResult.Cancelled -> error("A non-default instance must not cancel.")
        }
    }

    private fun nonDefaultInstance(): FontInstance {
        val source = FontSource(
            fixtureBytes("/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"),
            FontSourceProvenance("Noto Sans JP vertical fixture"),
        )
        val catalog = Kalligraphie.embedded(listOf(source)).successValue()
        val face = catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()).successValue()
        val geometry = FontGeometryParameters(normalizedAxes = listOf(FontAxisCoordinate("wght", 1.0f)))
        return face.instantiate(FontInstanceDescriptor(layoutSize = LayoutUnit(1000f), geometry = geometry)).successValue()
    }

    private fun request(font: FontInstance): ShapingRequest {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("A".toCharArray())),
        ).snapshot
        val range = snapshot.range
        val graphemeRanges = snapshot.scalars.indices.map { scalar ->
            TextRange(snapshot.textIndexAtScalarBoundary(scalar), snapshot.textIndexAtScalarBoundary(scalar + 1))
        }
        return ShapingRequest(
            snapshot = snapshot,
            itemRange = range,
            contextRange = range,
            font = font,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            bot = true,
            eot = true,
            featurePolicy = HarfBuzzShapingBackend.pinnedFeaturePolicy,
            features = emptyList(),
            graphemeClusters = graphemeRanges,
        )
    }

    private fun fixtureBytes(resource: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value
}
