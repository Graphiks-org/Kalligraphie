package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FontDiagnosticsTest {
    @Test
    fun successDefensivelyCopiesAndSortsPublishedDiagnostics() {
        val published = mutableListOf(
            diagnostic(code = "font.z", message = "later"),
            diagnostic(code = "font.a", message = "first"),
        )

        val result = FontOperationResult.Success(value = "ok", diagnostics = published)
        published.clear()
        published += diagnostic(code = "font.mutated", message = "mutated")

        assertEquals(listOf("font.a", "font.z"), result.diagnostics.map { it.code })
        assertEquals(listOf("first", "later"), result.diagnostics.map { it.message })
    }

    @Test
    fun failureDefensivelyCopiesAndSortsPublishedDiagnostics() {
        val published = mutableListOf(
            diagnostic(code = "font.z", message = "later"),
            diagnostic(code = "font.a", message = "first"),
        )

        val result = FontOperationResult.Failure(
            error = FontError.InvalidFontData("bad font"),
            diagnostics = published,
        )
        published.removeAt(0)

        assertEquals(listOf("font.a", "font.z"), result.diagnostics.map { it.code })
        assertEquals(2, result.diagnostics.size)
    }

    @Test
    fun cancelledDefensivelyCopiesAndSortsPublishedDiagnostics() {
        val published = mutableListOf(
            diagnostic(code = "font.z", message = "later"),
            diagnostic(code = "font.a", message = "first"),
        )

        val result = FontOperationResult.Cancelled(diagnostics = published)
        published.clear()

        assertEquals(listOf("font.a", "font.z"), result.diagnostics.map { it.code })
        assertEquals(2, result.diagnostics.size)
    }

    @Test
    fun successRetainsDataClassValueSemanticsAfterCanonicalizingDiagnostics() {
        val unsorted = listOf(
            diagnostic(code = "font.z", message = "later"),
            diagnostic(code = "font.a", message = "first"),
        )
        val sameValue = FontOperationResult.Success(value = "ok", diagnostics = unsorted.reversed())

        val result = FontOperationResult.Success(value = "ok", diagnostics = unsorted)
        val (value, diagnostics) = result

        assertEquals("ok", value)
        assertEquals(listOf("font.a", "font.z"), diagnostics.map { it.code })
        assertEquals(sameValue, result)
        assertEquals(sameValue.hashCode(), result.hashCode())
        assertTrue(result.toString().contains("Success"))
        assertNotEquals(
            result,
            result.copy(diagnostics = listOf(diagnostic(code = "font.b", message = "different"))),
        )
    }

    @Test
    fun successHashCodeMatchesDataClassSemanticsForNullablePayloads() {
        val diagnostics = listOf(
            diagnostic(code = "font.z", message = "later"),
            diagnostic(code = "font.a", message = "first"),
        )

        val result = FontOperationResult.Success<String?>(value = null, diagnostics = diagnostics)

        assertEquals(31 * 0 + diagnostics.sortedDiagnostics().hashCode(), result.hashCode())
    }

    private fun diagnostic(code: String, message: String) = FontDiagnostic(
        code = code,
        severity = FontDiagnosticSeverity.ERROR,
        location = FontDiagnosticLocation.Source,
        message = message,
    )
}
