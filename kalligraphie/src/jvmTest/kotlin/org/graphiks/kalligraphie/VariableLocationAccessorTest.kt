@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontGeometryParameters
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.LayoutUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VariableLocationAccessorTest {
    @Test
    fun aDefaultInstanceReportsNoLocation() {
        assertEquals(emptyList(), instanceAt("wght", null).evaluatedLocation())
    }

    @Test
    fun anExplicitDefaultAxisIsReportedInFvarOrder() {
        assertEquals(listOf(0.0f), instanceAt("wght", 0.0f).evaluatedLocation())
    }

    @Test
    fun aNonDefaultAxisIsReportedInFvarOrder() {
        assertEquals(listOf(1.0f), instanceAt("wght", 1.0f).evaluatedLocation())
    }

    @Test
    fun aNonVariableFaceIgnoresTheLocation() {
        assertEquals(
            emptyList(),
            instance(
                resource = "/fonts/dejavu/DejaVuSans.ttf",
                declaredName = "DejaVu Sans",
                axisTag = "wght",
                normalized = 1.0f,
            ).evaluatedLocation(),
        )
    }

    private fun instanceAt(axisTag: String, normalized: Float?): FontInstance =
        instance("/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf", "Noto Sans JP vertical", axisTag, normalized)

    private fun instance(resource: String, declaredName: String, axisTag: String, normalized: Float?): FontInstance {
        val source = FontSource(fixtureBytes(resource), FontSourceProvenance(declaredName))
        val catalog = Kalligraphie.embedded(listOf(source)).successValue()
        val face = catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()).successValue()
        val geometry = normalized?.let {
            FontGeometryParameters(normalizedAxes = listOf(FontAxisCoordinate(axisTag, it)))
        } ?: FontGeometryParameters()
        return face.instantiate(FontInstanceDescriptor(layoutSize = LayoutUnit(1000f), geometry = geometry)).successValue()
    }

    private fun FontInstance.evaluatedLocation(): List<Float> =
        assertIs<FontOperationResult.Success<List<Float>>>(normalizedVariationLocation()).value

    private fun fixtureBytes(resource: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value
}
