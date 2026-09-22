package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontGeometryParameters
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.LayoutUnit

class SyntheticGeometryInstantiationTest {
    @Test
    fun acceptsSyntheticGeometryDescriptors() {
        val instance = openSyntheticInstance(fixtureBytes(STATIC_FIXTURE), bold = true, italic = true)

        assertTrue(instance.key.geometry.syntheticBold)
        assertTrue(instance.key.geometry.syntheticItalic)
    }

    @Test
    fun reportsSyntheticOverAxisWhenTheFaceDeclaresAWghtAxis() {
        val face = openFace(fixtureBytes(VARIABLE_FIXTURE))

        val result = face.instantiate(
            FontInstanceDescriptor(
                layoutSize = LayoutUnit(2_048f),
                geometry = FontGeometryParameters(syntheticBold = true),
            ),
        )

        val success = assertIs<FontOperationResult.Success<FontInstance>>(result)
        assertTrue(success.diagnostics.any { it.code == "font.geometry.synthetic-over-axis" })
    }

    @Test
    fun doesNotReportSyntheticOverAxisWithoutAWghtAxis() {
        val face = openFace(fixtureBytes(STATIC_FIXTURE))

        val result = face.instantiate(
            FontInstanceDescriptor(
                layoutSize = LayoutUnit(2_048f),
                geometry = FontGeometryParameters(syntheticBold = true),
            ),
        )

        val success = assertIs<FontOperationResult.Success<FontInstance>>(result)
        assertTrue(success.diagnostics.none { it.code == "font.geometry.synthetic-over-axis" })
    }

    private fun openSyntheticInstance(bytes: ByteArray, bold: Boolean, italic: Boolean): FontInstance =
        assertIs<FontOperationResult.Success<FontInstance>>(
            openFace(bytes).instantiate(
                FontInstanceDescriptor(
                    layoutSize = LayoutUnit(2_048f),
                    geometry = FontGeometryParameters(syntheticBold = bold, syntheticItalic = italic),
                ),
            ),
        ).value

    private fun openFace(bytes: ByteArray): FontFace =
        assertIs<FontOperationResult.Success<FontFace>>(
            assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
                Kalligraphie.embedded(bytes, FontSourceProvenance("Synthetic geometry fixture")),
            ).value.let { catalog ->
                catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly())
            },
        ).value

    private fun fixtureBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "fixture font resource is missing: $path" }
            .use { it.readBytes() }

    private companion object {
        const val STATIC_FIXTURE = "/fonts/liberation/LiberationSans-Regular.ttf"
        const val VARIABLE_FIXTURE = "/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"
    }
}
