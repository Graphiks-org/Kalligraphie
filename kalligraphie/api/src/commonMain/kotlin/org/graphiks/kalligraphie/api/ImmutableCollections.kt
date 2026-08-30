package org.graphiks.kalligraphie.api

internal fun <Element> Iterable<Element>.immutableListSnapshot(): List<Element> =
    ImmutableSnapshotList(toList())

private class ImmutableSnapshotList<Element>(
    source: List<Element>,
) : AbstractMutableList<Element>() {
    private val elements: List<Element> = source.toList()

    override val size: Int
        get() = elements.size

    override fun get(index: Int): Element = elements[index]

    override fun add(index: Int, element: Element): Unit = immutableMutation()

    override fun removeAt(index: Int): Element = immutableMutation()

    override fun set(index: Int, element: Element): Element = immutableMutation()

    private fun <Value> immutableMutation(): Value =
        throw UnsupportedOperationException("Immutable collection snapshot.")
}
