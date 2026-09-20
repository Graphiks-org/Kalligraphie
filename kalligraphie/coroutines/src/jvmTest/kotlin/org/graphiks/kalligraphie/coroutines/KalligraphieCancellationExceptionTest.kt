package org.graphiks.kalligraphie.coroutines

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class KalligraphieCancellationExceptionTest {
    @Test
    fun carriesTheTypedResultAndRemainsACancellationException() {
        val typedResult = "typed-result"
        val exception = KalligraphieCancellationException(typedResult, "cancelled")

        assertSame(typedResult, exception.result)
        assertIs<CancellationException>(exception)
    }
}
