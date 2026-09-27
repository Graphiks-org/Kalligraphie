package org.graphiks.kalligraphie

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Android actual for [PortableConditionLock]. ART implements the same fair `ReentrantLock` and
 * condition as the JVM, so the semantics are the ones the session had before the port, including
 * the loud rejection of a nested [withLock] the iOS actual cannot express.
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
