package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmbeddedFontFaceContractTest {
    @Test
    fun opensTheLiberationSansTrueTypeFaceThroughThePublishedFacade() {
        val result = Kalligraphie.embedded(
            sourceBytes = fixtureBytes(),
            provenance = FontSourceProvenance(declaredName = "Liberation Sans Regular"),
        )
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(result).value
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(FontFaceRequest(faceIndex = 0), FontAccessRequirementsSnapshot.layoutOnly()),
        ).value

        assertEquals("Liberation Sans", face.metadata.familyName)
        assertEquals("Regular", face.metadata.styleName)
        assertEquals(2048, face.metadata.unitsPerEm)
        assertEquals(2620, face.metadata.glyphCount)
        assertTrue(face.id.value.isNotBlank())
    }

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "fixture font resource is missing"
        }.use { it.readBytes() }
}
