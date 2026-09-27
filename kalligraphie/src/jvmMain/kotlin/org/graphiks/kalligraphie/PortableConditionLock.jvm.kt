package org.graphiks.kalligraphie

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JVM actual for [PortableConditionLock], backed by the fair `ReentrantLock` and its condition the
 * session used directly before the port.
 *
 * The lock stays reentrant — that is what the session had — but a nested [withLock] is rejected
 * instead of silently succeeding, because the iOS actual cannot be reentrant and the constraint has
 * to be the same everywhere. The check is reached only after the reentrant acquisition, so a
 * violation fails immediately rather than deadlocking.
 */
internal actual class PortableConditionLock actual constructor() {
    private val lock = ReentrantLock(true)
    private val condition = lock.newCondition()

    internal actual fun <T> withLock(block: () -> T): T = lock.withLock {
        // The lock's own hold count is the authority, not a flag of ours: a thread waiting in
        // `awaitUninterruptibly` has released the lock and its count says so, while a flag of ours
        // would still claim the lock was held and reject an innocent caller.
        check(lock.holdCount == 1) { "PortableConditionLock must not be used reentrantly." }
        block()
    }

    internal actual fun awaitUninterruptibly() = condition.awaitUninterruptibly()

    internal actual fun signalAll() = condition.signalAll()
}

internal actual fun currentThreadToken(): Any = Thread.currentThread()
