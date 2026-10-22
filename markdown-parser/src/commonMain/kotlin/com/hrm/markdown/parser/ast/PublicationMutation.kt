package com.hrm.markdown.parser.ast

import com.hrm.markdown.parser.core.Attributes
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

    inline fun <T> withoutMutation(action: () -> T): T {
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

internal inline fun <T> withDocumentMutation(document: Document, action: () -> T): T {
    val owner = document.publicationMutationOwner
    owner.enterMutation()
    try { return action() } finally { owner.leaveMutation() }
}

/** Extension callbacks for a fresh region cannot borrow the engine's published-tree authority. */
internal inline fun <T> withoutDocumentMutation(document: Document, action: () -> T): T {
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
    sealDiagnostics(owner)
}

/** Lists returned by AST nodes cannot be cast back to their mutable backing storage. */
private open class ReadOnlyListView<T>(private val items: List<T>) : AbstractList<T>() {
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

/** Only these private snapshots, never a view over mutable backing, may be shared by copy. */
private class ImmutableList<T>(items: List<T>) : ReadOnlyListView<T>(items)

private class SingletonImmutableList<T>(private val item: T) : AbstractList<T>() {
    override val size: Int get() = 1
    override fun get(index: Int): T {
        if (index != 0) throw IndexOutOfBoundsException("index: $index, size: 1")
        return item
    }
}

internal fun <T> immutableListView(items: List<T>): List<T> =
    if (items is ReadOnlyListView<*> || items is SingletonImmutableList<*>) items else ReadOnlyListView(items)

internal fun <T> immutableListCopy(items: List<T>): List<T> = when {
    items is ImmutableList<*> || items is SingletonImmutableList<*> -> items
    items.isEmpty() -> emptyList()
    items.size == 1 -> SingletonImmutableList(items[0])
    else -> ImmutableList(items.toList())
}

private class ImmutableMap<K, V>(items: Map<K, V>) : AbstractMap<K, V>() {
    private val data = items.toMap()
    @Volatile
    private var entriesView: Set<Map.Entry<K, V>>? = null
    override val size: Int get() = data.size
    override fun get(key: K): V? = data[key]
    override fun containsKey(key: K): Boolean = data.containsKey(key)
    override fun containsValue(value: V): Boolean = data.containsValue(value)
    override fun isEmpty(): Boolean = data.isEmpty()
    override val entries: Set<Map.Entry<K, V>>
        get() = entriesView ?: createEntriesView().also { entriesView = it }

    private fun createEntriesView(): Set<Map.Entry<K, V>> = object : AbstractSet<Map.Entry<K, V>>() {
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

private class SingletonImmutableMap<K, V>(private val entryKey: K, private val entryValue: V) : AbstractMap<K, V>() {
    @Volatile
    private var entriesView: Set<Map.Entry<K, V>>? = null
    override val size: Int get() = 1
    override fun get(key: K): V? = if (key == entryKey) entryValue else null
    override fun containsKey(key: K): Boolean = key == entryKey
    override fun containsValue(value: V): Boolean = value == entryValue
    override fun isEmpty(): Boolean = false
    override val entries: Set<Map.Entry<K, V>>
        get() = entriesView ?: object : AbstractSet<Map.Entry<K, V>>() {
            private val entry = object : Map.Entry<K, V> {
                override val key: K get() = entryKey
                override val value: V get() = entryValue
                override fun equals(other: Any?): Boolean =
                    other is Map.Entry<*, *> && key == other.key && value == other.value
                override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
                override fun toString(): String = "$key=$value"
            }
            override val size: Int get() = 1
            override fun iterator(): Iterator<Map.Entry<K, V>> = object : Iterator<Map.Entry<K, V>> {
                private var pending = true
                override fun hasNext(): Boolean = pending
                override fun next(): Map.Entry<K, V> {
                    if (!pending) throw NoSuchElementException()
                    pending = false
                    return entry
                }
            }
        }.also { entriesView = it }
}

internal fun <K, V> immutableMapCopy(items: Map<K, V>): Map<K, V> = when {
    items is ImmutableMap<*, *> || items is SingletonImmutableMap<*, *> -> items
    items.isEmpty() -> emptyMap()
    items.size == 1 -> items.entries.first().let { SingletonImmutableMap(it.key, it.value) }
    else -> ImmutableMap(items)
}

internal val emptyAstAttributes: Attributes = Attributes()

internal fun immutableAttributesCopy(value: Attributes): Attributes {
    val classes = immutableListCopy(value.classes)
    val pairs = immutableMapCopy(value.pairs)
    if (value.id == null && classes.isEmpty() && pairs.isEmpty()) return emptyAstAttributes
    return if (classes === value.classes && pairs === value.pairs) value
        else value.copy(classes = classes, pairs = pairs)
}

/** Builder maps remain MutableMap-compatible while every mutation checks publication authority. */
internal class GuardedMutableMap<K, V>(
    private val checkWrite: (() -> Unit)? = null,
) : AbstractMutableMap<K, V>() {
    private var data: MutableMap<K, V>? = null
    @Volatile
    private var owner: MutationOwner? = null
    private var readOnlyDepth: Int = 0
    @Volatile
    private var keysView: MutableSet<K>? = null
    @Volatile
    private var valuesView: MutableCollection<V>? = null
    @Volatile
    private var entriesView: MutableSet<MutableMap.MutableEntry<K, V>>? = null

    override val size: Int get() = data?.size ?: 0
    override fun get(key: K): V? = data?.get(key)
    override fun containsKey(key: K): Boolean = data?.containsKey(key) ?: false
    override fun containsValue(value: V): Boolean = data?.containsValue(value) ?: false
    override fun isEmpty(): Boolean = data?.isEmpty() ?: true

    private fun checkMutation() {
        check(readOnlyDepth == 0) { "Region postprocessors cannot change document definitions." }
        checkWrite?.invoke()
        owner?.checkMutation()
    }

    inline fun <T> withReadOnly(action: () -> T): T {
        readOnlyDepth++
        try { return action() } finally { readOnlyDepth-- }
    }

    fun seal(owner: MutationOwner) {
        if (this.owner === owner) return
        check(this.owner == null) { "Definition maps cannot cross parser ownership." }
        this.owner = owner
        data?.values?.forEach { (it as? Node)?.seal(owner) }
    }

    override fun put(key: K, value: V): V? {
        checkMutation()
        owner?.let { (value as? Node)?.seal(it) }
        val writable = data ?: mutableMapOf<K, V>().also { data = it }
        return writable.put(key, value)
    }

    override fun remove(key: K): V? { checkMutation(); return data?.remove(key) }
    override fun clear() { checkMutation(); data?.clear() }
    override fun putAll(from: Map<out K, V>) {
        checkMutation()
        from.forEach { (key, value) -> put(key, value) }
    }

    override val keys: MutableSet<K>
        get() = keysView ?: createKeysView().also { keysView = it }

    private fun createKeysView(): MutableSet<K> = object : AbstractMutableSet<K>() {
        override val size: Int get() = this@GuardedMutableMap.size
        override fun contains(element: K): Boolean = containsKey(element)
        override fun add(element: K): Boolean {
            checkMutation()
            throw UnsupportedOperationException("Add keys with put")
        }
        override fun remove(element: K): Boolean {
            checkMutation()
            return data?.keys?.remove(element) ?: false
        }
        override fun clear() = this@GuardedMutableMap.clear()
        override fun iterator(): MutableIterator<K> = guardedIterator(data?.keys?.iterator())
    }

    override val values: MutableCollection<V>
        get() = valuesView ?: createValuesView().also { valuesView = it }

    private fun createValuesView(): MutableCollection<V> = object : AbstractMutableCollection<V>() {
        override val size: Int get() = this@GuardedMutableMap.size
        override fun contains(element: V): Boolean = containsValue(element)
        override fun add(element: V): Boolean {
            checkMutation()
            throw UnsupportedOperationException("Add values with put")
        }
        override fun remove(element: V): Boolean {
            checkMutation()
            return data?.values?.remove(element) ?: false
        }
        override fun clear() = this@GuardedMutableMap.clear()
        override fun iterator(): MutableIterator<V> = guardedIterator(data?.values?.iterator())
    }

    private fun <T> guardedIterator(iterator: MutableIterator<T>?): MutableIterator<T> =
        object : MutableIterator<T> {
            override fun hasNext(): Boolean = iterator?.hasNext() ?: false
            override fun next(): T {
                val current = iterator ?: throw NoSuchElementException()
                return current.next()
            }
            override fun remove() {
                checkMutation()
                val current = iterator ?: throw IllegalStateException("Call next before removing an entry")
                current.remove()
            }
        }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = entriesView ?: createEntriesView().also { entriesView = it }

    private fun createEntriesView(): MutableSet<MutableMap.MutableEntry<K, V>> =
        object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
            override val size: Int get() = this@GuardedMutableMap.size
            override fun add(element: MutableMap.MutableEntry<K, V>): Boolean {
                checkMutation()
                throw UnsupportedOperationException("Add entries with put")
            }
            override fun clear() = this@GuardedMutableMap.clear()
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
                val iterator = data?.entries?.iterator()
                return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                    override fun hasNext(): Boolean = iterator?.hasNext() ?: false
                    override fun remove() {
                        checkMutation()
                        val current = iterator ?: throw IllegalStateException("Call next before removing an entry")
                        current.remove()
                    }
                    override fun next(): MutableMap.MutableEntry<K, V> {
                        val entry = iterator?.next() ?: throw NoSuchElementException()
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
