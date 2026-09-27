package org.graphiks.kalligraphie

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toLong
import platform.Foundation.NSCondition
import platform.posix.pthread_self

/**
 * Apple actual for [PortableConditionLock], backed by `NSCondition`.
 *
 * `NSCondition` is the platform's own lock-and-condition pair: `wait` releases exactly the lock
 * `lock` took, which is the contract this type needs, and its memory is managed, so no native
 * mutex has to be allocated and freed by hand. It is **not** reentrant, which is why the type's
 * contract forbids nested [withLock] and why the JVM actual checks for it: a nesting bug must not
 * be able to pass on the JVM and hang here.
 */
internal actual class PortableConditionLock actual constructor() {
    private val condition = NSCondition()

    internal actual fun <T> withLock(block: () -> T): T {
        condition.lock()
        try {
            return block()
        } finally {
            condition.unlock()
        }
    }

    internal actual fun awaitUninterruptibly() {
        condition.wait()
    }

    internal actual fun signalAll() {
        condition.broadcast()
    }
}

/**
 * The pthread this thread runs on, as a value.
 *
 * It has to be a value and not an object: Kotlin/Native hands out a fresh wrapper each time the
 * same Objective-C object is reached, so an `NSThread` would compare unequal to itself as a map
 * key, while the pthread identifier is stable for the life of the thread and is never reused
 * without its entry having been removed first.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun currentThreadToken(): Any = pthread_self().toLong()
