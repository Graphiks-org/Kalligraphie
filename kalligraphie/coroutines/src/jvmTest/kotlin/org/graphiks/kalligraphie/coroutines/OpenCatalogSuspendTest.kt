package org.graphiks.kalligraphie.coroutines

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.graphiks.kalligraphie.FontDirectoryCatalog
import org.graphiks.kalligraphie.FontDirectoryCatalogOptions
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenCatalogSuspendTest {
    @Test
    fun suspendOpenMatchesTheSynchronousOpen() = runTest {
        withFontRoot { root ->
            val options = FontDirectoryCatalogOptions(listOf(root))

            val synchronous = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
                FontDirectoryCatalog.open(options),
            )
            val suspended = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
                KalligraphieCoroutines.open(options),
            )

            val synchronousFaces = synchronous.value.faces.map { it.id to it.metadata.familyName }
            val suspendedFaces = suspended.value.faces.map { it.id to it.metadata.familyName }
            assertEquals(synchronousFaces, suspendedFaces)
            assertEquals(
                synchronous.diagnostics.map { it.code }.sorted(),
                suspended.diagnostics.map { it.code }.sorted(),
            )
            assertTrue(synchronousFaces.isNotEmpty())
        }
    }

    @Test
    fun jobCancelledBeforeStartThrowsCarryingTheTypedCancelledCatalog() {
        val exception = captureCancellation { context ->
            val job = context[Job]!!
            job.cancel()
            withFontRoot { root ->
                KalligraphieCoroutines.open(FontDirectoryCatalogOptions(listOf(root)))
            }
        }

        assertIs<FontOperationResult.Cancelled>(exception.result)
    }
}
