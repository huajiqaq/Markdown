package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.ast.FootnoteReference
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.block.postprocessors.BlockPostProcessor
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.hrm.markdown.parser.inline.InlineParser
import com.hrm.markdown.parser.incremental.EditOperation
import com.hrm.markdown.parser.incremental.IncrementalEngine
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertSame

class ParserMutationIsolationTest {
    @Test
    fun should_keepBuiltInMiddleInsertionsLocal_when_stableDefinitionsAndSuffixGrow() {
        for (count in listOf(100, 1000)) {
            val source = "[ref]: /target\n\n" +
                List(count) { "paragraph $it [read][ref]" }.joinToString("\n\n")
            val engine = IncrementalEngine()
            engine.fullParse(source)
            val first = engine.document.children.first()
            val last = engine.document.children.last()
            val offset = source.indexOf("paragraph ${count / 2}") + 10
            engine.applyEdit(EditOperation.Insert(offset, "x"))
            assertNull(engine.lastUpdate.fullParseReason)
            assertTrue(engine.lastUpdate.parsedLines <= 4)
            assertSame(first, engine.document.children.first())
            assertSame(last, engine.document.children.last())
            val updated = source.replaceRange(offset, offset, "x")
            assertEquals(referenceDocument(MarkdownParser(), updated).semanticSnapshot(), engine.document.semanticSnapshot())
        }
    }

    @Test
    fun should_rejectRecursiveParserUpdates_when_aBlockCallbackRuns() {
        lateinit var parser: MarkdownParser
        var attempt = false
        var rejected = 0
        val processor = BlockPostProcessor { block ->
            if (attempt) {
                assertFailsWith<IllegalStateException> { parser.append("unrecorded") }
                assertFailsWith<IllegalStateException> { parser.parse("replacement") }
                rejected++
            }
            block
        }
        val flavour = object : MarkdownFlavour by CommonMarkFlavour {
            override val postProcessors = listOf(processor)
        }
        parser = MarkdownParser(flavour)
        parser.parse("prefix\n\ntail")
        attempt = true
        parser.replace(8, 4, "next")
        assertTrue(rejected > 0)
        assertEquals("prefix\n\nnext", parser.currentText())
    }

    @Test
    fun should_rejectPublicInlineNumbering_when_definitionsBelongToAPublishedDocument() {
        val published = MarkdownParser().parse("[^n]: note")
        val definition = published.footnoteDefinitions.getValue("n")
        val builder = Document()
        builder.footnoteDefinitions["n"] = definition
        assertFailsWith<IllegalStateException> {
            InlineParser(builder).parseInlines("[^n]", Paragraph())
        }
        assertEquals(0, definition.index)
        assertFailsWith<IllegalStateException> {
            InlineParser(published).parseInlines("[^n]", Paragraph())
        }
        assertEquals(0, definition.index)
    }

    @Test
    fun should_assignPublishedFootnoteNumbers_when_trustedInlineContentIsRead() {
        val document = MarkdownParser().parse("use[^n]\n\n[^n]: note")
        val paragraph = document.children.filterIsInstance<Paragraph>().first()
        val reference = paragraph.children.filterIsInstance<FootnoteReference>().single()
        assertEquals(1, reference.index)
        assertEquals(1, document.footnoteDefinitions.getValue("n").index)
    }
}
