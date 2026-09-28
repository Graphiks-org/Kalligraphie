package org.graphiks.kalligraphie

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult

class FontDirectoryWoffCaptureTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/fonts/woff-ibm-plex/$name")).use { it!!.readBytes() }

    @Test
    fun discoversAndDecodesBothContainers() {
        val root = createTempDirectory("woff-capture")
        Files.write(root.resolve("plex.woff"), fixture("IBMPlexSans-Regular.woff"))
        Files.write(root.resolve("plex.woff2"), fixture("IBMPlexSans-Regular.woff2"))
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            FontDirectoryCatalog.open(FontDirectoryCatalogOptions(roots = listOf(root.toString()))),
        ).value
        assertEquals(2, catalog.faces.size)
    }

    @Test
    fun anInvalidWoffIsRejectedWithoutFailingTheCapture() {
        val root = createTempDirectory("woff-capture-bad")
        Files.write(root.resolve("broken.woff"), "wOFF".encodeToByteArray())
        Files.write(root.resolve("plex.woff"), fixture("IBMPlexSans-Regular.woff"))
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            FontDirectoryCatalog.open(FontDirectoryCatalogOptions(roots = listOf(root.toString()))),
        ).value
        assertEquals(1, catalog.faces.size)
    }
}
