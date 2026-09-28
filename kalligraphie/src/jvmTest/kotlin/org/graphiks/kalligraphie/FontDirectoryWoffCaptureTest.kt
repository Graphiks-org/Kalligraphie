package org.graphiks.kalligraphie

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontError
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

    @Test
    fun aFailedContainerDecodeIsChargedToTheExaminationBudget() {
        val root = createTempDirectory("woff-capture-budget")
        // "broken.woff" sorts before "plex.woff": its decode failure consumes the single allowed
        // examination, so the valid container is never examined and the capture is resource-limited.
        Files.write(root.resolve("broken.woff"), "wOFF".encodeToByteArray())
        Files.write(root.resolve("plex.woff"), fixture("IBMPlexSans-Regular.woff"))
        val result = FontDirectoryCatalog.open(
            FontDirectoryCatalogOptions(roots = listOf(root.toString()), maxFacesToExamine = 1),
        )
        assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun aContainerLargerThanTheSourceBudgetIsResourceLimited() {
        val root = createTempDirectory("woff-capture-large")
        Files.write(root.resolve("plex.woff"), fixture("IBMPlexSans-Regular.woff"))
        val result = FontDirectoryCatalog.open(
            FontDirectoryCatalogOptions(roots = listOf(root.toString()), maxSourceBytes = 16),
        )
        assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
    }
}
