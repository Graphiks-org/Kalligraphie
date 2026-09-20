package org.graphiks.kalligraphie.coroutines

import kotlinx.coroutines.test.runTest
import org.graphiks.kalligraphie.JvmEditableParagraphFacade
import org.graphiks.kalligraphie.api.CoverageStatus
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class LayoutParagraphSuspendTest {
    @Test
    fun suspendParagraphMatchesTheSynchronousParagraph() = runTest {
        val fixture = paragraphFixture("fi fi fi")
        val request = paragraphRequest(fixture, paragraphConstraints(width = 1_400f, top = 50f, height = 1_200f))

        val synchronous = assertIs<ParagraphLayoutResult.Success>(JvmEditableParagraphFacade.layout(request))
        val suspended = assertIs<ParagraphLayoutResult.Success>(KalligraphieCoroutines.layout(request))

        assertEquals(paragraphFingerprint(synchronous), paragraphFingerprint(suspended))
        assertEquals(synchronous.coverageStatus, suspended.coverageStatus)
    }

    @Test
    fun suspendParagraphContinuationReplaysTheSameComposition() = runTest {
        val fixture = paragraphFixture("fi fi fi")
        val firstRequest = paragraphRequest(fixture, paragraphConstraints(width = 1_400f, top = 50f, height = 1_200f))
        val partial = assertIs<ParagraphLayoutResult.Success>(JvmEditableParagraphFacade.layout(firstRequest))
        val continuation = assertNotNull(partial.continuation, "the fixture must force a partial paragraph")
        assertEquals(CoverageStatus.PARTIAL, partial.coverageStatus)

        val resumeRequest = paragraphRequest(
            fixture,
            paragraphConstraints(width = 1_400f, top = 1_250f, height = 1_200f),
            sourceRange = continuation.remainingSourceRange,
            continuation = continuation,
        )
        val synchronousResume = assertIs<ParagraphLayoutResult.Success>(JvmEditableParagraphFacade.layout(resumeRequest))
        val suspendedResume = assertIs<ParagraphLayoutResult.Success>(KalligraphieCoroutines.layout(resumeRequest))

        assertEquals(CoverageStatus.COMPLETE, suspendedResume.coverageStatus)
        assertEquals(paragraphFingerprint(synchronousResume), paragraphFingerprint(suspendedResume))
    }
}
