package org.graphiks.kalligraphie

/**
 * Web actual for [PortableConditionLock].
 *
 * The common contract forbids reentrancy and requires `awaitUninterruptibly` to wait for another
 * thread to signal. A single-threaded runtime has no other thread, so waiting is unreachable by
 * design; reaching it is a programming error and fails loudly rather than blocking the event loop.
 */
internal actual class PortableConditionLock actual constructor() {
    private var held: Boolean = false

    internal actual fun <T> withLock(block: () -> T): T {
        check(!held) { "PortableConditionLock must not be used reentrantly." }
        held = true
        try {
            return block()
        } finally {
            held = false
        }
    }

    internal actual fun awaitUninterruptibly() {
        error("PortableConditionLock.awaitUninterruptibly is unreachable on a single-threaded web runtime.")
    }

    internal actual fun signalAll() = Unit
}

/** One stable token: every operation on a single-threaded runtime belongs to the same "thread". */
private val WEB_THREAD_TOKEN: Any = Any()

internal actual fun currentThreadToken(): Any = WEB_THREAD_TOKEN
