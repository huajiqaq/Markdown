package com.hrm.markdown.parser.incremental

import com.hrm.markdown.parser.ast.Node

/** A complete source interval between parser checkpoints, including exported metadata. */
internal data class ParsedRegion(
    val lines: Int,
    val nodes: List<Node>,
    val dependsOnFirstLine: Boolean,
    val hasDefinitions: Boolean,
    val hasNumberedReferences: Boolean,
    val startsWithAttribute: Boolean,
    val headings: List<HeadingInput> = emptyList(),
    val hasSourceRanges: Boolean = false,
    val bibliographyTailGap: Int? = null,
)

/** Heading inputs are captured before attributes/abbreviations mutate the output AST. */
internal data class HeadingInput(val node: Node, val customId: String?, val slug: String)

/**
 * Persistent, measured AVL index. Source-line and output-node lookups/splices are O(log B).
 * The root's List view shares this tree: publication never copies the unchanged prefix.
 */
internal class BlockIndex private constructor(private val root: Tree?) {
    val size: Int get() = root?.regions ?: 0
    val lines: Int get() = root?.lines ?: 0
    val nodeCount: Int get() = root?.nodes ?: 0
    val headingCount: Int get() = root?.headings ?: 0
    val hasSourceRanges: Boolean get() = (root?.positioned ?: 0) > 0
    val hasDefinitions: Boolean get() = (root?.definitions ?: 0) > 0
    val hasNumberedReferences: Boolean get() = (root?.numberedReferences ?: 0) > 0
    internal val height: Int get() = root?.height ?: 0

    operator fun get(index: Int): ParsedRegion {
        require(index in 0 until size)
        var tree = root!!
        var remaining = index
        while (tree is Branch) {
            if (remaining < tree.left.regions) tree = tree.left
            else { remaining -= tree.left.regions; tree = tree.right }
        }
        return (tree as Leaf).region
    }

    fun startLine(index: Int): Int {
        require(index in 0..size)
        if (index == size) return lines
        var tree = root!!
        var remaining = index
        var line = 0
        while (tree is Branch) {
            if (remaining < tree.left.regions) tree = tree.left
            else { remaining -= tree.left.regions; line += tree.left.lines; tree = tree.right }
        }
        return line
    }

    fun indexAtLine(line: Int): Int {
        if (size == 0) return 0
        var tree = root!!
        var remaining = line.coerceIn(0, (lines - 1).coerceAtLeast(0))
        var index = 0
        while (tree is Branch) {
            if (remaining < tree.left.lines) tree = tree.left
            else { remaining -= tree.left.lines; index += tree.left.regions; tree = tree.right }
        }
        return index
    }

    data class Checkpoint(val index: Int, val line: Int, val region: ParsedRegion)

    /** Seek once, then visit old checkpoints in source order without repeated tree searches. */
    fun checkpointsFrom(line: Int, minimumIndex: Int): Iterator<Checkpoint> {
        if (line >= lines || minimumIndex >= size) return emptyList<Checkpoint>().iterator()
        var first = maxOf(indexAtLine(line), minimumIndex)
        if (startLine(first) < line) first++
        if (first >= size) return emptyList<Checkpoint>().iterator()
        val tail = splitAt(first).second
        val firstLine = startLine(first)
        return sequence {
            var index = first
            var currentLine = firstLine
            suspend fun SequenceScope<Checkpoint>.visit(tree: Tree?) {
                when (tree) {
                    null -> Unit
                    is Leaf -> {
                        yield(Checkpoint(index++, currentLine, tree.region))
                        currentLine += tree.region.lines
                    }
                    is Branch -> { visit(tree.left); visit(tree.right) }
                }
            }
            visit(tail.root)
        }.iterator()
    }

    fun splitAt(index: Int): Pair<BlockIndex, BlockIndex> = split(root, index).let {
        BlockIndex(it.first) to BlockIndex(it.second)
    }

    operator fun plus(other: BlockIndex): BlockIndex = BlockIndex(join(root, other.root))

    fun forEachRegion(onlyPositioned: Boolean = false, action: (ParsedRegion) -> Unit) {
        fun visit(tree: Tree?) {
            if (onlyPositioned && (tree?.positioned ?: 0) == 0) return
            when (tree) {
                null -> Unit
                is Leaf -> action(tree.region)
                is Branch -> { visit(tree.left); visit(tree.right) }
            }
        }
        visit(root)
    }

    fun forEachHeading(action: (HeadingInput) -> Unit) {
        fun visit(tree: Tree?) {
            if (tree == null || tree.headings == 0) return
            when (tree) {
                is Leaf -> tree.region.headings.forEach(action)
                is Branch -> { visit(tree.left); visit(tree.right) }
            }
        }
        visit(root)
    }

    fun asNodeList(): List<Node> = object : AbstractList<Node>() {
        override val size: Int get() = nodeCount
        override fun get(index: Int): Node {
            require(index in 0 until size)
            var tree = root!!
            var remaining = index
            while (tree is Branch) {
                if (remaining < tree.left.nodes) tree = tree.left
                else { remaining -= tree.left.nodes; tree = tree.right }
            }
            return (tree as Leaf).region.nodes[remaining]
        }

        override fun iterator(): Iterator<Node> = sequence {
            suspend fun SequenceScope<Node>.visit(tree: Tree?) {
                when (tree) {
                    null -> Unit
                    is Leaf -> yieldAll(tree.region.nodes)
                    is Branch -> { visit(tree.left); visit(tree.right) }
                }
            }
            visit(root)
        }.iterator()
    }

    private sealed class Tree {
        abstract val regions: Int
        abstract val lines: Int
        abstract val nodes: Int
        abstract val headings: Int
        abstract val definitions: Int
        abstract val numberedReferences: Int
        abstract val height: Int
        abstract val positioned: Int
    }

    private class Leaf(val region: ParsedRegion) : Tree() {
        override val regions = 1
        override val lines = region.lines
        override val nodes = region.nodes.size
        override val headings = region.headings.size
        override val definitions = if (region.hasDefinitions) 1 else 0
        override val numberedReferences = if (region.hasNumberedReferences) 1 else 0
        override val height = 1
        override val positioned = if (region.hasSourceRanges) 1 else 0
    }

    private class Branch(val left: Tree, val right: Tree) : Tree() {
        override val regions = left.regions + right.regions
        override val lines = left.lines + right.lines
        override val nodes = left.nodes + right.nodes
        override val headings = left.headings + right.headings
        override val definitions = left.definitions + right.definitions
        override val numberedReferences = left.numberedReferences + right.numberedReferences
        override val height = maxOf(left.height, right.height) + 1
        override val positioned = left.positioned + right.positioned
    }

    companion object {
        fun empty() = BlockIndex(null)

        fun of(regions: List<ParsedRegion>): BlockIndex {
            fun build(start: Int, end: Int): Tree? {
                if (start == end) return null
                if (end - start == 1) return Leaf(regions[start])
                val mid = (start + end) ushr 1
                return Branch(build(start, mid)!!, build(mid, end)!!)
            }
            return BlockIndex(build(0, regions.size))
        }

        private fun split(tree: Tree?, index: Int): Pair<Tree?, Tree?> {
            if (tree == null) return null to null
            if (index <= 0) return null to tree
            if (index >= tree.regions) return tree to null
            tree as Branch
            return if (index < tree.left.regions) {
                val (a, b) = split(tree.left, index)
                a to join(b, tree.right)
            } else {
                val (a, b) = split(tree.right, index - tree.left.regions)
                join(tree.left, a) to b
            }
        }

        private fun join(left: Tree?, right: Tree?): Tree? {
            if (left == null) return right
            if (right == null) return left
            if (left.height > right.height + 1) {
                left as Branch
                return balance(left.left, join(left.right, right)!!)
            }
            if (right.height > left.height + 1) {
                right as Branch
                return balance(join(left, right.left)!!, right.right)
            }
            return Branch(left, right)
        }

        private fun balance(left: Tree, right: Tree): Tree {
            if (left.height > right.height + 1) {
                left as Branch
                if (left.left.height >= left.right.height) return Branch(left.left, Branch(left.right, right))
                val pivot = left.right as Branch
                return Branch(Branch(left.left, pivot.left), Branch(pivot.right, right))
            }
            if (right.height > left.height + 1) {
                right as Branch
                if (right.right.height >= right.left.height) return Branch(Branch(left, right.left), right.right)
                val pivot = right.left as Branch
                return Branch(Branch(left, pivot.left), Branch(pivot.right, right.right))
            }
            return Branch(left, right)
        }
    }
}
