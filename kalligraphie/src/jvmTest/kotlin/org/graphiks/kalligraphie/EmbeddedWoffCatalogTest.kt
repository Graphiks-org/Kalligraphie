package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance

class EmbeddedWoffCatalogTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/woff-ibm-plex/$name")).use { it!!.readBytes() }

    @Test
    fun createsACatalogFromWoffAndWoff2() {
        for (name in listOf("IBMPlexSans-Regular.woff", "IBMPlexSans-Regular.woff2")) {
            val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
                Kalligraphie.embedded(fixture(name), FontSourceProvenance(name)),
            ).value
            assertEquals(1, catalog.faces.size)
        }
    }

    @Test
    fun identicalContainersAreRejectedAsDuplicates() {
        val font = fixture("IBMPlexSans-Regular.woff")
        val result = Kalligraphie.embedded(
            listOf(
                FontSource(font, FontSourceProvenance("a")),
                FontSource(font, FontSourceProvenance("b")),
            ),
        )
        assertIs<FontOperationResult.Failure>(result)
    }
}
