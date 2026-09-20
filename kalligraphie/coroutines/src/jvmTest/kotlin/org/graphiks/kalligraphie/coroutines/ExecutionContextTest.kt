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
        val fixtures = List(4) { lineFixture("A") }
        try {
            val requests = fixtures.map { lineRequest(it) }
            val sequential = requests.map { request ->
                lineFingerprint(KalligraphieCoroutines.layout(request).line())
            }
            val concurrent = requests.map { request ->
                async(Dispatchers.Default) { lineFingerprint(KalligraphieCoroutines.layout(request).line()) }
            }.awaitAll()

            assertEquals(sequential, concurrent)
        } finally {
            fixtures.forEach { assertClosed(it.resolver.close()) }
        }
    }
}
