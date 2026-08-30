package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextIndexOrderingTest {
    @Test
    fun opaqueBoundariesExposeOrderingOnlyWithinTheirSharedVersion() {
        val snapshot = snapshot(TextVersion.create())
        val first = snapshot.textIndexAtScalarBoundary(0)
        val second = snapshot.textIndexAtScalarBoundary(1)

        assertTrue(first < second)
        assertEquals(0, first.compareTo(first))
        assertFailsWith<IllegalArgumentException> {
            first.compareTo(snapshot(TextVersion.create()).textIndexAtScalarBoundary(0))
        }
    }

    private fun snapshot(version: TextVersion): TextSnapshot =
        TextSnapshot(
            version = version,
            sourceEncoding = SourceEncoding.UTF16,
            scalars = listOf('a'.code, 'b'.code),
            sourceRanges = listOf(
                SourceRange(SourceOffset(version, SourceEncoding.UTF16, 0), SourceOffset(version, SourceEncoding.UTF16, 1)),
                SourceRange(SourceOffset(version, SourceEncoding.UTF16, 1), SourceOffset(version, SourceEncoding.UTF16, 2)),
            ),
        )
}
