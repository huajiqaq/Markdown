package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.ContainerNode
import com.hrm.markdown.parser.ast.Node
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.GFMFlavour
import com.hrm.markdown.parser.flavour.MarkdownExtraFlavour
import com.hrm.markdown.parser.incremental.EditOperation
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.random.Random
import kotlin.test.*

class IncrementalSessionLifecycleTest {
    @Test
    fun should_clearPreviousDiagnostics_when_beginningANewStream() {
        val parser = MarkdownParser(enableLinting = true, appendCoalesceThreshold = 16)
        parser.parse("# Title\n\n### Skipped level\n\ntext[^missing]")
        val previousDiagnostics = parser.diagnostics
        assertTrue(previousDiagnostics.diagnostics.isNotEmpty())

        parser.beginStream()
        assertEquals(emptyList(), parser.document.diagnostics.diagnostics)
        assertEquals(emptyList(), parser.diagnostics.diagnostics)
        parser.append("buffered")
        assertEquals(emptyList(), parser.diagnostics.diagnostics)
        assertTrue(previousDiagnostics.diagnostics.isNotEmpty(), "A new session must not mutate old diagnostics")
    }

    @Test
    fun should_matchCommittedInputAfterEveryTransition_when_sessionsEditsAndBuffersAreInterleaved() {
        val fragments = listOf("", "x", "中文😀", "\r", "\n", "\r\n", "\u0000", "**", "```\n", "[ref]: /new\n", "\n{#fixed}\n", "> ", "[^n]", " tail")
        var checked = 0
        for (flavour in listOf(CommonMarkFlavour, GFMFlavour, ExtendedFlavour, MarkdownExtraFlavour)) {
            for (threshold in listOf(0, 16)) for (seed in listOf(17, 83, 2026)) {
                val random = Random(seed)
                val parser = MarkdownParser(flavour, enableLinting = true, appendCoalesceThreshold = threshold)
                var source = "# Initial\n\nfirst **paragraph**\n\nlast"
                // Model the received and published inputs separately so buffered updates are observable.
                var committed = source
                var streaming = false
                // Cancellation preserves display repairs even though the session is no longer streaming.
                var repaired = false
                var rawCarriageReturn = false
                val history = mutableListOf<String>()
                parser.parse(source)

                repeat(128) { step ->
                    val fragment = fragments.random(random)
                    val offset = random.nextInt(source.length + 1)
                    val length = random.nextInt(minOf(6, source.length - offset) + 1)
                    when (random.nextInt(10)) {
                        0 -> {
                            history += "begin"
                            parser.beginStream()
                            source = ""; committed = ""; streaming = true; repaired = true; rawCarriageReturn = false
                        }
                        1 -> {
                            val input = incrementalSyntaxSamples.random(random)
                            history += "parse(${input.escape()})"
                            parser.parse(input)
                            source = normalizedTestInput(input); committed = source
                            streaming = false; repaired = false; rawCarriageReturn = false
                        }
                        2, 3 -> {
                            history += "append(${fragment.escape()})"
                            val skipLf = rawCarriageReturn && fragment.startsWith('\n')
                            val added = normalizedTestInput(if (skipLf) fragment.drop(1) else fragment)
                            if (fragment.isNotEmpty()) rawCarriageReturn = fragment.endsWith('\r')
                            source += added
                            parser.append(fragment)
                            if (added.isNotEmpty() && (threshold == 0 || '\n' in added || source.length - committed.length >= threshold)) {
                                committed = source; repaired = streaming
                            }
                        }
                        4, 5, 6 -> {
                            val previousSource = source
                            val operation = when (step % 3) {
                                0 -> EditOperation.Insert(offset, fragment)
                                1 -> EditOperation.Delete(offset, length)
                                else -> EditOperation.Replace(offset, length, fragment)
                            }
                            history += operation.toString().escape()
                            parser.applyEdit(operation)
                            source = when (operation) {
                                is EditOperation.Insert -> source.replaceRange(offset, offset, normalizedTestInput(fragment))
                                is EditOperation.Delete -> source.removeRange(offset, offset + length)
                                is EditOperation.Replace -> source.replaceRange(offset, offset + length, normalizedTestInput(fragment))
                                is EditOperation.Append -> error("not used")
                            }
                            committed = source; repaired = streaming
                            if (!streaming && source != previousSource) rawCarriageReturn = false
                        }
                        7 -> {
                            history += "end"
                            parser.endStream()
                            committed = source; streaming = false; repaired = false; rawCarriageReturn = false
                        }
                        8 -> {
                            history += "abort"
                            parser.abort()
                            if (source != committed) { committed = source; repaired = streaming }
                            streaming = false; rawCarriageReturn = false
                        }
                        else -> {
                            history += "invalid edit"
                            val document = parser.document
                            val sourceText = parser.sourceText
                            assertFailsWith<IllegalArgumentException> { parser.delete(offset, Int.MAX_VALUE) }
                            assertSame(document, parser.document)
                            assertSame(sourceText, parser.sourceText)
                        }
                    }
                    val context = "flavour=${flavour::class.simpleName} threshold=$threshold seed=$seed step=$step\n" + history.joinToString("\n")
                    assertEquals(source, parser.currentText(), "$context source")
                    assertEquals(source, parser.sourceText.content, "$context sourceText")
                    assertEquals(streaming, parser.isStreaming, "$context mode")
                    val expected = if (repaired) MarkdownParser(flavour, enableLinting = true).apply {
                        beginStream(); append(committed)
                    }.document else referenceDocument(parser, committed)
                    assertEquals(expected.semanticSnapshot(), parser.document.semanticSnapshot(), "$context AST")
                    assertEquals(expected.diagnostics.diagnostics, parser.document.diagnostics.diagnostics, "$context diagnostics")
                    assertEquals(expected.diagnostics.diagnostics, parser.diagnostics.diagnostics, "$context public diagnostics")
                    // An untouched empty streaming root has no source extent yet; compare all actual blocks.
                    assertEquals(expected.children.size, parser.document.children.size, context)
                    expected.children.zip(parser.document.children).forEach { (expectedNode, actualNode) ->
                        assertNodeCoordinatesEqual(expectedNode, actualNode, context)
                    }
                    assertParents(parser.document, context)
                    checked++
                }
                assertEditedDocument(parser, source, parser.endStream())
            }
        }
        println("mixed session lifecycle transitions=$checked")
    }

    private fun String.escape() = replace("\n", "\\n").replace("\r", "\\r")

    private fun assertParents(node: Node, context: String) {
        if (node is ContainerNode) node.children.forEach { child ->
            assertSame(node, child.parent, "$context ${child::class.simpleName} parent")
            assertParents(child, context)
        }
    }
}
