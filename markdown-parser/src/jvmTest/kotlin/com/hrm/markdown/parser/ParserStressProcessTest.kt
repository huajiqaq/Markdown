package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.GFMFlavour
import com.hrm.markdown.parser.flavour.MarkdownExtraFlavour
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.*

/** Run explicitly with :markdown-parser:stressTest; catastrophic inputs never share the test JVM. */
class ParserStressProcessTest {
    @Test
    fun should_finishWithinBudget_when_blocksAndInlineContentAreDeeplyNested() = runScenario("deep-nesting")

    @Test
    fun should_finishWithinBudget_when_oneLineContainsAMillionCharacters() = runScenario("long-line")

    @Test
    fun should_finishWithinBudget_when_delimitersAreDenseOrUnmatched() = runScenario("dense-delimiters")

    @Test
    fun should_finishWithinBudget_when_largeBlocksHaveNoClosingMarker() = runScenario("unclosed-blocks")

    @Test
    fun should_rejectFailedChild_when_processExitsWithoutCompletingScenario() {
        val result = launch("fixture-exit")
        assertEquals(17, result.exitCode)
        assertFailsWith<AssertionError> { result.assertSucceeded() }
    }

    @Test
    fun should_rejectIncompleteChild_when_exitCodeIsZeroButChecksDidNotRun() {
        val result = launch("fixture-noop")
        assertEquals(0, result.exitCode)
        assertFailsWith<AssertionError> { result.assertSucceeded() }
    }

    @Test
    fun should_killChildAndFail_when_deadlineExpires() {
        val result = launch("fixture-hang", timeoutMillis = 1_000)
        assertTrue(result.timedOut)
        assertFailsWith<AssertionError> { result.assertSucceeded() }
    }

    @Test
    fun should_rejectOutOfMemory_when_childExceedsItsHeapLimit() {
        val result = launch("fixture-oom", heapMiB = 32)
        assertFalse(result.timedOut)
        assertNotEquals(0, result.exitCode)
        assertTrue(result.logTail().contains("OutOfMemoryError"), result.logTail())
        assertFailsWith<AssertionError> { result.assertSucceeded() }
    }

    private fun runScenario(id: String) = launch(id).assertSucceeded()

    private fun launch(id: String, timeoutMillis: Long = 45_000, heapMiB: Int = 256): ChildResult {
        val directory = File(System.getProperty("markdown.stress.reportDir", "build/reports/parser-stress"))
        check(directory.isDirectory || directory.mkdirs())
        val log = File(directory, "$id.log")
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val classpath = System.getProperty("markdown.stress.classpath", System.getProperty("java.class.path"))
        val command = listOf(
            File(System.getProperty("java.home"), "bin/$executable").absolutePath,
            "-Xms16m", "-Xmx${heapMiB}m", "-Xss1m", "-XX:+ExitOnOutOfMemoryError",
            "-XX:MaxJavaStackTraceDepth=64", "-cp", classpath, ParserStressWorker::class.java.name, id,
        )
        val started = System.nanoTime()
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
        val timedOut: Boolean
        try {
            timedOut = !process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } finally {
            if (process.isAlive) {
                process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Unable to terminate stress child $id" }
            }
        }
        val result = ChildResult(id, process.exitValue(), timedOut, log)
        File(directory, "$id.txt").writeText(
            "case=$id\nheapMiB=$heapMiB\nstack=1m\ntimeoutMillis=$timeoutMillis\n" +
                "elapsedMillis=${(System.nanoTime() - started) / 1_000_000}\n" +
                "exitCode=${result.exitCode}\ntimedOut=$timedOut\n",
        )
        println("stress case=$id exit=${result.exitCode} timedOut=$timedOut log=${log.absolutePath}")
        return result
    }

    private data class ChildResult(val id: String, val exitCode: Int, val timedOut: Boolean, val log: File) {
        fun assertSucceeded() {
            val tail = logTail()
            assertFalse(timedOut, "$id exceeded its deadline; see $log\n$tail")
            assertEquals(0, exitCode, "$id crashed; see $log\n$tail")
            assertTrue(tail.contains("STRESS_PASS $id"), "$id did not finish its checks; see $log\n$tail")
        }

        fun logTail(): String = RandomAccessFile(log, "r").use { file ->
            val size = minOf(file.length(), 8_192L).toInt()
            file.seek(file.length() - size)
            ByteArray(size).also(file::readFully).toString(Charsets.UTF_8)
        }
    }
}

/** Generated inputs and AST checks are intentionally inside the bounded child process. */
object ParserStressWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        val id = args.single()
        println("STRESS_START $id maxHeapBytes=${Runtime.getRuntime().maxMemory()}")
        when (id) {
            "fixture-exit" -> exitProcess(17)
            "fixture-noop" -> return
            "fixture-hang" -> Thread.sleep(Long.MAX_VALUE)
            "fixture-oom" -> {
                val retained = mutableListOf<ByteArray>()
                while (true) retained += ByteArray(1024 * 1024)
            }
            "deep-nesting" -> {
                exercise("quotes-512", "> ".repeat(512) + "sentinel", minDepth = 512)
                exercise("emphasis-256", "*a ".repeat(256) + "sentinel" + " a*".repeat(256), minDepth = 256)
            }
            "long-line" -> exercise("unicode-line-1Mi", "中文🙂abcdefghijklmnopqrstuvwx".repeat(37_450))
            "dense-delimiters" -> {
                exercise("paired-16384", "**x** _y_ `z` [q](/target) ".repeat(16_384))
                exercise("unmatched-brackets-131072", "[".repeat(131_072) + "sentinel")
            }
            "unclosed-blocks" -> {
                val body = "value 0123456789\n".repeat(131_072)
                exercise("fence-2Mi", "```text\n$body")
                exercise("html-2Mi", "<!--\n$body")
                exercise("math-512Ki", "$$\n" + "x + y\n".repeat(87_382), extendedOnly = true)
            }
            else -> error("Unknown stress scenario: $id")
        }
        println("STRESS_PASS $id")
    }

    private fun exercise(label: String, input: String, minDepth: Int = 0, extendedOnly: Boolean = false) {
        println("INPUT $label chars=${input.length}")
        val flavours = if (extendedOnly) listOf(ExtendedFlavour)
            else listOf(CommonMarkFlavour, GFMFlavour, ExtendedFlavour, MarkdownExtraFlavour)
        for (flavour in flavours) {
            val name = "$label/${flavour::class.simpleName}"
            val parser = MarkdownParser(flavour, appendCoalesceThreshold = 16)
            phase("$name/full") {
                val stats = inspect(parser.parse(input), parser.sourceText.lineCount)
                println("AST $name $stats")
                check(stats.depth >= minDepth) { "Nesting was silently lost: $stats" }
                check(stats.nodes > 1) { "Input was not parsed: $stats" }
                check(parser.currentText() == input)
                verifyContent(label, parser.document, input, stats)
            }
            phase("$name/edit") {
                parser.insert(input.length, " tail")
                val stats = inspect(parser.document, parser.sourceText.lineCount)
                check(parser.currentText() == input + " tail")
                verifyContent(label, parser.document, input + " tail", stats)
            }
            phase("$name/stream") {
                parser.beginStream()
                parser.append(input)
                inspect(parser.document, parser.sourceText.lineCount)
                val committed = parser.document
                check(parser.append(" tail") === committed) { "The 16-character buffer was bypassed" }
                check(parser.currentText() == input + " tail")
                val stats = inspect(parser.endStream(), parser.sourceText.lineCount)
                check(!parser.isStreaming)
                verifyContent(label, parser.document, input + " tail", stats)
            }
        }
    }

    private fun verifyContent(label: String, document: Document, input: String, stats: Stats) {
        when {
            label.startsWith("quotes") -> check(stats.quotes == 512)
            label.startsWith("emphasis") -> check(stats.emphases == 256)
            label.startsWith("paired") -> check(stats.emphases == 16_384 && stats.strong == 16_384 &&
                stats.codeSpans == 16_384 && stats.links == 16_384) { "Delimiter output was lost: $stats" }
            label.startsWith("unicode-line") || label.startsWith("unmatched-brackets") -> {
                val paragraph = document.children.single() as Paragraph
                check((paragraph.children.single() as Text).literal == input) { "Plain text was truncated" }
            }
            label.startsWith("fence") -> {
                val body = input.substringAfter('\n')
                val expected = if (body.endsWith('\n')) body else "$body\n"
                check((document.children.single() as FencedCodeBlock).literal == expected) { "Code was truncated" }
            }
            label.startsWith("html") -> {
                val literal = (document.children.single() as HtmlBlock).literal
                check(literal == input || literal == input.removeSuffix("\n")) { "HTML was truncated" }
            }
            label.startsWith("math") -> check((document.children.single() as MathBlock).literal.trimEnd('\n') ==
                input.substringAfter('\n').trimEnd('\n')) { "Math was truncated" }
        }
    }

    private data class Stats(
        val nodes: Int, val depth: Int, val quotes: Int, val emphases: Int,
        val strong: Int, val codeSpans: Int, val links: Int,
    )

    /** Do not let a recursive test helper become the source of stack overflow. Forces lazy inlines. */
    private fun inspect(document: Document, lines: Int): Stats {
        val seen = Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
        val pending = ArrayDeque<Pair<Node, Int>>()
        pending.addLast(document to 0)
        var depth = 0
        val counts = IntArray(5)
        while (pending.isNotEmpty()) {
            val (node, level) = pending.removeLast()
            check(seen.add(node)) { "AST contains a cycle or a shared child" }
            depth = maxOf(depth, level)
            when (node) {
                is BlockQuote -> counts[0]++
                is Emphasis -> counts[1]++
                is StrongEmphasis -> counts[2]++
                is InlineCode -> counts[3]++
                is Link -> counts[4]++
                else -> Unit
            }
            check(node.lineRange.startLine >= 0 && node.lineRange.endLine <= lines) { "Invalid node coordinates" }
            if (node is ContainerNode) node.children.asReversed().forEach { child ->
                check(child.parent === node) { "Invalid parent for ${child.javaClass.simpleName}" }
                pending.addLast(child to level + 1)
            }
        }
        return Stats(seen.size, depth, counts[0], counts[1], counts[2], counts[3], counts[4])
    }

    private inline fun phase(name: String, action: () -> Unit) {
        println("PHASE $name")
        val start = System.nanoTime()
        action()
        println("DONE $name elapsedMillis=${(System.nanoTime() - start) / 1_000_000}")
    }
}
