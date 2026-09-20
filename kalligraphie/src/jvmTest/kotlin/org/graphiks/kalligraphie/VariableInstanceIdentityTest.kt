package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontGeometryParameters
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates

class VariableInstanceIdentityTest {
    // Shared OFL variable TrueType fixture (wght 100/100/900). See test-fixtures/fonts/noto-sans-jp.
    private val bytes: ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"),
    ).readBytes()

    @Test
    fun designSelectionIsRebuiltIntoNormalizedInstanceIdentity() {
        val instance = success(openFace().instantiate(
            FontInstanceDescriptor(variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", 500f)))),
        ))
        val normalized = instance.key.geometry.normalizedAxes
        assertEquals(listOf("wght"), normalized.map { it.tag })
        assertTrue(normalized.single().value > 0f && normalized.single().value <= 1f)
    }

    @Test
    fun namedInstanceResolvesToItsNormalizedCoordinates() {
        val named = openFace().namedInstances().first { it.coordinates.coordinates.single().value == 500f }
        val instance = success(openFace().instantiate(FontInstanceDescriptor(variation = named.coordinates)))
        val normalized = instance.key.geometry.normalizedAxes
        assertEquals(listOf("wght"), normalized.map { it.tag })
        assertTrue(normalized.single().value > 0f && normalized.single().value <= 1f)
    }

    @Test
    fun defaultValuedAxisNormalizesToZero() {
        val instance = success(openFace().instantiate(
            FontInstanceDescriptor(variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", 100f)))),
        ))
        assertEquals(0f, instance.key.geometry.normalizedAxes.single().value)
    }

    @Test
    fun unknownAxisIsATypedFailure() {
        val result = openFace().instantiate(
            FontInstanceDescriptor(variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wdth", 100f)))),
        )
        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.unknown-axis", failure.error.code)
    }

    @Test
    fun ambiguousRequestIsATypedFailure() {
        val result = openFace().instantiate(
            FontInstanceDescriptor(
                geometry = FontGeometryParameters(listOf(FontAxisCoordinate("wght", 0.5f))),
                variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", 500f))),
            ),
        )
        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.ambiguous-request", failure.error.code)
    }

    @Test
    fun defaultSelectionStillInstantiates() {
        val instance = success(openFace().instantiate(FontInstanceDescriptor()))
        assertTrue(instance.key.geometry.normalizedAxes.isEmpty())
    }

    @Test
    fun variationAxesAreExposed() {
        val axes = openFace().variationAxes()
        assertEquals(listOf("wght"), axes.map { it.tag })
        val wght = axes.single()
        assertEquals(100f, wght.minValue)
        assertEquals(100f, wght.defaultValue)
        assertEquals(900f, wght.maxValue)
    }

    @Test
    fun clampDiagnosticSurfacesThroughInstantiate() {
        val result = openFace().instantiate(
            FontInstanceDescriptor(variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", 5000f)))),
        )
        val success = assertIs<FontOperationResult.Success<FontInstance>>(result)
        assertTrue(success.diagnostics.any { it.code == "font.variation.axis-clamped" })
        assertEquals(1f, success.value.key.geometry.normalizedAxes.single().value)
    }

    private fun openFace(): FontFace {
        val catalog = success(Kalligraphie.embedded(bytes, FontSourceProvenance("NotoSansJP-VerticalFixture")))
        val faceId = catalog.faces.single().id
        return success(catalog.resolveFace(faceId, FontAccessRequirementsSnapshot.layoutOnly()))
    }

    private fun <T> success(result: FontOperationResult<T>): T = when (result) {
        is FontOperationResult.Success -> result.value
        is FontOperationResult.Failure -> error("Unexpected failure: ${result.error}")
        is FontOperationResult.Cancelled -> error("Unexpected cancellation")
    }
}
