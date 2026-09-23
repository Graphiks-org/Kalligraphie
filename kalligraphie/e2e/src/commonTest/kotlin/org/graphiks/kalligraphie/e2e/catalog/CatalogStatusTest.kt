// CatalogStatusTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CatalogStatusTest {
    @Test
    fun aNotYetEntryWithAPinnedBehaviorCarriesNoUnpinnedReason() {
        val status = CatalogStatus.NotYet(
            trackingIssue = "spec:§5 variation",
            currentBehavior = PinnedBehavior.RejectedAt(CatalogStage.METRICS, "font.variation.varc-unsupported"),
            unpinnedReason = null,
        )
        assertNull(status.unpinnedReason)
        assertNotNull(status.currentBehavior)
    }

    @Test
    fun aNotYetEntryWithoutAProbeMustStateWhy() {
        assertFailsWith<IllegalArgumentException> {
            CatalogStatus.NotYet(trackingIssue = "spec:§5 metrics", currentBehavior = null, unpinnedReason = null)
        }
    }

    @Test
    fun aNotYetEntryWithAProbeMustNotStateAnUnpinnedReason() {
        assertFailsWith<IllegalArgumentException> {
            CatalogStatus.NotYet(
                trackingIssue = "spec:§5 metrics",
                currentBehavior = PinnedBehavior.SucceededWith(CatalogStage.METRICS, "STAT decodes as Success(null)"),
                unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
            )
        }
    }

    @Test
    fun aSupportedStatusRequiresACommitHash() {
        assertFailsWith<IllegalArgumentException> { CatalogStatus.Supported("") }
    }

    @Test
    fun anOutOfScopeStatusRequiresARationale() {
        assertFailsWith<IllegalArgumentException> { CatalogStatus.OutOfScope("   ") }
    }

    @Test
    fun anAutoSizedFrameRequiresANonNegativePadding() {
        assertFailsWith<IllegalArgumentException> { SceneFramePolicy.AutoSized(padding = -1) }
    }

    @Test
    fun aPinnedFrameRequiresPositiveDimensions() {
        assertFailsWith<IllegalArgumentException> { SceneFramePolicy.Pinned(width = 0, height = 10) }
    }

    @Test
    fun aCorpusKeyMustNotBeBlank() {
        assertFailsWith<IllegalArgumentException> { CorpusKey(" ") }
    }
}
