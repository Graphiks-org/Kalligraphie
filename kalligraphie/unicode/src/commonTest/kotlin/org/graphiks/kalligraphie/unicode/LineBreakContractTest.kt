package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.graphiks.kalligraphie.api.LineBreakAnalysis
import org.graphiks.kalligraphie.api.LineBreakKind
import org.graphiks.kalligraphie.api.LineBreakOpportunity
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeDataIdentity

class LineBreakContractTest {
    @Test
    fun opportunity_outside_the_analyzed_range_is_rejected() {
        val snapshot = snapshotOf("abc")

        assertFailsWith<IllegalArgumentException> {
            analysis(
                range = range(snapshot, 1, 3),
                graphemeClusters = listOf(range(snapshot, 1, 2), range(snapshot, 2, 3)),
                opportunities = listOf(opportunity(snapshot, 0)),
            )
        }
    }

    @Test
    fun opportunity_from_another_text_version_is_rejected() {
        val snapshot = snapshotOf("abc")
        val foreignSnapshot = snapshotOf("abc")

        assertFailsWith<IllegalArgumentException> {
            analysis(
                range = snapshot.range,
                graphemeClusters = scalarClusters(snapshot),
                opportunities = listOf(opportunity(foreignSnapshot, 1)),
            )
        }
    }

    @Test
    fun opportunities_must_be_strictly_sorted() {
        val snapshot = snapshotOf("abc")

        assertFailsWith<IllegalArgumentException> {
            analysis(
                range = snapshot.range,
                graphemeClusters = scalarClusters(snapshot),
                opportunities = listOf(opportunity(snapshot, 2), opportunity(snapshot, 1)),
            )
        }
    }

    @Test
    fun opportunity_inside_an_extended_grapheme_cluster_is_rejected() {
        val snapshot = snapshotOf("a\u0308b")

        assertFailsWith<IllegalArgumentException> {
            analysis(
                range = snapshot.range,
                graphemeClusters = listOf(range(snapshot, 0, 2), range(snapshot, 2, 3)),
                opportunities = listOf(opportunity(snapshot, 1)),
            )
        }
    }

    @Test
    fun supplied_opportunities_are_captured_as_an_immutable_snapshot() {
        val snapshot = snapshotOf("abc")
        val supplied = mutableListOf(opportunity(snapshot, 1))
        val analysis = analysis(
            range = snapshot.range,
            graphemeClusters = scalarClusters(snapshot),
            opportunities = supplied,
        )

        supplied.clear()

        assertEquals(listOf(opportunity(snapshot, 1)), analysis.opportunities)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (analysis.opportunities as MutableList<LineBreakOpportunity>).clear()
        }
    }

    private fun analysis(
        range: TextRange,
        graphemeClusters: List<TextRange>,
        opportunities: List<LineBreakOpportunity>,
    ): LineBreakAnalysis = LineBreakAnalysis(
        range = range,
        unicodeData = UnicodeDataIdentity(
            unicodeVersion = "16.0",
            implementation = "test",
            implementationVersion = "1",
        ),
        graphemeClusters = graphemeClusters,
        opportunities = opportunities,
    )

    private fun opportunity(
        snapshot: TextSnapshot,
        boundary: Int,
        kind: LineBreakKind = LineBreakKind.ALLOWED,
    ): LineBreakOpportunity = LineBreakOpportunity(
        boundary = snapshot.textIndexAtScalarBoundary(boundary),
        kind = kind,
    )

    private fun scalarClusters(snapshot: TextSnapshot): List<TextRange> =
        snapshot.scalars.indices.map { scalarIndex -> range(snapshot, scalarIndex, scalarIndex + 1) }

    private fun snapshotOf(text: String): TextSnapshot =
        TextSnapshots.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(text.toCharArray())),
        ).snapshot

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(
            snapshot.textIndexAtScalarBoundary(start),
            snapshot.textIndexAtScalarBoundary(endExclusive),
        )
}
