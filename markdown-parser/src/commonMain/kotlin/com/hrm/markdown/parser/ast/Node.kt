package com.hrm.markdown.parser.ast

import com.hrm.markdown.parser.SourceRange
import com.hrm.markdown.parser.LineRange
import kotlin.concurrent.Volatile

private val EMPTY_NODE_LINE_RANGE = LineRange(0, 0)

/**
 * 所有 AST 节点的基类。
 * 节点通过父子关系形成树结构。
 *
 * 增量解析支持：
 * - [contentHash] 标识内容版本，不承担解析边界正确性的证明。
 * - 增量引擎依据解析器检查点、精确未变源码和语义依赖条件复用旧子树。
 */
sealed class Node {
    var sourceRange: SourceRange = SourceRange.EMPTY
        set(value) { checkMutation(); field = value }
    var lineRange: LineRange = EMPTY_NODE_LINE_RANGE
        set(value) { checkMutation(); field = value }
    /** Stable within one parser lineage, independent of source coordinates. */
    var persistentId: Long = 0L
        internal set
    internal var mutationOwner: MutationOwner? = null
        private set
    @Volatile
    private var localMutationDepth: Int = 0
    @Volatile
    private var localMutationThread: Any? = null
    private var directParent: Node? = null
    private var documentOwner: DocumentOwner? = null
    var parent: Node?
        get() = documentOwner?.document ?: directParent
        set(value) { checkMutation(); documentOwner = null; directParent = value }

    // Reused root children already shared mutable coordinates across revisions. Share the
    // current root reference too, so publishing a new root does not walk the stable prefix.
    internal fun attachToDocument(owner: DocumentOwner) {
        checkMutation()
        directParent = null
        documentOwner = owner
    }

    /**
     * 内容哈希：基于该节点所覆盖的源文本行内容计算。
     * 用于内容缓存失效；节点复用还必须满足解析状态和语义依赖条件。
     */
    var contentHash: Long = 0L
        set(value) { checkMutation(); field = value }

    internal fun checkMutation() {
        check(mutationOwner == null || mutationOwner!!.hasMutationAuthority || hasLocalMutationPermit) {
            "Published Markdown AST is read-only; edit source through MarkdownParser instead."
        }
    }

    /** Only newly built, already materialized nodes are visited at publication. */
    internal fun seal(owner: MutationOwner, includeChildren: Boolean = true) {
        if (mutationOwner === owner) return
        check(mutationOwner == null) { "A published node cannot be adopted by another parser." }
        mutationOwner = owner
        if (includeChildren && this is ContainerNode) forEachMaterializedChild { it.seal(owner) }
    }

    /** Lazy parsing may populate this node, without granting writes to published siblings. */
    internal inline fun <T> withLocalMutation(action: () -> T): T {
        enterSubtreeMutation()
        try { return action() } finally { leaveSubtreeMutation() }
    }

    internal val hasLocalMutationPermit: Boolean
        get() = localMutationDepth > 0 && localMutationThread === currentMutationThread()

    internal fun enterSubtreeMutation() {
        val thread = currentMutationThread()
        check(localMutationDepth == 0 || localMutationThread === thread) {
            "AST subtree mutation authority belongs to a different execution thread."
        }
        if (localMutationDepth == 0) localMutationThread = thread
        localMutationDepth++
    }

    internal fun leaveSubtreeMutation() {
        check(localMutationDepth > 0 && localMutationThread === currentMutationThread())
        localMutationDepth--
        if (localMutationDepth == 0) localMutationThread = null
    }

    internal fun sealAttachedChild(child: Node) {
        val owner = mutationOwner
        if (owner != null && !hasLocalMutationPermit) child.seal(owner)
    }

    /**
     * 用于 Compose `key()` 的稳定身份标识。
     * 仅基于起始行号，确保：
     * - 同一位置的块始终被 Compose 视为同一组件实例 → 复用组件、只更新内容
     * - 不同位置的块 key 不同 → 正确分辨不同组件
     *
     * 注意：不包含 contentHash，因为流式输入时内容每个 token 都在变，
     * 如果 key 跟着变，Compose 会销毁旧组件再创建新组件，导致"闪缩"和状态丢失。
     */
    val stableKey: Int
        get() = lineRange.startLine

    /**
     * 接受访问者进行树遍历。
     */
    abstract fun <R> accept(visitor: NodeVisitor<R>): R
}

/**
 * 容器节点，可以包含子节点。
 *
 * 支持按需内联解析（Lazy Inline Parsing）：
 * 块级解析完成后，行内内容可以延迟到首次访问 [children] 时才解析。
 * 这优化了 IDE 语法高亮场景（只解析可见块）和长文档预览（分页 + 按需解析）。
 *
 * 通过 [setLazyInlineContent] 设置延迟解析内容，首次访问 [children] 时自动触发。
 */
sealed class ContainerNode : Node() {
    private var _children: MutableList<Node>? = null
    private var childrenView: List<Node>? = null
    private var indexedChildren: List<Node>? = null
    private val childList: List<Node> get() = indexedChildren ?: _children ?: emptyList()

    /** Publish a persistent block index without copying every root child. */
    internal fun installIndexedChildren(children: List<Node>) {
        checkMutation()
        _children = null
        childrenView = null
        indexedChildren = children
    }

    private fun writableChildren(): MutableList<Node> {
        indexedChildren?.let {
            _children = it.toMutableList()
            childrenView = null
            indexedChildren = null
        }
        return _children ?: mutableListOf<Node>().also { _children = it }
    }

    /**
     * 延迟行内解析的原始内容。设置后，首次访问 [children] 时自动触发行内解析。
     */
    private var _lazyInlineContent: String? = null

    /**
     * 行内解析器引用。与 [_lazyInlineContent] 配合使用。
     */
    private var _lazyInlineParser: ((String, ContainerNode) -> Unit)? = null
    private var trustedLazyInlineContent: Boolean = false
    private var parsingInlineContent: Boolean = false

    /**
     * 是否已完成延迟行内解析。
     */
    private var _inlineParsed: Boolean = false

    /**
     * 子节点列表。
     *
     * 若设置了延迟行内解析内容（通过 [setLazyInlineContent]），
     * 首次访问时会自动触发行内解析。
     */
    val children: List<Node>
        get() {
            ensureInlineParsed()
            return childrenView ?: immutableListView(indexedChildren ?: writableChildren()).also {
                childrenView = it
            }
        }

    /**
     * 设置延迟行内解析内容。
     *
     * 调用后，行内内容不会立即解析，而是在首次访问 [children] 时触发。
     * 解析器提交时会提前执行扩展提供的回调，以核实完整语义依赖；内置行内解析仍保持惰性。
     * 适用于 Paragraph、Heading、SetextHeading、TableCell、DefinitionTerm 等
     * 需要行内解析的块级节点。
     *
     * @param content 待解析的行内原始文本
     * @param parser 行内解析函数，接收 (content, parent) 并将解析结果追加到 parent
     */
    fun setLazyInlineContent(content: String, parser: (String, ContainerNode) -> Unit) {
        setLazyInlineContent(content, parser, trusted = false)
    }

    /** Only built-in inline parsing can preserve deferred dependency-free content at publication. */
    internal fun setTrustedLazyInlineContent(content: String, parser: (String, ContainerNode) -> Unit) {
        setLazyInlineContent(content, parser, trusted = true)
    }

    private fun setLazyInlineContent(content: String, parser: (String, ContainerNode) -> Unit, trusted: Boolean) {
        checkMutation()
        check(!parsingInlineContent) { "An inline parser cannot replace its own pending content." }
        _lazyInlineContent = content
        _lazyInlineParser = parser
        trustedLazyInlineContent = trusted
        _inlineParsed = false
    }

    internal fun materializeUntrustedInlineContent() {
        if (!trustedLazyInlineContent) ensureInlineParsed()
    }

    internal val hasUntrustedPendingContent: Boolean
        get() = !trustedLazyInlineContent && !_inlineParsed && _lazyInlineContent != null

    /**
     * 行内内容是否已解析。
     */
    val isInlineParsed: Boolean get() = _inlineParsed

    /**
     * 确保行内内容已被解析。若有延迟内容则立即执行解析。
     */
    private fun ensureInlineParsed() {
        if (!_inlineParsed && _lazyInlineContent != null) {
            _inlineParsed = true
            val content = _lazyInlineContent!!
            val parser = _lazyInlineParser!!
            val trusted = trustedLazyInlineContent
            _lazyInlineContent = null
            _lazyInlineParser = null
            if (content.isNotEmpty()) {
                val owner = mutationOwner
                parsingInlineContent = true
                try {
                    if (!trusted && owner != null) {
                        owner.withoutMutation { withLocalMutation { parser(content, this) } }
                    } else {
                        withLocalMutation { parser(content, this) }
                    }
                } finally {
                    parsingInlineContent = false
                    if (!hasLocalMutationPermit) {
                        val currentOwner = mutationOwner
                        if (currentOwner != null) forEachMaterializedChild { it.seal(currentOwner) }
                    }
                }
            }
        }
    }

    fun appendChild(child: Node) {
        checkMutation()
        requireBlockOrInlineChild(child)
        child.parent = this
        writableChildren().add(child)
        sealAttachedChild(child)
    }

    fun insertChild(index: Int, child: Node) {
        checkMutation()
        requireBlockOrInlineChild(child)
        child.parent = this
        writableChildren().add(index, child)
        sealAttachedChild(child)
    }

    fun removeChild(child: Node): Boolean {
        checkMutation()
        child.parent = null
        return writableChildren().remove(child)
    }

    fun removeChildAt(index: Int): Node {
        checkMutation()
        val child = writableChildren().removeAt(index)
        child.parent = null
        return child
    }

    fun replaceChild(old: Node, new: Node) {
        checkMutation()
        requireBlockOrInlineChild(new)
        val index = childList.indexOf(old)
        if (index >= 0) {
            old.parent = null
            new.parent = this
            writableChildren()[index] = new
            sealAttachedChild(new)
        }
    }

    fun replaceChildren(startIndex: Int, endIndex: Int, newChildren: List<Node>) {
        checkMutation()
        for (child in newChildren) requireBlockOrInlineChild(child)
        val children = writableChildren()
        for (i in startIndex until endIndex) children[i].parent = null
        children.subList(startIndex, endIndex).clear()
        newChildren.forEach { it.parent = this }
        children.addAll(startIndex, newChildren)
        for (child in newChildren) sealAttachedChild(child)
    }

    fun clearChildren() {
        checkMutation()
        childList.forEach { it.parent = null }
        _children = null
        childrenView = null
        indexedChildren = null
        // 同时清除 lazy 状态，避免 clear 后重新触发旧的 lazy 解析
        _lazyInlineContent = null
        _lazyInlineParser = null
        trustedLazyInlineContent = false
        _inlineParsed = false
    }

    fun childCount(): Int {
        ensureInlineParsed()
        return childList.size
    }

    /**
     * 遍历当前已经物化的子节点，但不触发延迟行内解析。
     *
     * AST 坐标平移等结构维护操作只需要处理现存节点；如果在这些路径上读取
     * [children]，会意外把整个复用子树的行内节点提前解析出来。
     */
    internal inline fun forEachMaterializedChild(action: (Node) -> Unit) {
        val indexed = indexedChildren
        if (indexed != null) {
            // The persistent root index has a linear iterator; indexed reads would cost O(log N).
            for (child in indexed) action(child)
        } else {
            // Builder child storage is an ArrayList. Avoid both a public wrapper and an iterator
            // for the frequent metadata/sealing walks, including untouched lazy containers.
            val materialized = _children ?: return
            var index = 0
            val size = materialized.size
            while (index < size) action(materialized[index++])
        }
    }

    /** Returns a mutation-safe snapshot without triggering delayed inline parsing. */
    internal fun materializedChildrenSnapshot(): List<Node> = childList.toList()

    private fun requireBlockOrInlineChild(child: Node) {
        require(child !is Document) {
            "Document is only valid as an AST root; return a block or inline node from an extension."
        }
    }
}

/** Resolve extension-supplied lazy trees before publishing their complete semantic dependencies. */
internal fun materializeUntrustedContent(node: Node) {
    materializeUntrustedSubtree(node)
    validateMaterializedUntrustedSubtree(node)
}

private fun materializeUntrustedSubtree(node: Node) {
    if (node is ContainerNode) {
        node.materializeUntrustedInlineContent()
        node.forEachMaterializedChild(::materializeUntrustedSubtree)
    }
}

private fun validateMaterializedUntrustedSubtree(node: Node) {
    if (node is ContainerNode) {
        check(!node.hasUntrustedPendingContent) {
            "An extension rescheduled lazy content in an already visited subtree; lazy callbacks must only populate their supplied container."
        }
        node.forEachMaterializedChild(::validateMaterializedUntrustedSubtree)
    }
}

/**
 * 叶子节点，不能包含子节点，仅包含文本内容。
 */
sealed class LeafNode : Node() {
    abstract val literal: String
}

/** One mutable owner per incremental engine; top-level nodes resolve their current root lazily. */
internal class DocumentOwner(var document: Document)
