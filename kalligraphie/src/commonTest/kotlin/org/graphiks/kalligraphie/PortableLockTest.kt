package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The portable lock the text facades hold, on every target that compiles this suite.
 *
 * The facades rely on two properties the JVM monitor gave them for free and that a portable
 * replacement must keep: the block's result is the lock's result, and the lock is reentrant, so a
 * synchronized operation can call another without deadlocking. The remaining property — mutual
 * exclusion between threads — is exercised by the facade journeys, which drive the real sessions
 * concurrently on the targets that can run them.
 */
class PortableLockTest {
    @Test
    fun withLock_returns_the_block_result() {
        val lock = PortableLock()

        assertEquals(7, lock.withLock { 7 })
        assertEquals("value", lock.withLock { "value" })
    }

    @Test
    fun withLock_is_reentrant_on_the_same_thread() {
        val lock = PortableLock()
        val order = mutableListOf<String>()

        val result = lock.withLock {
            order += "outer"
            val inner = lock.withLock {
                order += "inner"
                "inner-result"
            }
            assertEquals("inner-result", inner)
            order += "outer-resumed"
            "outer-result"
        }

        assertEquals("outer-result", result)
        assertEquals(listOf("outer", "inner", "outer-resumed"), order)
    }

    @Test
    fun a_failing_block_propagates_and_still_releases_the_lock() {
        val lock = PortableLock()

        assertFailsWith<IllegalStateException> {
            lock.withLock { throw IllegalStateException("boom") }
        }

        // The lock must be usable again: a block that threw may not leave it held.
        var ran = false
        lock.withLock { ran = true }
        assertTrue(ran)
    }

    @Test
    fun the_result_of_a_nested_block_is_not_the_outer_result() {
        val lock = PortableLock()

        val outer = lock.withLock {
            val nested = lock.withLock { 1 }
            nested + 1
        }

        assertEquals(2, outer)
    }

    @Test
    fun separate_locks_are_independent() {
        val first = PortableLock()
        val second = PortableLock()
        var secondAcquired = false

        first.withLock {
            second.withLock { secondAcquired = true }
        }

        assertTrue(secondAcquired)
        assertFalse(first.withLock { false })
    }
}
