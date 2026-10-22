package com.hrm.markdown.parser.core

/** Persistent, bounded text leaves for physical lines that outgrow a single small String. */
internal sealed class TextChunks {
    abstract val length: Int
    abstract val height: Int
    abstract val hash: Long
    abstract val power: Long

    operator fun get(index: Int): Char = when (this) {
        is Leaf -> text[index]
        is Branch -> if (index < left.length) left[index] else right[index - left.length]
    }

    fun appendTo(out: StringBuilder, start: Int = 0, end: Int = length) {
        if (start >= end) return
        when (this) {
            is Leaf -> out.append(text, start, end)
            is Branch -> {
                if (start < left.length) left.appendTo(out, start, minOf(end, left.length))
                if (end > left.length) right.appendTo(out, maxOf(0, start - left.length), end - left.length)
            }
        }
    }

    fun slice(start: Int, end: Int): TextChunks? {
        require(start in 0..length && end in start..length)
        if (start == end) return null
        if (start == 0 && end == length) return this
        return when (this) {
            is Leaf -> Leaf(text.substring(start, end))
            is Branch -> when {
                end <= left.length -> left.slice(start, end)
                start >= left.length -> right.slice(start - left.length, end - left.length)
                else -> join(left.slice(start, left.length), right.slice(0, end - left.length))
            }
        }
    }

    private class Leaf(val text: String) : TextChunks() {
        override val length = text.length
        override val height = 1
        override val hash = hash(text)
        override val power = power(text.length)
    }

    private class Branch(val left: TextChunks, val right: TextChunks) : TextChunks() {
        override val length = left.length + right.length
        override val height = maxOf(left.height, right.height) + 1
        override val hash = left.hash * right.power + right.hash
        override val power = left.power * right.power
    }

    companion object {
        const val LEAF_LIMIT = 1024
        private const val HASH_BASE = -7046029254386353131L

        fun hash(text: String): Long {
            var result = 0L
            for (c in text) result = result * HASH_BASE + c.code + 1L
            return result
        }

        private fun power(count: Int): Long {
            var exponent = count
            var base = HASH_BASE
            var result = 1L
            while (exponent > 0) {
                if (exponent and 1 != 0) result *= base
                base *= base
                exponent = exponent ushr 1
            }
            return result
        }

        fun of(text: String, start: Int = 0, end: Int = text.length): TextChunks? {
            if (start == end) return null
            if (end - start <= LEAF_LIMIT) return Leaf(if (start == 0 && end == text.length) text else text.substring(start, end))
            val middle = start + (end - start) / 2
            return Branch(of(text, start, middle)!!, of(text, middle, end)!!)
        }

        fun join(left: TextChunks?, right: TextChunks?): TextChunks? {
            if (left == null) return right
            if (right == null) return left
            if (left is Leaf && right is Leaf && left.length + right.length <= LEAF_LIMIT) {
                return Leaf(left.text + right.text)
            }
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

        private fun balance(left: TextChunks, right: TextChunks): TextChunks {
            if (left.height > right.height + 1) {
                left as Branch
                if (left.left.height >= left.right.height) return Branch(left.left, Branch(left.right, right))
                val middle = left.right as Branch
                return Branch(Branch(left.left, middle.left), Branch(middle.right, right))
            }
            if (right.height > left.height + 1) {
                right as Branch
                if (right.right.height >= right.left.height) return Branch(Branch(left, right.left), right.right)
                val middle = right.left as Branch
                return Branch(Branch(left, middle.left), Branch(middle.right, right.right))
            }
            return Branch(left, right)
        }
    }
}
