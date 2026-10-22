package com.hrm.markdown.benchmark

import com.hrm.markdown.parser.MarkdownParserConfig

import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

/**
 * Closed-loop samples of individual parser calls at a fixed source size. Invocation restoration and
 * optional preparation of the next call's existing AST are excluded from the SampleTime interval,
 * but can still affect GC, caches and scheduling. Do not use fixture-inclusive GC-profiler bytes/op
 * as parser-call allocation or interpret this as an arrival-driven, continuously growing stream.
 */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 250, timeUnit = TimeUnit.MILLISECONDS, batchSize = 1)
@Measurement(iterations = 4, time = 500, timeUnit = TimeUnit.MILLISECONDS, batchSize = 1)
@Fork(value = 2, jvmArgsAppend = ["-Xms256m", "-Xmx512m"])
open class ParserCallSampleTimeBenchmark {
    @Param("100", "1000", "10000")
    @JvmField
    var paragraphs: Int = 0

    @Param("tail-replace", "middle-replace", "append-commit")
    @JvmField
    var operation: String = ""

    @Param("lazy", "materialized")
    @JvmField
    var existingAst: String = ""

    private lateinit var parser: MarkdownParser
    private var sourceLength = 0
    private var targetParagraph = 0
    private var editOffset = 0
    private var appendMode = false
    private var materializedMode = false
    private var replacement = "X"

    @Setup(Level.Trial)
    open fun setUpTrial() {
        require(paragraphs >= 2)
        require(operation in setOf("tail-replace", "middle-replace", "append-commit"))
        require(existingAst == "lazy" || existingAst == "materialized")
        appendMode = operation == "append-commit"
        materializedMode = existingAst == "materialized"
        targetParagraph = if (operation == "middle-replace") paragraphs / 2 else paragraphs - 1
        val source = (0 until paragraphs).joinToString("\n\n") { "paragraph $it plain text" }
        sourceLength = source.length
        editOffset = if (operation == "middle-replace") {
            source.indexOf("paragraph $targetParagraph ") + "paragraph ".length
        } else sourceLength - 1
        replacement = "X"
        require(source[editOffset] != 'X' && source[editOffset] != 'Y')

        parser = MarkdownParser(MarkdownParserConfig(flavour = CommonMarkFlavour, appendCoalesceThreshold = 16))
        if (appendMode) {
            parser.beginStream()
            parser.append(source)
        } else {
            parser.parse(source)
        }
        require(parser.sourceText.length == sourceLength)
        require(parser.document.children.size == paragraphs)
        if (materializedMode) materializeBenchmarkTree(parser.document)
    }

    @Benchmark
    open fun parserCall(): Document = if (appendMode) {
        // Exactly one threshold: every invocation publishes a parse, never just a buffered receive.
        parser.append(SAMPLE_TIME_APPEND_CHUNK)
    } else {
        parser.replace(editOffset, 1, replacement)
    }

    @TearDown(Level.Invocation)
    open fun restoreInvocation() {
        if (appendMode) {
            require(parser.sourceText.length == sourceLength + SAMPLE_TIME_APPEND_CHUNK.length)
            val tail = parser.document.children[targetParagraph] as Paragraph
            require(tail.rawContent?.endsWith(SAMPLE_TIME_APPEND_CHUNK) == true) { "Append did not publish a committed tail" }
            parser.delete(sourceLength, SAMPLE_TIME_APPEND_CHUNK.length)
        } else {
            require(parser.sourceText[editOffset] == replacement[0])
            // The next replacement always differs from the currently committed source character.
            replacement = if (replacement == "X") "Y" else "X"
        }
        require(parser.sourceText.length == sourceLength)
        if (materializedMode) materializeBenchmarkTree(parser.document.children[targetParagraph])
    }
}

private const val SAMPLE_TIME_APPEND_CHUNK = "abcdefghijklmnop"
