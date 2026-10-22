package com.hrm.markdown.benchmark

import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.ContainerNode
import com.hrm.markdown.parser.ast.LeafNode
import com.hrm.markdown.parser.ast.Node
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import org.openjdk.jmh.annotations.Fork
import java.util.concurrent.TimeUnit

/** Independent of the historical gate: input construction is excluded, all lazy inlines are included. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = ["-Xms256m", "-Xmx512m"])
open class FailedInlineScalingBenchmark {
    @Param("1024", "4096", "16384")
    @JvmField
    var chars: Int = 0

    @Param("unmatched-closers", "url-punctuation", "directive", "spoiler", "unequal-backticks", "wiki")
    @JvmField
    var shape: String = ""

    private lateinit var input: String

    @Setup
    open fun setUp() {
        require(chars >= 32)
        // The prefix prevents inline HTML or directive probes from becoming a block-level construct.
        input = buildString(chars) {
            append("prefix ")
            if (shape == "url-punctuation") {
                append("https://a")
                repeat(chars - length) { append('.') }
            } else if (shape == "unequal-backticks") {
                var run = 1
                while (length + run + 1 <= chars) {
                    append("`".repeat(run++))
                    append('x')
                }
            } else {
                val fragment = when (shape) {
                    "unmatched-closers" -> "a* "
                    "wiki" -> "[[target "
                    "directive" -> "{% card "
                    "spoiler" -> ">!hidden "
                    else -> error("Unknown failed inline shape: $shape")
                }
                while (length + fragment.length <= chars) append(fragment)
            }
            repeat(chars - length) { append('x') }
        }
        check(input.length == chars)
    }

    @Benchmark
    open fun materializedParse() = MarkdownParser().parse(input).also { materializeBenchmarkTree(it) }
}

/** Iterative traversal consumes every lazy inline container, including any nested inline containers. */
internal fun materializeBenchmarkTree(root: Node, readLiterals: Boolean = false): Long {
    val pending = ArrayDeque<Node>()
    pending.addLast(root)
    var nodes = 0L
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        nodes++
        if (readLiterals && node is LeafNode) nodes += node.literal.length
        if (node is ContainerNode) node.children.forEach(pending::addLast)
    }
    return nodes
}
