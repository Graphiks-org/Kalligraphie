package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ParagraphStyleContractsTest {
    private val version = TextVersion.create()
    private val snapshot = TextSnapshot(
        version = version,
        sourceEncoding = SourceEncoding.UTF16,
        scalars = "abcdef".map { it.code },
        sourceRanges = (0 until 6).map { index ->
            SourceRange(
                SourceOffset(version, SourceEncoding.UTF16, index),
                SourceOffset(version, SourceEncoding.UTF16, index + 1),
            )
        },
    )

    private fun range(start: Int, endExclusive: Int) =
        TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(endExclusive))

    private fun face(name: String) = FontFaceId(FontSourceId.Opaque("test", name, "face"), 0)

    private fun wght(value: Float) = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", value)))

    @Test
    fun rejectsAnEmptyRange() {
        assertFailsWith<IllegalArgumentException> { ParagraphStyleSpan(range(2, 2), face = face("a")) }
    }

    @Test
    fun rejectsASpanThatNamesNeitherFaceNorVariation() {
        assertFailsWith<IllegalArgumentException> { ParagraphStyleSpan(range(0, 2)) }
    }

    @Test
    fun acceptsFaceOnlyAndVariationOnlySpans() {
        ParagraphStyleSpan(range(0, 2), face = face("a"))
        ParagraphStyleSpan(range(2, 4), variation = wght(700f))
    }

    @Test
    fun snapshotRejectsAnEmptySpanList() {
        assertFailsWith<IllegalArgumentException> { ParagraphStyleSnapshot(emptyList()) }
    }

    @Test
    fun snapshotRejectsUnorderedOrOverlappingSpans() {
        assertFailsWith<IllegalArgumentException> {
            ParagraphStyleSnapshot(
                listOf(ParagraphStyleSpan(range(2, 4), face = face("a")), ParagraphStyleSpan(range(0, 3), face = face("b"))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ParagraphStyleSnapshot(
                listOf(ParagraphStyleSpan(range(0, 4), face = face("a")), ParagraphStyleSpan(range(3, 5), face = face("b"))),
            )
        }
    }

    @Test
    fun snapshotRejectsSpansFromDifferentTextVersions() {
        val other = TextVersion.create()
        val otherSnapshot = TextSnapshot(
            version = other,
            sourceEncoding = SourceEncoding.UTF16,
            scalars = "xy".map { it.code },
            sourceRanges = (0 until 2).map { index ->
                SourceRange(SourceOffset(other, SourceEncoding.UTF16, index), SourceOffset(other, SourceEncoding.UTF16, index + 1))
            },
        )
        val foreignRange = TextRange(otherSnapshot.textIndexAtScalarBoundary(0), otherSnapshot.textIndexAtScalarBoundary(2))

        assertFailsWith<IllegalArgumentException> {
            ParagraphStyleSnapshot(
                listOf(
                    ParagraphStyleSpan(range(0, 2), face = face("a")),
                    ParagraphStyleSpan(foreignRange, face = face("b")),
                ),
            )
        }
    }

    @Test
    fun styleAtReturnsTheCoveringSpanAndNullInsideAGap() {
        val styles = ParagraphStyleSnapshot(
            listOf(
                ParagraphStyleSpan(range(0, 2), face = face("a")),
                ParagraphStyleSpan(range(4, 6), face = face("b")),
            ),
        )

        assertEquals(face("a"), styles.styleAt(snapshot.textIndexAtScalarBoundary(1))?.face)
        assertNull(styles.styleAt(snapshot.textIndexAtScalarBoundary(2)))
        assertNull(styles.styleAt(snapshot.textIndexAtScalarBoundary(3)))
        assertEquals(face("b"), styles.styleAt(snapshot.textIndexAtScalarBoundary(5))?.face)
    }

    @Test
    fun styleAtRejectsAForeignVersionIndex() {
        val styles = ParagraphStyleSnapshot(listOf(ParagraphStyleSpan(range(0, 2), face = face("a"))))
        val other = TextVersion.create()
        val foreignIndex = TextSnapshot(
            version = other,
            sourceEncoding = SourceEncoding.UTF16,
            scalars = listOf('x'.code),
            sourceRanges = listOf(
                SourceRange(SourceOffset(other, SourceEncoding.UTF16, 0), SourceOffset(other, SourceEncoding.UTF16, 1)),
            ),
        ).textIndexAtScalarBoundary(0)

        assertFailsWith<IllegalArgumentException> { styles.styleAt(foreignIndex) }
    }
}
