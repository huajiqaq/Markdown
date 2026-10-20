package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.flavour.GFMFlavour
import com.hrm.markdown.parser.incremental.containsDefinition
import kotlin.test.*

class MetadataTraversalRegressionTest {
    @Test
    fun should_findNestedDefinitionsWithoutParsingInlineContent_when_traversingMetadata() {
        for (definition in listOf(LinkReferenceDefinition(), FootnoteDefinition(), AbbreviationDefinition(), BibliographyDefinition())) {
            val lazy = Paragraph().apply {
                setLazyInlineContent("[text][ref]") { _, _ -> error("Metadata must not force inline parsing") }
            }
            val nested = BlockQuote().apply { appendChild(definition) }
            val root = BlockQuote().apply { appendChild(lazy); appendChild(nested) }

            assertTrue(containsDefinition(root), definition::class.simpleName)
            assertFalse(lazy.isInlineParsed)
            nested.removeChild(definition)
            assertFalse(containsDefinition(root))
            assertFalse(lazy.isInlineParsed)
        }
    }

    @Test
    fun should_preserveLazyTableCells_when_fullParsingAndReusingMetadata() {
        val parser = MarkdownParser(GFMFlavour)
        val source = "prefix\n\n| A | B |\n|---|---|\n| **bold** | [link](/target) |\n\nsuffix"
        val table = parser.parse(source).children.filterIsInstance<Table>().single()
        val cells = table.children.flatMap { (it as ContainerNode).children }
            .flatMap { (it as TableRow).children }.map { it as TableCell }
        assertTrue(cells.isNotEmpty())
        assertTrue(cells.all { !it.isInlineParsed })

        val updated = parser.replace(0, "prefix".length, "longer prefix")
        assertSame(table, updated.children.filterIsInstance<Table>().single())
        assertTrue(cells.all { !it.isInlineParsed })
        assertEditedDocument(parser, source.replaceFirst("prefix", "longer prefix"), updated)
    }

    @Test
    fun should_publishProcessedMembershipAndKeepOldLists_when_processorsRemoveAndReplaceBlocks() {
        val parser = MarkdownParser()
        var source = "# Same\n\n{#fixed}\n\n![caption](/first.png)\n\n{.image}\n\n```mermaid\ngraph TD\nA-->B\n```\n\n# Same\n\nsuffix"
        val published = parser.parse(source).children
        val originalNodes = published.toList()
        val expectedTypes = listOf(Heading::class, Figure::class, DiagramBlock::class, Heading::class, Paragraph::class)
        assertEquals(expectedTypes, published.map { it::class })
        assertEditedDocument(parser, source)

        for ((old, replacement) in listOf("/first.png" to "/updated-image.png", "graph TD" to "graph LR", "suffix" to "tail")) {
            val offset = source.indexOf(old)
            source = source.replaceRange(offset, offset + old.length, replacement)
            assertEditedDocument(parser, source, parser.replace(offset, old.length, replacement))
            assertEquals(expectedTypes, parser.document.children.map { it::class })
            assertEquals(originalNodes.size, published.size)
            originalNodes.forEachIndexed { index, node -> assertSame(node, published[index]) }
        }
    }

    @Test
    fun should_reindexRetainedHeadings_when_replacementContainsNoHeadings() {
        val parser = MarkdownParser()
        var source = "# Same\n\n> # Same\n\nlast"
        parser.parse(source)
        val quote = parser.document.children.filterIsInstance<BlockQuote>().single()
        val heading = quote.children.single() as Heading
        assertEquals("same-1", heading.id)

        for ((old, replacement) in listOf("# Same" to "plain", "plain" to "longer plain")) {
            val offset = source.indexOf(old)
            source = source.replaceRange(offset, offset + old.length, replacement)
            assertEditedDocument(parser, source, parser.replace(offset, old.length, replacement))
            assertSame(quote, parser.document.children.filterIsInstance<BlockQuote>().single())
            assertEquals("same", heading.id)
        }
    }
}
