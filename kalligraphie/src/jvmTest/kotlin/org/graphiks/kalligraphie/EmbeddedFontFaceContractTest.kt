package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceRecord
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.FontSourceProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class EmbeddedFontFaceContractTest {
    @Test
    fun capturedFacesCannotBeMutatedByACatalogCaller() {
        val liberation = FontSource(
            sourceBytes = fixtureBytes("/fonts/liberation/LiberationSans-Regular.ttf"),
            provenance = FontSourceProvenance(declaredName = "Liberation Sans Regular"),
        )
        val bungee = FontSource(
            sourceBytes = fixtureBytes("/fonts/bungee-color/BungeeColor-Regular.ttf"),
            provenance = FontSourceProvenance(declaredName = "Bungee Color Regular"),
        )
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(listOf(liberation, bungee)),
        ).value

        @Suppress("UNCHECKED_CAST")
        val attemptedMutation = catalog.faces as MutableList<FontFaceRecord>

        assertFailsWith<UnsupportedOperationException> { attemptedMutation.removeAt(0) }
        assertEquals(listOf(liberation.id, bungee.id), catalog.faces.map { it.id.source })
    }

    @Test
    fun loadsEveryEmbeddedFaceFromAMultiSourceCatalogThroughThePublishedFacade() {
        val liberation = FontSource(
            sourceBytes = fixtureBytes("/fonts/liberation/LiberationSans-Regular.ttf"),
            provenance = FontSourceProvenance(declaredName = "Liberation Sans Regular"),
        )
        val bungee = FontSource(
            sourceBytes = fixtureBytes("/fonts/bungee-color/BungeeColor-Regular.ttf"),
            provenance = FontSourceProvenance(declaredName = "Bungee Color Regular"),
        )

        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(listOf(liberation, bungee)),
        ).value

        assertEquals(listOf(liberation.id, bungee.id), catalog.faces.map { it.id.source })
        assertEquals(2, catalog.faces.size)
    }

    @Test
    fun opensTheLiberationSansTrueTypeFaceThroughThePublishedFacade() {
        val result = Kalligraphie.embedded(
            sourceBytes = fixtureBytes(),
            provenance = FontSourceProvenance(declaredName = "Liberation Sans Regular"),
        )
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(result).value
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()),
        ).value

        assertEquals("Liberation Sans", face.metadata.familyName)
        assertEquals("Regular", face.metadata.styleName)
        assertEquals(2048, face.metadata.unitsPerEm)
        assertEquals(2620, face.metadata.glyphCount)
        assertEquals(0, face.id.faceIndex)
        val sourceId = assertIs<FontSourceId.Portable>(face.id.source)
        assertEquals(64, sourceId.contentDigest.value.length)
    }

    private fun fixtureBytes(path: String = "/fonts/liberation/LiberationSans-Regular.ttf"): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) {
            "fixture font resource $path is missing"
        }.use { it.readBytes() }
}
