package com.hrm.markdown.parser.core

import com.hrm.markdown.parser.LineRange

/**
 * Normalized, immutable source backed by a persistent AVL tree of physical lines.
 * Edits copy search paths and bounded boundary fragments, sharing unchanged text and line indexes.
 * [content] explicitly materializes (and caches) a flat String; parsing uses line access instead.
 */
class SourceText private constructor(
    private val root: Lines,
    private var flattened: String? = null,
) {
    val content: String
        get() = flattened ?: buildString(length) { root.appendTo(this) }.also { flattened = it }
    val length: Int get() = root.characters
    val lineCount: Int get() = root.count
    internal val treeHeight: Int get() = root.height

    fun lineStart(line: Int): Int {
        require(line in 0 until lineCount) { "Line $line out of range [0, $lineCount)" }
        return root.offsetOf(line)
    }

    /** End offset, including a possible newline. */
    fun lineEnd(line: Int): Int = lineStart(line) + root.lineAt(line).characters

    fun lineContent(line: Int): String {
        require(line in 0 until lineCount) { "Line $line out of range [0, $lineCount)" }
        return root.lineAt(line).contentWithoutNewline()
    }

    fun linesContent(startLine: Int, endLine: Int): List<String> =
        (startLine until endLine).map(::lineContent)

    /** Offset at EOF belongs to the last physical line, including the final empty line. */
    fun lineAtOffset(offset: Int): Int = root.lineAtOffset(offset.coerceIn(0, length))

    fun columnAtOffset(offset: Int): Int = offset - lineStart(lineAtOffset(offset))

    operator fun get(index: Int): Char {
        require(index in 0 until length)
        val line = lineAtOffset(index)
        return root.lineAt(line).characterAt(index - lineStart(line))
    }

    fun substring(startOffset: Int, endOffset: Int): String {
        require(startOffset in 0..length && endOffset in startOffset..length)
        if (startOffset == endOffset) return ""
        return buildString(endOffset - startOffset) {
            root.appendRangeTo(this, startOffset, endOffset)
        }
    }

    /** Position-independent rolling hash of line hashes. Query cost is O(log(lineCount)). */
    fun contentHash(range: LineRange): Long {
        val start = range.startLine.coerceIn(0, lineCount)
        val end = range.endLine.coerceIn(start, lineCount)
        return root.hashRange(start, end).first
    }

    /** Sequential traversal; edited chunked lines additionally materialize their visited text. */
    internal fun forEachLine(start: Int, end: Int, action: (Int, String, Long) -> Boolean) {
        fun visit(tree: Lines, base: Int): Boolean {
            if (base >= end || base + tree.count <= start) return true
            return when (tree) {
                is Line -> action(base, tree.text, tree.hash)
                is Branch -> visit(tree.left, base) && visit(tree.right, base + tree.left.count)
            }
        }
        visit(root, 0)
    }

    private sealed class Lines {
        abstract val count: Int
        abstract val characters: Int
        abstract val height: Int
        abstract val hash: Long
        abstract val power: Long

        fun lineAt(index: Int): Line = when (this) {
            is Line -> this
            is Branch -> if (index < left.count) left.lineAt(index) else right.lineAt(index - left.count)
        }

        fun offsetOf(index: Int): Int = when (this) {
            is Line -> 0
            is Branch -> if (index < left.count) left.offsetOf(index)
                else left.characters + right.offsetOf(index - left.count)
        }

        fun lineAtOffset(offset: Int): Int = when (this) {
            is Line -> 0
            is Branch -> if (offset < left.characters) left.lineAtOffset(offset)
                else left.count + right.lineAtOffset(offset - left.characters)
        }

        fun appendTo(out: StringBuilder) {
            when (this) {
                is Line -> appendContentTo(out, 0, characters)
                is Branch -> { left.appendTo(out); right.appendTo(out) }
            }
        }

        fun appendRangeTo(out: StringBuilder, start: Int, end: Int) {
            if (start >= end) return
            when (this) {
                is Line -> appendContentTo(out, start, end)
                is Branch -> {
                    if (start < left.characters) left.appendRangeTo(out, start, minOf(end, left.characters))
                    if (end > left.characters) right.appendRangeTo(out, maxOf(0, start - left.characters), end - left.characters)
                }
            }
        }

        fun hashRange(start: Int, end: Int): Pair<Long, Long> {
            if (start == end) return 0L to 1L
            if (start == 0 && end == count) return hash to power
            this as Branch
            if (end <= left.count) return left.hashRange(start, end)
            if (start >= left.count) return right.hashRange(start - left.count, end - left.count)
            val a = left.hashRange(start, left.count)
            val b = right.hashRange(0, end - left.count)
            return (a.first * b.second + b.first) to (a.second * b.second)
        }
    }

    private class Line private constructor(private val flat: String?, private val chunks: TextChunks?) : Lines() {
        constructor(text: String) : this(text,
            if (text.length > TextChunks.LEAF_LIMIT) TextChunks.of(text) else null)
        constructor(chunks: TextChunks?) : this(if (chunks == null) "" else null, chunks)

        override val count = 1
        override val characters = flat?.length ?: chunks!!.length
        override val height = 1
        override val power = RANGE_HASH_BASE
        override val hash: Long = (chunks?.hash ?: TextChunks.hash(flat!!)) xor -3750763034362895579L
        val text: String get() = flat ?: buildString(characters) { chunks!!.appendTo(this) }

        fun characterAt(index: Int): Char = flat?.get(index) ?: chunks!![index]

        fun appendContentTo(out: StringBuilder, start: Int, end: Int) {
            if (flat != null) out.append(flat, start, end) else chunks!!.appendTo(out, start, end)
        }

        fun contentWithoutNewline(): String {
            val end = if (characters > 0 && characterAt(characters - 1) == '\n') characters - 1 else characters
            if (flat != null) return if (end == characters) flat else flat.substring(0, end)
            return buildString(end) { chunks!!.appendTo(this, 0, end) }
        }

        fun slice(start: Int, end: Int): TextChunks? =
            if (chunks != null) chunks.slice(start, end) else TextChunks.of(flat!!, start, end)
    }

    private class Branch(val left: Lines, val right: Lines) : Lines() {
        override val count = left.count + right.count
        override val characters = left.characters + right.characters
        override val height = maxOf(left.height, right.height) + 1
        override val hash = left.hash * right.power + right.hash
        override val power = left.power * right.power
    }

    companion object {
        private const val RANGE_HASH_BASE = -7046029254386353131L

        fun of(input: String): SourceText {
            val text = normalize(input)
            return SourceText(fromText(text, includeFinalLine = true)!!, text)
        }

        fun applyEdit(current: SourceText, offset: Int, deleteLength: Int, insertText: String): SourceText =
            applyEditFast(current, offset, deleteLength, insertText)

        /** Copies bounded text leaves and tree paths, including for a growing physical line. */
        fun applyEditFast(current: SourceText, offset: Int, deleteLength: Int, insertText: String): SourceText {
            require(offset in 0..current.length) { "offset $offset out of [0, ${current.length}]" }
            require(deleteLength >= 0 && deleteLength <= current.length - offset) {
                "delete length $deleteLength out of [0, ${current.length - offset}]"
            }
            val inserted = normalize(insertText)
            if (deleteLength == inserted.length && current.substring(offset, offset + deleteLength) == inserted) return current
            val first = current.lineAtOffset(offset)
            val last = current.lineAtOffset(offset + deleteLength)
            val firstLine = current.root.lineAt(first)
            val lastLine = current.root.lineAt(last)
            val prefixEnd = offset - current.lineStart(first)
            val suffixStart = offset + deleteLength - current.lineStart(last)
            val (head, rest) = split(current.root, first)
            val (_, tail) = split(rest, last - first + 1)
            val includeFinalLine = last == current.lineCount - 1
            val changed = if (prefixEnd + inserted.length + lastLine.characters - suffixStart <= TextChunks.LEAF_LIMIT) {
                val middle = buildString {
                    firstLine.appendContentTo(this, 0, prefixEnd)
                    append(inserted)
                    lastLine.appendContentTo(this, suffixStart, lastLine.characters)
                }
                fromText(middle, includeFinalLine)
            } else {
                val lines = ArrayList<Line>()
                var prefix = firstLine.slice(0, prefixEnd)
                var start = 0
                for (i in inserted.indices) {
                    if (inserted[i] == '\n') {
                        lines.add(Line(TextChunks.join(prefix, TextChunks.of(inserted, start, i + 1))))
                        prefix = null
                        start = i + 1
                    }
                }
                val end = TextChunks.join(TextChunks.join(prefix, TextChunks.of(inserted, start)),
                    lastLine.slice(suffixStart, lastLine.characters))
                if (end != null || includeFinalLine) lines.add(Line(end))
                fromLines(lines)
            }
            return SourceText(join(join(head, changed), tail) ?: Line(""))
        }

        internal fun normalize(input: String): String {
            if (input.none { it == '\r' || it == '\u0000' }) return input
            return buildString(input.length) {
                var i = 0
                while (i < input.length) {
                    when (val c = input[i]) {
                        '\r' -> { append('\n'); if (i + 1 < input.length && input[i + 1] == '\n') i++ }
                        '\u0000' -> append('\uFFFD')
                        else -> append(c)
                    }
                    i++
                }
            }
        }

        private fun fromText(text: String, includeFinalLine: Boolean): Lines? {
            val lines = ArrayList<Line>()
            var start = 0
            for (i in text.indices) {
                if (text[i] == '\n') { lines.add(Line(text.substring(start, i + 1))); start = i + 1 }
            }
            if (start < text.length || includeFinalLine) lines.add(Line(text.substring(start)))
            return fromLines(lines)
        }

        private fun fromLines(lines: List<Line>): Lines? {
            fun build(startIndex: Int, endIndex: Int): Lines? {
                if (startIndex == endIndex) return null
                if (endIndex - startIndex == 1) return lines[startIndex]
                val mid = (startIndex + endIndex) ushr 1
                return Branch(build(startIndex, mid)!!, build(mid, endIndex)!!)
            }
            return build(0, lines.size)
        }

        private fun split(tree: Lines?, count: Int): Pair<Lines?, Lines?> {
            if (tree == null) return null to null
            if (count <= 0) return null to tree
            if (count >= tree.count) return tree to null
            tree as Branch
            return if (count < tree.left.count) {
                val (a, b) = split(tree.left, count)
                a to join(b, tree.right)
            } else {
                val (a, b) = split(tree.right, count - tree.left.count)
                join(tree.left, a) to b
            }
        }

        private fun join(left: Lines?, right: Lines?): Lines? {
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

        private fun balance(left: Lines, right: Lines): Lines {
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
