package org.graphiks.kalligraphie.platform.apple

/** Independent admission parent; each admitted child owns one reference to minimal resources. */
internal class CoreTextResourceOwner private constructor(private val shared: Shared) : AutoCloseable {
    constructor(cleanup: () -> Unit) : this(Shared(cleanup))
    private var closed = false
    fun acquireChild(): CoreTextResourceOwner? = synchronized(this) {
        if (closed) null else {
            val child = CoreTextResourceOwner(shared)
            shared.retain()
            child
        }
    }
    fun isOpen(): Boolean = synchronized(this) { !closed }
    override fun close() {
        val release = synchronized(this) { if (closed) false else { closed = true; true } }
        if (release) shared.release()
    }
    private class Shared(private val cleanup: () -> Unit) {
        private var references = 1L
        fun retain() = synchronized(this) { references = Math.addExact(references, 1) }
        fun release() {
            val final = synchronized(this) { --references == 0L }
            if (final) cleanup()
        }
    }
}
