package com.hrm.markdown.parser.ast

import kotlin.concurrent.Volatile

/** Identity only: this provides no process-wide mutable permission or parser synchronization. */
internal expect fun currentMutationThread(): Any

/** Mutation authority belongs to one parser, never to a process or thread-wide counter. */
internal class MutationOwner {
    @Volatile
    private var mutationDepth: Int = 0
    @Volatile
    private var activeThread: Any? = null

    val hasMutationAuthority: Boolean
        get() = mutationDepth > 0 && activeThread === currentMutationThread()

    fun enterMutation() {
        val thread = currentMutationThread()
        check(mutationDepth == 0 || activeThread === thread) {
            "Parser mutation authority belongs to a different execution thread."
        }
        if (mutationDepth == 0) activeThread = thread
        mutationDepth++
    }

    fun leaveMutation() {
        check(mutationDepth > 0 && activeThread === currentMutationThread()) {
            "Parser mutation authority can only be released by its execution thread."
        }
        mutationDepth--
        if (mutationDepth == 0) activeThread = null
    }

    fun <T> withoutMutation(action: () -> T): T {
        val depth = mutationDepth
        val thread = activeThread
        check(depth == 0 || thread === currentMutationThread()) {
            "Parser mutation authority belongs to a different execution thread."
        }
        mutationDepth = 0
        activeThread = null
        try { return action() } finally {
            activeThread = thread
            mutationDepth = depth
        }
    }

    fun checkMutation() {
        check(hasMutationAuthority) {
            "Published Markdown AST is read-only; edit source through MarkdownParser instead."
        }
    }
}

internal fun <T> withDocumentMutation(document: Document, action: () -> T): T {
    val owner = document.publicationMutationOwner
    owner.enterMutation()
    try { return action() } finally { owner.leaveMutation() }
}

/** Extension callbacks for a fresh region cannot borrow the engine's published-tree authority. */
internal fun <T> withoutDocumentMutation(document: Document, action: () -> T): T {
    val owner = document.publicationMutationOwner
    return owner.withoutMutation { document.definitionContext.withReadOnlyDefinitions(action) }
}

/** A block extension can mutate its input subtree, without thawing any published siblings. */
internal fun <T> withSubtreeMutation(block: Node, action: () -> T): T {
    val permitted = mutableListOf<Node>()
    val owner = block.mutationOwner
    fun acquire(node: Node) {
        node.enterSubtreeMutation()
        permitted.add(node)
        // Materialize only this replacement block, so later passes can transform its inlines.
        if (node is ContainerNode) node.children.forEach(::acquire)
    }
    fun sealNewDescendants(node: Node) {
        if (node.mutationOwner == null) {
            owner?.let { node.seal(it) }
        } else if (node is ContainerNode) {
            node.forEachMaterializedChild(::sealNewDescendants)
        }
    }
    try {
        acquire(block)
        return action()
    } finally {
        permitted.asReversed().forEach(Node::leaveSubtreeMutation)
        if (owner != null) sealNewDescendants(block)
    }
}

/** Publish only the replacement range. Stable prefixes have already been sealed. */
internal fun Document.sealPublication(changedNodes: List<Node>) {
    val owner = publicationMutationOwner
    seal(owner, includeChildren = false)
    changedNodes.forEach { it.seal(owner) }
    definitionContext.seal(owner)
    diagnostics.sealForDocument(owner)
}

/** Lists returned by AST nodes cannot be cast back to their mutable backing storage. */
private class ImmutableList<T>(private val items: List<T>) : AbstractList<T>() {
    override val size: Int get() = items.size
    override fun get(index: Int): T = items[index]
    override fun iterator(): Iterator<T> {
        val iterator = items.iterator()
        return object : Iterator<T> {
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): T = iterator.next()
        }
    }
    override fun listIterator(index: Int): ListIterator<T> {
        val iterator = items.listIterator(index)
        return object : ListIterator<T> {
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): T = iterator.next()
            override fun hasPrevious(): Boolean = iterator.hasPrevious()
            override fun previous(): T = iterator.previous()
            override fun nextIndex(): Int = iterator.nextIndex()
            override fun previousIndex(): Int = iterator.previousIndex()
        }
    }
}

internal fun <T> immutableListView(items: List<T>): List<T> = ImmutableList(items)
internal fun <T> immutableListCopy(items: List<T>): List<T> =
    if (items.isEmpty()) emptyList() else ImmutableList(items.toList())

private class ImmutableMap<K, V>(items: Map<K, V>) : AbstractMap<K, V>() {
    private val data = items.toMap()
    override val size: Int get() = data.size
    override fun get(key: K): V? = data[key]
    override fun containsKey(key: K): Boolean = data.containsKey(key)
    override fun containsValue(value: V): Boolean = data.containsValue(value)
    override fun isEmpty(): Boolean = data.isEmpty()
    override val entries: Set<Map.Entry<K, V>> = object : AbstractSet<Map.Entry<K, V>>() {
        override val size: Int get() = data.size
        override fun iterator(): Iterator<Map.Entry<K, V>> {
            val iterator = data.entries.iterator()
            return object : Iterator<Map.Entry<K, V>> {
                override fun hasNext(): Boolean = iterator.hasNext()
                override fun next(): Map.Entry<K, V> {
                    val entry = iterator.next()
                    return object : Map.Entry<K, V> {
                        override val key: K = entry.key
                        override val value: V = entry.value
                        override fun equals(other: Any?): Boolean =
                            other is Map.Entry<*, *> && key == other.key && value == other.value
                        override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
                        override fun toString(): String = "$key=$value"
                    }
                }
            }
        }
    }
}

internal fun <K, V> immutableMapCopy(items: Map<K, V>): Map<K, V> =
    if (items.isEmpty()) emptyMap() else ImmutableMap(items)

/** Builder maps remain MutableMap-compatible while every mutation checks publication authority. */
internal class GuardedMutableMap<K, V>(
    private val checkWrite: (() -> Unit)? = null,
) : AbstractMutableMap<K, V>() {
    private val data = mutableMapOf<K, V>()
    private var owner: MutationOwner? = null
    private var readOnlyDepth: Int = 0

    override val size: Int get() = data.size
    override fun get(key: K): V? = data[key]
    override fun containsKey(key: K): Boolean = data.containsKey(key)
    override fun containsValue(value: V): Boolean = data.containsValue(value)
    override fun isEmpty(): Boolean = data.isEmpty()

    private fun checkMutation() {
        check(readOnlyDepth == 0) { "Region postprocessors cannot change document definitions." }
        checkWrite?.invoke()
        owner?.checkMutation()
    }

    fun <T> withReadOnly(action: () -> T): T {
        readOnlyDepth++
        try { return action() } finally { readOnlyDepth-- }
    }

    fun seal(owner: MutationOwner) {
        if (this.owner === owner) return
        check(this.owner == null) { "Definition maps cannot cross parser ownership." }
        this.owner = owner
        data.values.forEach { (it as? Node)?.seal(owner) }
    }

    override fun put(key: K, value: V): V? {
        checkMutation()
        owner?.let { (value as? Node)?.seal(it) }
        return data.put(key, value)
    }

    override fun remove(key: K): V? { checkMutation(); return data.remove(key) }
    override fun clear() { checkMutation(); data.clear() }
    override fun putAll(from: Map<out K, V>) {
        checkMutation()
        from.forEach { (key, value) -> put(key, value) }
    }

    override val keys: MutableSet<K> = object : AbstractMutableSet<K>() {
        override val size: Int get() = data.size
        override fun contains(element: K): Boolean = data.containsKey(element)
        override fun add(element: K): Boolean {
            checkMutation()
            throw UnsupportedOperationException("Add keys with put")
        }
        override fun remove(element: K): Boolean {
            checkMutation()
            return data.keys.remove(element)
        }
        override fun clear() = this@GuardedMutableMap.clear()
        override fun iterator(): MutableIterator<K> = guardedIterator(data.keys.iterator())
    }

    override val values: MutableCollection<V> = object : AbstractMutableCollection<V>() {
        override val size: Int get() = data.size
        override fun contains(element: V): Boolean = data.containsValue(element)
        override fun add(element: V): Boolean {
            checkMutation()
            throw UnsupportedOperationException("Add values with put")
        }
        override fun remove(element: V): Boolean {
            checkMutation()
            return data.values.remove(element)
        }
        override fun clear() = this@GuardedMutableMap.clear()
        override fun iterator(): MutableIterator<V> = guardedIterator(data.values.iterator())
    }

    private fun <T> guardedIterator(iterator: MutableIterator<T>): MutableIterator<T> =
        object : MutableIterator<T> {
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): T = iterator.next()
            override fun remove() { checkMutation(); iterator.remove() }
        }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> =
        object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
            override val size: Int get() = data.size
            override fun add(element: MutableMap.MutableEntry<K, V>): Boolean {
                checkMutation()
                throw UnsupportedOperationException("Add entries with put")
            }
            override fun clear() = this@GuardedMutableMap.clear()
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
                val iterator = data.entries.iterator()
                return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                    override fun hasNext(): Boolean = iterator.hasNext()
                    override fun remove() { checkMutation(); iterator.remove() }
                    override fun next(): MutableMap.MutableEntry<K, V> {
                        val entry = iterator.next()
                        return object : MutableMap.MutableEntry<K, V> {
                            override val key: K get() = entry.key
                            override val value: V get() = entry.value
                            override fun setValue(newValue: V): V {
                                checkMutation()
                                owner?.let { (newValue as? Node)?.seal(it) }
                                return entry.setValue(newValue)
                            }
                            override fun equals(other: Any?): Boolean = entry == other
                            override fun hashCode(): Int = entry.hashCode()
                            override fun toString(): String = entry.toString()
                        }
                    }
                }
            }
        }
}
