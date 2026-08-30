package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun publishedDiagnosticsRejectMutationThroughMutableListCast() {
        val result = FontOperationResult.Success(
            value = "ok",
            diagnostics = listOf(
                diagnostic(code = "font.b", message = "second"),
                diagnostic(code = "font.a", message = "first"),
            ),
        )

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.diagnostics as MutableList<FontDiagnostic>)[0] =
                diagnostic(code = "font.mutated", message = "mutated")
        }
        assertEquals(listOf("font.a", "font.b"), result.diagnostics.map { it.code })
    }

    @Test
    fun canonicalOrderingUsesStructuredDataBeforeHumanMessage() {
        val diagnostics = listOf(
            diagnostic(
                code = "font.limit",
                message = "alphabetically first",
                data = FontDiagnosticData(observedValue = 2, limit = 10),
            ),
            diagnostic(
                code = "font.limit",
                message = "alphabetically last",
                data = FontDiagnosticData(observedValue = 1, limit = 10),
            ),
        ).sortedDiagnostics()

        assertEquals(listOf(1L, 2L), diagnostics.map { it.data.observedValue })
    }

    @Test
    fun errorDiagnosticCarriesMachineReadableRangeAndLimitData() {
        val data = FontDiagnosticData(
            offset = 2_147_483_647L,
            length = 16L,
            observedValue = 2_147_483_663L,
            limit = 128L,
        )

        val diagnostic = FontError.OutOfBounds(
            message = "range is outside the source",
            location = FontDiagnosticLocation.Table("cmap"),
        ).toDiagnostic(data)

        assertEquals(data, diagnostic.data)
    }

    private fun diagnostic(
        code: String,
        message: String,
        data: FontDiagnosticData = FontDiagnosticData.empty,
    ) = FontDiagnostic(
        code = code,
        severity = FontDiagnosticSeverity.ERROR,
        location = FontDiagnosticLocation.Source,
        message = message,
        data = data,
    )
}
