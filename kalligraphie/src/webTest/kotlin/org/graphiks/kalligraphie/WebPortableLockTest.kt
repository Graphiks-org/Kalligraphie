package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun signalAllIsANoOp() {
        val lock = PortableConditionLock()
        lock.withLock { lock.signalAll() }
        assertTrue(true)
    }
}
