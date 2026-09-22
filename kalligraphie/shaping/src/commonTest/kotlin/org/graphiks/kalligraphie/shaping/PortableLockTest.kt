@file:OptIn(ExperimentalAtomicApi::class)

package org.graphiks.kalligraphie.shaping

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises [PortableLock] and the `kotlin.concurrent.atomics` `closed` flag under real
 * contention. Workers run on [Dispatchers.Default], which is a genuine multi-threaded pool on
 * the JVM/ART, so every worker races the same monitor.
 */
class PortableLockTest {
    @Test
    fun lockSerializesPlainCounterIncrementsUnderContention() = runTest {
        val lock = PortableLock()
        var counter = 0
        val start = CompletableDeferred<Unit>()

        coroutineScope {
            repeat(WORKER_COUNT) {
                launch(Dispatchers.Default) {
                    start.await()
                    repeat(INCREMENTS_PER_WORKER) { lock.withLock { counter += 1 } }
                }
            }
            start.complete(Unit)
        }

        assertEquals(WORKER_COUNT * INCREMENTS_PER_WORKER, counter)
    }

    @Test
    fun closedFlagShortCircuitsOnceSet() = runTest {
        val lock = PortableLock()
        val closed = AtomicBoolean(false)
        var accepted = 0

        fun tryAccept(): Boolean {
            if (closed.load()) return false
            return lock.withLock {
                if (closed.load()) {
                    false
                } else {
                    accepted += 1
                    true
                }
            }
        }

        val start = CompletableDeferred<Unit>()
        val acceptedByWorkers = coroutineScope {
            val workers = (0 until WORKER_COUNT).map {
                async(Dispatchers.Default) {
                    start.await()
                    var localAccepted = 0
                    repeat(ATTEMPTS_PER_WORKER) { if (tryAccept()) localAccepted += 1 }
                    localAccepted
                }
            }
            start.complete(Unit)
            workers.awaitAll().sum()
        }

        assertEquals(WORKER_COUNT * ATTEMPTS_PER_WORKER, acceptedByWorkers)
        assertEquals(acceptedByWorkers, accepted)

        closed.store(true)
        var rejectedAfterClose = 0
        repeat(POST_CLOSE_ATTEMPTS) { if (!tryAccept()) rejectedAfterClose += 1 }
        assertEquals(POST_CLOSE_ATTEMPTS, rejectedAfterClose)
        assertEquals(acceptedByWorkers, accepted)
    }

    @Test
    fun closeLandingBetweenOuterReadAndLockEntryRejectsInFlightAcquirers() = runTest {
        val lock = PortableLock()
        val closed = AtomicBoolean(false)

        fun tryAccept(): Boolean {
            if (closed.load()) return false
            return lock.withLock { if (closed.load()) false else true }
        }

        val leftOuterRead = Channel<Unit>(WORKER_COUNT)
        val enterLock = Channel<Unit>(WORKER_COUNT)

        val acceptedInFlight = coroutineScope {
            val acquirers = (0 until WORKER_COUNT).map { worker ->
                async(Dispatchers.Default) {
                    check(!closed.load()) { "worker $worker observed a closed lock before the transition" }
                    leftOuterRead.send(Unit)
                    enterLock.receive()
                    lock.withLock { !closed.load() }
                }
            }

            val closer = launch(Dispatchers.Default) {
                repeat(WORKER_COUNT) { leftOuterRead.receive() }
                closed.store(true)
                repeat(WORKER_COUNT) { enterLock.send(Unit) }
            }

            closer.join()
            acquirers.awaitAll().count { it }
        }

        assertEquals(0, acceptedInFlight, "the close transition must reject every in-flight acquirer")

        var acceptedAfterClose = 0
        repeat(POST_CLOSE_ATTEMPTS) { if (tryAccept()) acceptedAfterClose += 1 }
        assertEquals(0, acceptedAfterClose, "the post-close invariant must hold")
    }

    @Test
    fun lockKeepsSharedCacheConsistentUnderContention() = runTest {
        val lock = PortableLock()
        val cache = mutableMapOf<Int, Int>()
        val start = CompletableDeferred<Unit>()

        coroutineScope {
            repeat(WORKER_COUNT) { worker ->
                launch(Dispatchers.Default) {
                    start.await()
                    repeat(KEYS_PER_WORKER) { index ->
                        val key = worker * KEYS_PER_WORKER + index
                        lock.withLock {
                            check(cache.put(key, key * 2) == null) { "duplicate key $key" }
                        }
                    }
                }
            }
            start.complete(Unit)
        }

        assertEquals(WORKER_COUNT * KEYS_PER_WORKER, cache.size)
        for ((key, value) in cache) assertEquals(key * 2, value)
    }

    private companion object {
        const val WORKER_COUNT = 12
        const val INCREMENTS_PER_WORKER = 5_000
        const val ATTEMPTS_PER_WORKER = 4_000
        const val KEYS_PER_WORKER = 2_000
        const val POST_CLOSE_ATTEMPTS = 256
    }
}
