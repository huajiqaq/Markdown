package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.GFMFlavour
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StreamingTailWorkContractTest {
    @Test
    fun should_keepCompleteTailLazy_when_displayNeedsNoRepair() {
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.beginStream()
        val document = parser.append("plain **closed** [link](/target)")
        val paragraph = document.children.filterIsInstance<Paragraph>().single()

        assertFalse(paragraph.isInlineParsed)
        assertTrue(paragraph.materializedChildrenSnapshot().isEmpty())
        assertTrue(paragraph.children.any { it is StrongEmphasis })
        assertEquals("/target", paragraph.children.filterIsInstance<Link>().single().destination)

        parser.append(" and **open")
        val repaired = parser.document.children.filterIsInstance<Paragraph>().single()
        assertEquals(2, repaired.children.filterIsInstance<StrongEmphasis>().size)
        assertEditedDocument(parser, "plain **closed** [link](/target) and **open", parser.endStream())
    }

    @Test
    fun should_useCurrentDefinitionsForRepairedAndLazyContent_when_definitionBindingsChange() {
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.beginStream()
        val original = "[ref]: /old\n\n[read][ref] **open"
        parser.append(original)
        val definitions = parser.document.linkDefinitions
        val oldParagraph = parser.document.children.filterIsInstance<Paragraph>().single()
        assertEquals("/old", oldParagraph.children.filterIsInstance<Link>().single().destination)

        parser.replace(original.indexOf("/old"), 4, "/new")
        assertNotSame(definitions, parser.document.linkDefinitions)
        val newParagraph = parser.document.children.filterIsInstance<Paragraph>().single()
        assertEquals("/new", newParagraph.children.filterIsInstance<Link>().single().destination)
        assertEquals("/old", oldParagraph.children.filterIsInstance<Link>().single().destination)

        val currentDefinitions = parser.document.linkDefinitions
        parser.append("**")
        val complete = parser.document.children.filterIsInstance<Paragraph>().single()
        assertSame(currentDefinitions, parser.document.linkDefinitions)
        assertTrue(complete.materializedChildrenSnapshot().isEmpty())
        assertEquals("/new", complete.children.filterIsInstance<Link>().single().destination)
        assertEditedDocument(parser, original.replace("/old", "/new") + "**", parser.endStream())
    }

    @Test
    fun should_preserveFinishedRowDisplayRepairs_when_tableContinuesOrEndsAtANewline() {
        val parser = MarkdownParser(GFMFlavour)
        parser.beginStream()
        val source = "| A | **heading |\n|---|---|\n| first | **finished |\n| second | **open"
        val table = parser.append(source).children.filterIsInstance<Table>().single()
        val header = table.children.filterIsInstance<TableHead>().single()
        val headerCell = (header.children.single() as TableRow).children.last() as TableCell
        val rows = table.children.filterIsInstance<TableBody>().single().children.filterIsInstance<TableRow>()
        val finishedCell = rows.first().children.last() as TableCell
        val appendableCell = rows.last().children.last() as TableCell

        assertTrue(headerCell.children.any { it is StrongEmphasis })
        assertTrue(finishedCell.children.any { it is StrongEmphasis })
        assertTrue(appendableCell.children.any { it is StrongEmphasis })

        parser.append("\n")
        val newlineTable = parser.document.children.filterIsInstance<Table>().single()
        val finalRow = newlineTable.children.filterIsInstance<TableBody>().single().children.last() as TableRow
        val finalCell = finalRow.children.last() as TableCell
        assertTrue(finalCell.children.any { it is StrongEmphasis })
        assertEditedDocument(parser, source + "\n", parser.endStream())
    }

    @Test
    fun should_keepAllCompleteTableCellsLazy_when_tableEndsAtANewline() {
        val parser = MarkdownParser(GFMFlavour)
        parser.beginStream()
        val source = "| A | B |\n|---|---|\n" + (0 until 32).joinToString("") { "| row $it | **closed** |\n" }
        val table = parser.append(source).children.filterIsInstance<Table>().single()
        for (section in table.children.filterIsInstance<ContainerNode>()) {
            for (row in section.children.filterIsInstance<TableRow>()) {
                for (cell in row.children.filterIsInstance<TableCell>()) {
                    assertFalse(cell.isInlineParsed)
                    assertTrue(cell.materializedChildrenSnapshot().isEmpty())
                }
            }
        }
        assertEditedDocument(parser, source, parser.endStream())
    }

    @Test
    fun should_numberFootnotesOnAppend_when_completeTailNeedsNoRepair() {
        val parser = MarkdownParser()
        parser.beginStream()
        val source = "[^a]: first\n\n[^b]: second\n\nA[^a] and B[^b]"
        val paragraph = parser.append(source).children.filterIsInstance<Paragraph>().single()
        assertTrue(paragraph.materializedChildrenSnapshot().isNotEmpty())
        assertEquals(1, parser.document.footnoteDefinitions.getValue("a").index)
        assertEquals(2, parser.document.footnoteDefinitions.getValue("b").index)

        val references = paragraph.children.filterIsInstance<FootnoteReference>()
        assertEquals(listOf("a", "b"), references.map { it.label })
        assertEquals(listOf(1, 2), references.map { it.index })
        val fresh = MarkdownParser().apply { beginStream(); append(source) }
        // Observe both revisions in the same order before comparing their semantic snapshots.
        val freshParagraph = fresh.document.children.filterIsInstance<Paragraph>().single()
        assertEquals(references.map { it.index }, freshParagraph.children.filterIsInstance<FootnoteReference>().map { it.index })
        assertEquals(fresh.document.semanticSnapshot(), parser.document.semanticSnapshot())

        parser.append(" tail")
        assertEditedDocument(parser, source + " tail", parser.endStream())
    }

    @Test
    fun should_resolveReferencesInTransientSetextDisplay_when_headingIsDowngraded() {
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.beginStream()
        val source = "[ref]: /target\n\n[read][ref]\n-"
        val paragraph = parser.append(source).children.filterIsInstance<Paragraph>().single()
        assertEquals("/target", paragraph.children.filterIsInstance<Link>().single().destination)
        assertEditedDocument(parser, source, parser.endStream())
    }

    @Test
    fun should_preserveImmediateFootnoteNumbers_when_caretComesFromParsedBracketContent() {
        for (reference in listOf("[\\^n]", "[`^n`]")) {
            val source = "[^n]: note\n\nA$reference"
            val parser = MarkdownParser()
            parser.beginStream()
            parser.append(source)
            assertEquals(1, parser.document.footnoteDefinitions.getValue("n").index, reference)
            val paragraph = parser.document.children.filterIsInstance<Paragraph>().single()
            assertEquals(1, paragraph.children.filterIsInstance<FootnoteReference>().single().index)
            assertEditedDocument(parser, source, parser.endStream())
        }
    }
}
