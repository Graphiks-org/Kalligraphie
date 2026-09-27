package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The portable lock-with-condition the editable line session holds, on every target that compiles
 * this suite.
 *
 * What is asserted here is the half that needs no second thread: the lock half carries the block's
 * result and propagates its failures, and signalling with no waiter is harmless. The waiting half
 * is exercised by the session's own journeys, which drive a real concurrent close against in-flight
 * layouts; a portable test cannot spawn a second thread, so that behaviour is pinned there rather
 * than here.
 */
class PortableConditionLockTest {
    @Test
    fun withLock_returns_the_block_result() {
        val lock = PortableConditionLock()

        assertEquals(7, lock.withLock { 7 })
        assertEquals("value", lock.withLock { "value" })
    }

    @Test
    fun a_failing_block_propagates_and_still_releases_the_lock() {
        val lock = PortableConditionLock()

        assertFailsWith<IllegalStateException> {
            lock.withLock { throw IllegalStateException("boom") }
        }

        var ran = false
        lock.withLock { ran = true }
        assertTrue(ran)
    }

    @Test
    fun signalling_without_a_waiter_is_harmless() {
        val lock = PortableConditionLock()

        lock.withLock { lock.signalAll() }
        // The lock is still usable and still returns its block's result.
        assertEquals(1, lock.withLock { lock.signalAll(); 1 })
    }

    @Test
    fun the_thread_token_is_stable_for_one_thread() {
        // Comparing by equality, not identity: on Apple the token is a pthread identifier, and a
        // per-thread map is keyed by equality on every platform.
        assertEquals(currentThreadToken(), currentThreadToken())
    }
}
