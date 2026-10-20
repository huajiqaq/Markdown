package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.AbbreviationDefinition
import com.hrm.markdown.parser.ast.Link
import com.hrm.markdown.parser.ast.LinkReferenceDefinition
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.block.BlockParser
import com.hrm.markdown.parser.block.OpenBlock
import com.hrm.markdown.parser.block.starters.BlockStarter
import com.hrm.markdown.parser.core.LineCursor
import com.hrm.markdown.parser.core.SourceText
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.hrm.markdown.parser.incremental.EditOperation
import com.hrm.markdown.parser.inline.InlineParser
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IncrementalBoundaryRegressionTest {
    @Test
    fun should_preserveLooseListAndCoordinates_when_separatorEditsChangeContainerBoundaries() {
        val parser = MarkdownParser()
        parser.parse("# Title\n\n- first\n- second")
        parser.insert(25, "\r\n\r\n")
        assertEditedDocument(parser, "# Title\n\n\n- second\n\n", parser.delete(9, 7))

        val source = "Term\n: Definition\n\n==mark=="
        parser.parse(source)
        assertEditedDocument(parser, source.replaceRange(19, 19, "\n\n"), parser.insert(19, "\r\n\r\n"))
    }

    @Test
    fun should_continuePastOldSuffix_when_removingFenceClosureChangesFollowingBlocks() {
        val source = "before\n\n```kotlin\ncode\n```\n\nafter\n\nend"
        val closingOffset = source.indexOf("```", source.indexOf("code"))
        val parser = MarkdownParser()
        parser.parse(source)
        val opened = source.removeRange(closingOffset, closingOffset + 3)
        assertEditedDocument(parser, opened, parser.delete(closingOffset, 3))
        assertEditedDocument(parser, source, parser.insert(closingOffset, "```"))
    }

    @Test
    fun should_returnDefinitionsWithSourceLines_when_parsingARange() {
        val source = SourceText.of("before\n\n[ref]: /new\n\n*[API]: Interface\n")
        val blocks = BlockParser(source).parseLines(2, source.lineCount)
        val link = blocks.filterIsInstance<LinkReferenceDefinition>().single()
        val abbreviation = blocks.filterIsInstance<AbbreviationDefinition>().single()
        assertEquals(LineRange(2, 3), link.lineRange)
        assertEquals(LineRange(4, 5), abbreviation.lineRange)
        assertEquals(source.contentHash(link.lineRange), link.contentHash)
        assertEquals(source.contentHash(abbreviation.lineRange), abbreviation.contentHash)
    }

    @Test
    fun should_preserveEarlierLazyReferences_when_reusingBlockParserForAnotherRange() {
        val source = SourceText.of("[ref]: /first\n[read][ref]\n\n[ref]: /second\n[read][ref]\n")
        val parser = BlockParser(source, inlineParserFactory = { InlineParser(it) })
        val first = parser.parseLines(0, 3).filterIsInstance<Paragraph>().single()
        val second = parser.parseLines(3, source.lineCount).filterIsInstance<Paragraph>().single()
        assertEquals("/first", first.children.filterIsInstance<Link>().single().destination)
        parser.parse()
        assertEquals("/second", second.children.filterIsInstance<Link>().single().destination)
    }

    @Test
    fun should_useUpdatedBoundariesAndDiagnostics_when_appendingAfterEditRemovesEarlierBlocks() {
        val parser = MarkdownParser(enableLinting = true)
        parser.parse("a\n\nb\n\n```\nc\n```")
        assertEditedDocument(parser, "```\nc\n```", parser.delete(0, 6))
        assertEditedDocument(parser, "```\nc\n``` tail", parser.applyEdit(EditOperation.Append(" tail")))

        parser.parse("one\n\ntwo\n\n***")
        assertEquals(LineRange(4, 5), parser.document.children.last().lineRange)
        assertEditedDocument(parser, "one\n\ntwo\n\n****", parser.applyEdit(EditOperation.Append("*")))
        assertEditedDocument(parser, "oxne\n\ntwo\n\n****", parser.insert(1, "x"))
    }

    @Test
    fun should_resolveShadowedDefinition_when_firstMaterializedDefinitionIsChangedThenDeleted() {
        val parser = MarkdownParser(enableLinting = true)
        val original = "[read][ref]\n\n[ref]: /old\n\n[ref]: /fallback\n"
        parser.parse(original).semanticSnapshot()
        val offset = original.indexOf("/old")
        val changed = original.replaceRange(offset, offset + 4, "/new")
        assertEditedDocument(parser, changed, parser.replace(offset, 4, "/new"))

        val firstDefinition = "[ref]: /new\n\n"
        val start = changed.indexOf(firstDefinition)
        val remaining = changed.removeRange(start, start + firstDefinition.length)
        assertEditedDocument(parser, remaining, parser.delete(start, firstDefinition.length))
        assertEquals("/fallback", parser.document.linkDefinitions["ref"]?.destination)
    }

    @Test
    fun should_renumberRemainingFootnotes_when_editRemovesAPreviouslyMaterializedReference() {
        val source = "First[^a], then[^b].\n\n[^a]: Alpha\n\n[^b]: Beta"
        val parser = MarkdownParser(enableLinting = true)
        parser.parse(source).semanticSnapshot()
        val offset = source.indexOf("[^a]")
        assertEditedDocument(parser, source.removeRange(offset, offset + 4), parser.delete(offset, 4))
        assertEquals(0, parser.document.footnoteDefinitions["a"]?.index)
        assertEquals(1, parser.document.footnoteDefinitions["b"]?.index)
    }

    @Test
    fun should_updateMaterializedPrefix_when_definitionArrivesInLaterChunks() {
        val samples = listOf(
            listOf("[read][ref]\n\nplain\n\n", "[ref]: /", "new", "\n\n", "tail"),
            listOf("Text[^n].\n\nplain\n\n", "[^n]: ", "**note**", "\n\n", "tail"),
            listOf("Read API.\n\nplain\n\n", "*[API]: ", "Interface", "\n\n", "tail"),
        )
        for (chunks in samples) {
            val parser = MarkdownParser()
            parser.beginStream()
            var prefix = ""
            for (chunk in chunks) {
                prefix += chunk
                parser.append(chunk)
                val reference = MarkdownParser().apply { beginStream(); append(prefix) }
                assertEquals(reference.document.semanticSnapshot(), parser.document.semanticSnapshot(), prefix)
            }
        }
    }

    @Test
    fun should_boundBlockParsingWork_when_editingOrdinaryTextInLargeDocumentsWithDefinitionMarkers() {
        for (paragraphs in listOf(100, 1000)) {
            for (prefix in listOf("ordinary ]: punctuation\n\n", "[ref]: /target\n\n", "[ref]: /target\ntext after definition\n\n")) {
                val probe = ParsingProbe()
                val source = prefix + (0 until paragraphs).joinToString("\n\n") { "paragraph $it [read][ref]" }
                val parser = MarkdownParser(probe.flavour)
                parser.parse(source)
                parser.insert(source.length, "") // Initialize the lazy edit engine before measuring.
                val first = parser.document.children.first()
                val last = parser.document.children.last()
                val offset = source.indexOf("paragraph ${paragraphs / 2}") + 10
                probe.calls = 0
                parser.insert(offset, "x")
                assertTrue(probe.calls in 1..4, "paragraphs=$paragraphs, prefix=$prefix, calls=${probe.calls}")
                assertSame(first, parser.document.children.first())
                assertSame(last, parser.document.children.last())
                assertEditedDocument(parser, source.replaceRange(offset, offset, "x"))
            }
        }
    }

    @Test
    fun should_keepStreamingTailLocal_when_definitionOrLiteralMarkerIsAlreadyInStablePrefix() {
        for (prefix in listOf("ordinary ]: punctuation\n\n", "[ref]: /target\n\n", "[ref]: /target\ntext after definition\n\n")) {
            val probe = ParsingProbe()
            val parser = MarkdownParser(probe.flavour)
            parser.beginStream()
            var source = prefix + (0 until 1000).joinToString("\n\n") { "paragraph $it [read][ref]" }
            parser.append(source).semanticSnapshot()
            val first = parser.document.children.first()
            repeat(3) {
                probe.calls = 0
                source += " tail"
                parser.append(" tail")
                assertTrue(probe.calls in 1..4, "prefix=$prefix, calls=${probe.calls}")
                assertSame(first, parser.document.children.first())
                val reference = MarkdownParser().apply { beginStream(); append(source) }
                assertEquals(reference.document.semanticSnapshot(), parser.document.semanticSnapshot())
            }
        }
    }

    /** Count parser work through a public extension point; avoid timing-dependent assertions. */
    private class ParsingProbe {
        var calls = 0
        private val observer = object : BlockStarter {
            override val priority = Int.MIN_VALUE
            override val canInterruptParagraph = true
            override fun tryStart(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
                calls++
                return null
            }
        }
        val flavour = object : MarkdownFlavour by ExtendedFlavour {
            override val blockStarters = listOf(observer) + ExtendedFlavour.blockStarters
        }
    }
}
