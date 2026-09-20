package org.graphiks.kalligraphie.coroutines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.graphiks.kalligraphie.JvmEditableLineFacade
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LayoutLineSuspendTest {
    @Test
    fun suspendLineMatchesTheSynchronousLine() = runTest {
        val fixture = lineFixture("A")
        try {
            val request = lineRequest(fixture)
            val synchronous = assertIs<EditableLineResult.Success>(JvmEditableLineFacade.layout(request))
            val suspended = assertIs<EditableLineResult.Success>(KalligraphieCoroutines.layout(request))

            assertEquals(lineFingerprint(synchronous.line), lineFingerprint(suspended.line))
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }

    @Test
    fun consumerTokenCancellationReturnsTheTypedCancelledLine() = runTest {
        val fixture = lineFixture("A")
        try {
            val token = object : CancellationToken {
                private var checks = 0
                override fun isCancellationRequested(): Boolean = ++checks >= 1
            }
            val result = KalligraphieCoroutines.layout(lineRequest(fixture, cancellationToken = token))

            assertIs<EditableLineResult.Cancelled>(result)
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }

    @Test
    fun jobCancelledBeforeStartThrowsCarryingAnEmptyTypedCancelledLine() {
        val fixture = lineFixture("A")
        try {
            val request = lineRequest(fixture)
            val exception = captureCancellation { context ->
                val job = context[Job]!!
                job.cancel()
                KalligraphieCoroutines.layout(request)
            }

            assertIs<EditableLineResult.Cancelled>(exception.result)
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }

    @Test
    fun jobCancelledDuringTheOperationThrowsCarryingTheTypedCancelledLine() {
        val fixture = lineFixture("A")
        try {
            val exception = captureCancellation { context ->
                val job = context[Job]!!
                val token = object : CancellationToken {
                    private var checks = 0
                    override fun isCancellationRequested(): Boolean {
                        checks += 1
                        if (checks >= 1) {
                            job.cancel()
                            return true
                        }
                        return false
                    }
                }
                KalligraphieCoroutines.layout(lineRequest(fixture, cancellationToken = token))
            }

            assertIs<EditableLineResult.Cancelled>(exception.result)
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }

    @Test
    fun runsInTheCallerContextWithoutChangingTheResult() = runTest {
        val fixture = lineFixture("A")
        try {
            val request = lineRequest(fixture)
            val baseline = lineFingerprint(KalligraphieCoroutines.layout(request).line())
            val onUnconfined = lineFingerprint(
                withContext(Dispatchers.Unconfined) { KalligraphieCoroutines.layout(request).line() },
            )

            assertEquals(baseline, onUnconfined)
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }
}
