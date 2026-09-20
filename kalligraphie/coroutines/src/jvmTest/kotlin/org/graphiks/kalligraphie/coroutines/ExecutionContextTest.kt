package org.graphiks.kalligraphie.coroutines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class ExecutionContextTest {
    @Test
    fun resultIsStableAcrossCallerDispatchers() = runTest {
        val fixture = lineFixture("A")
        try {
            val request = lineRequest(fixture)
            val baseline = lineFingerprint(KalligraphieCoroutines.layout(request).line())

            val onUnconfined = withContext(Dispatchers.Unconfined) {
                lineFingerprint(KalligraphieCoroutines.layout(request).line())
            }
            val onDefault = withContext(Dispatchers.Default) {
                lineFingerprint(KalligraphieCoroutines.layout(request).line())
            }
            val onIo = withContext(Dispatchers.IO) {
                lineFingerprint(KalligraphieCoroutines.layout(request).line())
            }

            assertEquals(baseline, onUnconfined)
            assertEquals(baseline, onDefault)
            assertEquals(baseline, onIo)
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }

    @Test
    fun concurrentInvocationsShareNoMutableState() = runTest {
        val fixture = lineFixture("A")
        try {
            val request = lineRequest(fixture)
            val baseline = lineFingerprint(KalligraphieCoroutines.layout(request).line())

            val results = List(4) {
                async(Dispatchers.Default) { lineFingerprint(KalligraphieCoroutines.layout(request).line()) }
            }.awaitAll()

            assertEquals(List(4) { baseline }, results)
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }
}
