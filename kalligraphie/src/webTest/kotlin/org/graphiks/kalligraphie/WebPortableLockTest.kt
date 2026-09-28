package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class WebPortableLockTest {
    @Test
    fun lockRunsTheBlockAndAllowsReentrancy() {
        val lock = PortableLock()
        val value = lock.withLock { lock.withLock { 42 } }
        assertEquals(42, value)
    }

    @Test
    fun conditionLockRejectsReentrantUse() {
        val lock = PortableConditionLock()
        assertFailsWith<IllegalStateException> {
            lock.withLock { lock.withLock { Unit } }
        }
    }

    @Test
    fun conditionLockAwaitIsUnreachableByContract() {
        val lock = PortableConditionLock()
        assertFailsWith<IllegalStateException> { lock.awaitUninterruptibly() }
    }

    @Test
    fun threadTokenIsASingleSharedValue() {
        assertSame(currentThreadToken(), currentThreadToken())
    }

    // `signalAll` with no waiter is covered portably by PortableConditionLockTest, which runs on
    // every target including web; no web-specific duplicate is needed.
}
