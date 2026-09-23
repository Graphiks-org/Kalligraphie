// CatalogAuditorTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

class CatalogAuditorTest {
    @Test
    fun aSupportedEntryNeedsAFontAFamilyAndAFrame() {
        val violations = entryViolations(
            listOf(
                entry(id = "outline.glyf", status = CatalogStatus.Supported("abc1234"), tables = setOf("cmap")),
            ),
        )
        assertEquals(setOf("supported-missing-font", "supported-missing-family", "supported-missing-frame"), violations.map { it.rule }.toSet())
    }

    @Test
    fun aSupportedEntryWithEveryFieldIsClean() {
        val violations = entryViolations(listOf(supportedEntry("outline.glyf")))
        assertTrue(violations.isEmpty(), violations.toString())
    }

    @Test
    fun aSupportedEntryMustClaimAtLeastOneTable() {
        val violations = entryViolations(
            listOf(
                entry(
                    id = "outline.glyf",
                    status = CatalogStatus.Supported("abc1234"),
                    font = CorpusKey("liberation"),
                    family = GoldenSceneFamily.GLYPH_OUTLINE,
                    frame = SceneFramePolicy.Pinned(10, 10),
                ),
            ),
        )
        assertEquals(setOf("supported-without-tables"), violations.map { it.rule }.toSet())
    }

    @Test
    fun anExpectedRejectionMustNotCarryAFrame() {
        val violations = entryViolations(
            listOf(
                entry(
                    id = "robustness.truncated",
                    status = CatalogStatus.ExpectedRejection(CatalogStage.DECODE, "font.sfnt.truncated"),
                    font = CorpusKey("liberation"),
                    family = GoldenSceneFamily.GLYPH_OUTLINE,
                ),
            ),
        )
        assertEquals(setOf("non-scene-carries-scene-fields"), violations.map { it.rule }.toSet())
    }

    @Test
    fun aNotYetEntryPinningABehaviourNeedsAFont() {
        val violations = entryViolations(
            listOf(
                entry(
                    id = "metrics.vvar",
                    status = CatalogStatus.NotYet(
                        trackingIssue = "spec:§5 metrics",
                        currentBehavior = PinnedBehavior.RejectedAt(CatalogStage.METRICS, "font.variation.vvar-missing"),
                        unpinnedReason = null,
                    ),
                ),
            ),
        )
        assertEquals(setOf("pinned-behaviour-missing-font"), violations.map { it.rule }.toSet())
    }

    @Test
    fun duplicateIdsAcrossAxesAreReportedOncePerExtraEntry() {
        val violations = entryViolations(
            listOf(
                supportedEntry("color.colr-v0", CatalogAxis.COLOR),
                supportedEntry("color.colr-v0", CatalogAxis.BITMAP),
            ),
        )
        assertEquals(listOf("duplicate-id"), violations.map { it.rule })
    }

    @Test
    fun everyAxisMustBeCovered() {
        val violations = CatalogAuditor.audit(listOf(supportedEntry("outline.glyf")))
        assertTrue(
            violations.any { violation -> violation.rule == "axis-uncovered" && violation.detail.contains("BITMAP") },
            violations.toString(),
        )
    }

    private fun supportedEntry(id: String, axis: CatalogAxis = CatalogAxis.OUTLINE) = entry(
        id = id,
        axis = axis,
        status = CatalogStatus.Supported("abc1234"),
        font = CorpusKey("liberation"),
        family = GoldenSceneFamily.GLYPH_OUTLINE,
        frame = SceneFramePolicy.Pinned(10, 10),
        tables = setOf("cmap"),
    )

    private fun entry(
        id: String,
        axis: CatalogAxis = CatalogAxis.OUTLINE,
        status: CatalogStatus,
        font: CorpusKey? = null,
        family: GoldenSceneFamily? = null,
        frame: SceneFramePolicy? = null,
        tables: Set<String> = emptySet(),
    ) = CatalogEntry(
        id = id,
        axis = axis,
        technology = CatalogText("test technology", "technologie de test"),
        font = font,
        status = status,
        family = family,
        frame = frame,
        tables = tables,
    )

    /**
     * Violations about the entries themselves: [CatalogAuditor.audit] always reports the
     * catalog-wide axis-coverage rule too, and the partial inputs below deliberately cover a
     * single axis, so the catalog-wide violations are dropped here. The rule itself is exercised
     * by [everyAxisMustBeCovered] and, on the real catalog, by [ExpectationCatalogTest].
     */
    private fun entryViolations(entries: List<CatalogEntry>): List<CatalogViolation> =
        CatalogAuditor.audit(entries).filter { violation -> violation.entryId != null }
}
