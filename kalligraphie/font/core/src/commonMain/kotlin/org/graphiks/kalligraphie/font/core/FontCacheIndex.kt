package org.graphiks.kalligraphie.font.core

/**
 * Fixed-depth binary hash trie with at most 32 items sharing one hash. Saturation declines
 * optional retention. Preparation allocates before ledger mutation; commit and handle removal
 * allocate nothing and invoke neither key hashing nor equality. No operation resizes an index.
 */
internal class CacheIndex<T> {
    internal class Branch<T> {
        var zero: Branch<T>? = null
        var one: Branch<T>? = null
        var items: Item<T>? = null
    }
    internal class Item<T>(val key: Any, val value: T, val hash: Int, var next: Item<T>? = null)
    private val root = Branch<T>()

    fun get(key: Any): T? {
        val hash = key.hashCode()
        var branch = root
        for (bit in 0 until 32) branch = (if ((hash ushr bit) and 1 == 0) branch.zero else branch.one) ?: return null
        var item = branch.items
        while (item != null) {
            if (item.key == key) return item.value
            item = item.next
        }
        return null
    }

    fun prepare(key: Any, value: T): Insertion<T>? {
        val hash = key.hashCode()
        var branch = root
        for (bit in 0 until 32) {
            val one = (hash ushr bit) and 1 != 0
            val child = if (one) branch.one else branch.zero
            if (child == null) {
                val subtree = Branch<T>()
                var leaf = subtree
                for (remaining in bit + 1 until 32) {
                    val next = Branch<T>()
                    if ((hash ushr remaining) and 1 == 0) leaf.zero = next else leaf.one = next
                    leaf = next
                }
                val item = Item(key, value, hash)
                leaf.items = item
                return Insertion(branch, one, subtree, item)
            }
            branch = child
        }
        var item = branch.items
        var collisions = 0
        while (item != null) {
            if (item.key == key || ++collisions >= 32) return null
            item = item.next
        }
        return Insertion(branch, false, null, Item(key, value, hash, branch.items))
    }

    internal class Insertion<T>(private val parent: Branch<T>, private val one: Boolean, private val subtree: Branch<T>?, val item: Item<T>) {
        fun commit() {
            if (subtree == null) parent.items = item
            else if (one) parent.one = subtree else parent.zero = subtree
        }
    }

    fun remove(item: Item<T>) { remove(root, item, item.hash, 0) }
    private fun remove(branch: Branch<T>, target: Item<T>, hash: Int, bit: Int): Boolean {
        if (bit == 32) {
            var previous: Item<T>? = null
            var item = branch.items
            while (item != null) {
                if (item === target) {
                    if (previous == null) branch.items = item.next else previous.next = item.next
                    break
                }
                previous = item
                item = item.next
            }
        } else if ((hash ushr bit) and 1 == 0) {
            val child = branch.zero
            if (child != null && remove(child, target, hash, bit + 1)) branch.zero = null
        } else {
            val child = branch.one
            if (child != null && remove(child, target, hash, bit + 1)) branch.one = null
        }
        return branch.items == null && branch.zero == null && branch.one == null
    }
}
