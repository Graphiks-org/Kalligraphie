package org.graphiks.kalligraphie.coroutines

import kotlinx.coroutines.Job
import org.graphiks.kalligraphie.api.CancellationToken
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CancellationBridgeTest {
    @Test
    fun isNotCancelledWhenNeitherJobNorTokenRequestsIt() {
        val token = bridgeCancellationToken(Job(), CancellationToken.none)

        assertFalse(token.isCancellationRequested())
    }

    @Test
    fun isCancelledWhenTheConsumerTokenRequestsIt() {
        val token = bridgeCancellationToken(Job(), CancellationToken.cancelled)

        assertTrue(token.isCancellationRequested())
    }

    @Test
    fun isCancelledWhenTheCoroutineJobIsCancelled() {
        val job = Job().apply { cancel() }
        val token = bridgeCancellationToken(job, CancellationToken.none)

        assertTrue(token.isCancellationRequested())
    }

    @Test
    fun toleratesAnAbsentJob() {
        val token = bridgeCancellationToken(null, CancellationToken.none)

        assertFalse(token.isCancellationRequested())
    }
}
