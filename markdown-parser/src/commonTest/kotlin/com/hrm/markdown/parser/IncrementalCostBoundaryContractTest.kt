package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.ContainerNode
import com.hrm.markdown.parser.ast.FencedCodeBlock
import com.hrm.markdown.parser.ast.Link
import com.hrm.markdown.parser.ast.Node
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.ast.withDocumentMutation
import com.hrm.markdown.parser.block.OpenBlock
import com.hrm.markdown.parser.block.starters.BlockStarter
import com.hrm.markdown.parser.core.LineCursor
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.hrm.markdown.parser.incremental.EditOperation
import com.hrm.markdown.parser.incremental.IncrementalEngine
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IncrementalCostBoundaryContractTest {
    @Test
    fun should_rebuildPositionDependentExtensionsAndRetainEqualLengthReuse_when_middleEditsChangeCoordinates() {
        var source = (List(3) { "prefix $it **bold**\ncontinued [link](/prefix/$it)" } +
            "middle **old**\ncontinued [link](/middle)" +
            List(6) { "suffix $it **bold**\ncontinued [link](/suffix/$it)" }).joinToString("\n\n")
        val positions = PositionedParagraphInput(source)
        val engine = IncrementalEngine(positions.flavour)
        engine.fullParse(source).semanticSnapshot()
        val replacements = listOf("**old**" to "**lengthened**", "middle " to "middle \nextra ",
            "\nextra " to "", "**lengthened**" to "**x**", "**x**" to "**y**")
        for ((removed, inserted) in replacements) {
            val first = engine.document.children.first()
            val suffix = engine.document.children.filterIsInstance<Paragraph>()
                .filter { it.rawContent.orEmpty().startsWith("suffix ") }
            suffix.forEach(::materializePositionedDescendants)
            val descendants = suffix.map(::descendantNodes)
            val offset = source.indexOf(removed)
            assertTrue(offset >= 0)
            source = source.replaceRange(offset, offset + removed.length, inserted)
            positions.update(source)
            val actual = engine.applyEdit(EditOperation.Replace(offset, removed.length, inserted))
            val context = "$removed -> $inserted"
            val coordinatesChanged = removed.length != inserted.length ||
                removed.count { it == '\n' } != inserted.count { it == '\n' }
            if (coordinatesChanged) {
                assertEquals("extension source positions changed", engine.lastUpdate.fullParseReason, context)
                assertEquals(engine.sourceText.lineCount, engine.lastUpdate.parsedLines, context)
                assertNotSame(first, actual.children.first(), context)
            } else {
                assertNull(engine.lastUpdate.fullParseReason, context)
                assertTrue(engine.lastUpdate.parsedLines <= 8, "$context ${engine.lastUpdate}")
                assertTrue(engine.lastUpdate.reusedSuffixRegions > 0, context)
                assertSame(first, actual.children.first(), context)
            }
            val actualSuffix = actual.children.filterIsInstance<Paragraph>().filter { it.rawContent.orEmpty().startsWith("suffix ") }
            actualSuffix.forEach(::materializePositionedDescendants)
            assertEquals(suffix.size, actualSuffix.size)
            for (index in suffix.indices) {
                val actualDescendants = descendantNodes(actualSuffix[index])
                assertEquals(descendants[index].size, actualDescendants.size)
                if (coordinatesChanged) {
                    assertNotSame(suffix[index], actualSuffix[index], context)
                } else {
                    assertSame(suffix[index], actualSuffix[index], context)
                    descendants[index].zip(actualDescendants).forEach { (before, after) -> assertSame(before, after, context) }
                }
                assertSame(actual, actualSuffix[index].parent, context)
            }

            val expected = referenceDocument(MarkdownParser(positions.flavour), source)
            expected.children.filterIsInstance<Paragraph>()
                .filter { it.rawContent.orEmpty().startsWith("suffix ") }
                .forEach(::materializePositionedDescendants)
            assertEquals(expected.semanticSnapshot(), actual.semanticSnapshot(), context)
            assertNodeCoordinatesEqual(expected, actual, context)
            assertEquals(source, engine.currentText(), context)
        }
    }

    @Test
    fun should_reportDefinitionInvalidation_when_realDeclarationsChangeButLiteralMarkersDoNot() {
        var source = "use [read][ref]\n\n[ref]: /old\n\n[ref]: /shadow\n\nordinary ]: punctuation\n\nstable tail"
        val engine = IncrementalEngine()
        engine.fullParse(source).semanticSnapshot()
        val originalDefinitions = engine.document.linkDefinitions
        val originalFirst = engine.document.children.first()
        val originalTail = engine.document.children.last()

        val markerOffset = source.indexOf("ordinary")
        source = source.replaceRange(markerOffset, markerOffset + 1, "O")
        engine.applyEdit(EditOperation.Replace(markerOffset, 1, "O"))
        assertNull(engine.lastUpdate.fullParseReason)
        assertSame(originalDefinitions, engine.document.linkDefinitions)
        assertSame(originalFirst, engine.document.children.first())
        assertSame(originalTail, engine.document.children.last())
        assertMatchesFull(engine, source)

        for ((old, replacement, target) in listOf(
            Triple("/shadow", "/hidden", "/old"),
            Triple("/old", "/new", "/new"),
            Triple("[ref]: /new\n\n", "", "/hidden"),
        )) {
            val previousDefinitions = engine.document.linkDefinitions
            val offset = source.indexOf(old)
            source = source.replaceRange(offset, offset + old.length, replacement)
            engine.applyEdit(EditOperation.Replace(offset, old.length, replacement))
            assertEquals("definitions changed", engine.lastUpdate.fullParseReason)
            assertEquals(engine.sourceText.lineCount, engine.lastUpdate.parsedLines)
            assertNotSame(previousDefinitions, engine.document.linkDefinitions)
            assertEquals(target, engine.document.linkDefinitions.getValue("ref").destination)
            val paragraph = engine.document.children.filterIsInstance<Paragraph>().first()
            assertEquals(target, paragraph.children.filterIsInstance<Link>().single().destination)
            assertMatchesFull(engine, source)
        }

        source = "A[^a], B[^b]\n\n[^a]: first\n\n[^b]: second"
        engine.fullParse(source).semanticSnapshot()
        val offset = source.indexOf("[^a]")
        source = source.removeRange(offset, offset + 4)
        engine.applyEdit(EditOperation.Delete(offset, 4))
        assertEquals("footnote order changed", engine.lastUpdate.fullParseReason)
        assertMatchesFull(engine, source)
        assertEquals(0, engine.document.footnoteDefinitions.getValue("a").index)
        assertEquals(1, engine.document.footnoteDefinitions.getValue("b").index)
    }

    @Test
    fun should_keepCheckpointWorkBounded_when_onlyTheFixedTailChangesAfterPrefixMaterialization() {
        for (count in listOf(16, 256, 4096)) {
            var source = List(count) { "paragraph $it **bold** [link](/$it)" }.joinToString("\n\n") + "\n\ntail"
            val engine = IncrementalEngine(CommonMarkFlavour)
            engine.fullParse(source).semanticSnapshot()
            val first = engine.document.children.first()
            repeat(3) {
                source += " more"
                engine.applyEdit(EditOperation.Append(" more"))
                assertNull(engine.lastUpdate.fullParseReason)
                assertTrue(engine.lastUpdate.parsedLines <= 4, "count=$count ${engine.lastUpdate}")
                assertSame(first, engine.document.children.first())
            }
            assertEquals(referenceDocument(MarkdownParser(CommonMarkFlavour), source).semanticSnapshot(), engine.document.semanticSnapshot())
        }
    }

    @Test
    fun should_preserveEveryPublishedBody_when_aLongFenceCloserArrivesOneCharacterAtATime() {
        // This is a semantic boundary test. Rechecking the growing candidate line can still
        // require increasing work; no linear-time or constant-allocation assertion belongs here.
        for (flavour in listOf(CommonMarkFlavour, ExtendedFlavour)) for (marker in listOf('`', '~')) {
            val fence = marker.toString().repeat(257)
            val opening = fence + "kotlin " + "option".repeat(512) + "\n"
            var source = opening + "body\n"
            val engine = IncrementalEngine(flavour)
            engine.beginStream()
            engine.append(source)
            val samples = setOf(1, 64, 65, fence.length - 1, fence.length)
            repeat(fence.length) { index ->
                source += marker
                engine.append(marker.toString())
                val received = index + 1
                val code = engine.document.children.filterIsInstance<FencedCodeBlock>().single()
                val expectedBody = if (received == fence.length) "body\n" else "body\n" + marker.toString().repeat(received) + "\n"
                assertEquals(expectedBody, code.literal, "marker=$marker received=$received")
                if (received in samples) {
                    val fresh = MarkdownParser(flavour).apply { beginStream(); append(source) }
                    assertEquals(fresh.document.semanticSnapshot(), engine.document.semanticSnapshot())
                    assertNodeCoordinatesEqual(fresh.document, engine.document)
                }
            }
            source += "\n# after"
            engine.append("\n# after")
            assertEquals(referenceDocument(MarkdownParser(flavour), source).semanticSnapshot(), engine.endStream().semanticSnapshot())
        }
    }

    private fun assertMatchesFull(engine: IncrementalEngine, source: String) {
        val expected = referenceDocument(MarkdownParser(), source)
        assertEquals(expected.semanticSnapshot(), engine.document.semanticSnapshot())
        assertNodeCoordinatesEqual(expected, engine.document)
        assertEquals(source, engine.currentText())
    }

    private fun descendantNodes(node: ContainerNode): List<Node> = buildList {
        fun visit(container: ContainerNode) {
            for (child in container.children) {
                add(child)
                if (child is ContainerNode) visit(child)
            }
        }
        visit(node)
    }
}

/** Supply absolute coordinates through the public block extension point before indexing. */
internal class PositionedParagraphInput(source: String) {
    private var lineStarts = intArrayOf(0)

    init { update(source) }

    fun update(source: String) {
        lineStarts = buildList {
            add(0)
            source.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }.toIntArray()
    }

    private val coordinates = object : BlockStarter {
        override val priority = Int.MIN_VALUE
        override val canInterruptParagraph = true

        override fun tryStart(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
            val paragraph = tip.node as? Paragraph ?: return null
            val first = tip.contentStartLine
            paragraph.sourceRange = SourceRange(
                SourcePosition(first, 0, lineStarts[first]),
                SourcePosition(lineIdx, cursor.line.length, lineStarts[lineIdx] + cursor.line.length),
            )
            return null
        }
    }

    val flavour = CommonMarkFlavour.copy(
        blockStarters = listOf(coordinates) + CommonMarkFlavour.blockStarters
    )
}

/** Descendants start inside an already-positioned block, so the index knows shifts are needed. */
internal fun materializePositionedDescendants(paragraph: Paragraph) {
    val lineRange = paragraph.lineRange
    val sourceRange = paragraph.sourceRange
    check(sourceRange != SourceRange.EMPTY)
    fun visit(container: ContainerNode) {
        for (child in container.children) {
            child.lineRange = lineRange
            child.sourceRange = sourceRange
            if (child is ContainerNode) visit(child)
        }
    }
    val document = paragraph.parent as? com.hrm.markdown.parser.ast.Document
    if (document == null) visit(paragraph) else withDocumentMutation(document) { visit(paragraph) }
}
