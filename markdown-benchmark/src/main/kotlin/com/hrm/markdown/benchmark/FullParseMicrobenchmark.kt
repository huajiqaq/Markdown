package com.hrm.markdown.benchmark

import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.ContainerNode
import com.hrm.markdown.parser.ast.Node
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import org.openjdk.jmh.annotations.Fork
import java.util.concurrent.TimeUnit

/** Full-parse shapes missed by many short blocks and lazy inline-only input. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
open class FullParseMicrobenchmark {
    private lateinit var fencedCode: String
    private lateinit var definitions: String
    private lateinit var paragraph: String
    private lateinit var inlineDocument: String

    @Setup
    open fun setUp() {
        fencedCode = "```text\n" + "value 0123456789\n".repeat(3000) + "```\n"
        definitions = (0 until 3000).joinToString("\n") { "[r$it]: /target$it" }
        paragraph = "ordinary paragraph line\n".repeat(3000)
        inlineDocument = (0 until 200).joinToString("\n\n") {
            "**Kotlin** _Multiplatform_ `parser` ~~benchmark~~ [link](https://example.com/$it) :smile:"
        }
    }

    @Benchmark
    open fun longFencedCode() = MarkdownParser(CommonMarkFlavour).parse(fencedCode)

    @Benchmark
    open fun consecutiveDefinitions() = MarkdownParser(CommonMarkFlavour).parse(definitions)

    @Benchmark
    open fun longParagraph() = MarkdownParser().parse(paragraph)

    @Benchmark
    open fun materializedInlineParse() = MarkdownParser().parse(inlineDocument).also(::materialize)

    private fun materialize(node: Node) {
        if (node is ContainerNode) node.children.forEach(::materialize)
    }
}
