package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.postprocessors.PostProcessor
import com.hrm.markdown.parser.flavour.*
import com.hrm.markdown.parser.incremental.EditOperation
import com.hrm.markdown.parser.incremental.IncrementalEngine
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.*

internal val incrementalSyntaxSamples = listOf(
        "paragraph **bold**",
        "# title\n# title-1\n# title",
        "```kotlin\nval x = 1\n```\nadjacent paragraph",
        "$$\nx + y\n$$\nadjacent paragraph",
        "- first\n\n    [ref]: /inside\n\n- last",
        "> [!NOTE] Title\n> body\n>\n> - nested",
        "![caption](/image.png)\n\n{.figure #image}",
        "# Heading\n\n{.first #fixed}\n\n{.second}",
        "[^bibliography]: a: Author, Book\n\nb: Another, Book\n\nend: Entry",
        "```mermaid\ngraph TD\nA-->B\n```",
        ":::columns\n:::column{width=50%}\nleft\n:::\n:::column\nright\n:::\n:::",
        "{% card title=hi %}\nbody\n{% endcard %}",
        ":::tabs\n@tab One\nfirst\n@tab Two\nsecond\n:::",
        "Term\n: definition\n\nTerm two\n: other",
        "| A | B |\n|---|---|\n| 1 | 2 |",
        "[read][ref] HTML text[^n]\n\n[ref]: /first\n\n[ref]: /second\n\n*[HTML]: Hyper Text\n\n[^n]: note",
        "---\ntitle: value\n---\ntext",
        "<script>\ncontent\n</script>\n\ntext",
        "中文 😀\r\n\r\n**unicode**\u0000",
    )

class IncrementalAlgorithmContractTest {
    private val features get() = incrementalSyntaxSamples

    @Test
    fun should_matchIndependentFullPipeline_when_editingEveryFeatureAtSyntaxAndJoinBoundaries() {
        var checked = 0
        val fragments = listOf("x", "\n", "\n\n", "- ", "{.class}", "[^n]: new\n")
        for (flavour in listOf(CommonMarkFlavour, GFMFlavour, ExtendedFlavour, MarkdownExtraFlavour)) {
            for ((featureIndex, raw) in features.withIndex()) {
                val feature = normalizedTestInput(raw)
                val original = "prefix\n\n$feature\n\nsuffix"
                val positions = buildSet {
                    add(0); add(original.length); add(8); add(8 + feature.length)
                    original.forEachIndexed { index, c -> if (c == '\n') { add(index); add(index + 1) } }
                }
                for (offset in positions) {
                    val operations = fragments.map { EditOperation.Insert(offset, it) } +
                        listOf(EditOperation.Delete(offset, minOf(3, original.length - offset)),
                            EditOperation.Replace(offset, minOf(2, original.length - offset), "\r\nX"))
                    for (edit in operations) {
                        val parser = MarkdownParser(MarkdownParserConfig(flavour = flavour, enableLinting = true))
                        parser.parse(original).semanticSnapshot()
                        val expected = when (edit) {
                            is EditOperation.Insert -> original.replaceRange(offset, offset, normalizedTestInput(edit.text))
                            is EditOperation.Delete -> original.removeRange(offset, offset + edit.length)
                            is EditOperation.Replace -> original.replaceRange(offset, offset + edit.length, normalizedTestInput(edit.newText))
                            is EditOperation.Append -> error("not used")
                        }
                        val context = "flavour=${flavour::class.simpleName} feature=$featureIndex edit=$edit"
                        assertEditedDocument(parser, expected, parser.applyEdit(edit), context)
                        assertParents(parser.document)
                        checked++
                    }
                }
            }
        }
        println("independent feature boundary cases=$checked")
    }

    @Test
    fun should_removeAndReapplyAttributesExactlyOnce_when_editsChangeCrossBlockDependencies() {
        var text = "# Title\n\n{.first #fixed}\n\n{.second}\n\nparagraph\n\n# Title"
        val parser = MarkdownParser()
        parser.parse(text).semanticSnapshot()
        for ((old, replacement) in listOf("first" to "changed", "{.second}" to "ordinary", "ordinary" to "{.third}", "{.changed #fixed}" to "", "paragraph" to "{.fourth}")) {
            val offset = text.indexOf(old)
            text = text.replaceRange(offset, offset + old.length, replacement)
            assertEditedDocument(parser, text, parser.replace(offset, old.length, replacement))
        }
    }

    @Test
    fun should_recomputeHeadingReservationsFromRawInputs_when_attributesAndAbbreviationsAlreadyChangedOutput() {
        var text = "# HTML\n\n{#custom}\n\n# HTML\n\n# html-1\n\n*[HTML]: Hyper Text\n\nlast"
        val parser = MarkdownParser()
        parser.parse(text).semanticSnapshot()
        val suffix = parser.document.children.last()
        text = text.replaceRange(2, 6, "Other")
        assertEditedDocument(parser, text, parser.replace(2, 4, "Other"))
        assertSame(suffix, parser.document.children.last())
    }

    @Test
    fun should_preserveGlobalReferenceContext_when_oldLazySuffixIsMaterializedAfterSeveralEdits() {
        val engine = IncrementalEngine(CommonMarkFlavour)
        var text = "prefix\n\n[read][ref]\n\n[ref]: /target\n\nsuffix"
        engine.fullParse(text)
        val reference = engine.document.children[1] as Paragraph
        assertFalse(reference.isInlineParsed)
        repeat(4) {
            engine.applyEdit(EditOperation.Insert(1, "x"))
            text = text.replaceRange(1, 1, "x")
        }
        assertSame(reference, engine.document.children[1])
        assertFalse(reference.isInlineParsed)
        assertEquals("/target", (reference.children.single() as Link).destination)
        assertEquals(referenceDocument(MarkdownParser(CommonMarkFlavour), text).semanticSnapshot(), engine.document.semanticSnapshot())
    }

    @Test
    fun should_rebuildPristineSyntax_when_customProcessorIsNotIdempotentOrChangesEarlierBlocks() {
        val processor = object : PostProcessor {
            override val priority = 100
            override fun process(document: Document) {
                val paragraphs = document.children.filterIsInstance<Paragraph>()
                paragraphs.firstOrNull()?.rawContent = "blocks=${paragraphs.size}"
                paragraphs.forEach { it.appendChild(Text("processed")) }
            }
        }
        val flavour = CommonMarkFlavour.copy(
            postProcessors = listOf(processor)
        )
        val parser = MarkdownParser(flavour)
        var text = "one\n\ntwo"
        parser.parse(text).semanticSnapshot()
        repeat(3) {
            val extra = "\n\nnext $it"
            assertEditedDocument(parser, text + extra, parser.applyEdit(EditOperation.Append(extra)))
            text += extra
        }
    }

    @Test
    fun should_flushPendingSourceBeforeEditingAndRemoveRepairsOnEnd_when_coalescingStreamUpdates() {
        val engine = IncrementalEngine(appendCoalesceThreshold = 32)
        engine.beginStream()
        engine.append("first\n\n**bold")
        engine.append(" tail") // buffered in source, not in AST
        engine.applyEdit(EditOperation.Insert(1, "X"))
        val text = "fXirst\n\n**bold tail"
        assertEquals(text, engine.currentText())
        engine.endStream()
        assertEquals(referenceDocument(MarkdownParser(), text).semanticSnapshot(), engine.document.semanticSnapshot())
    }

    @Test
    fun should_boundParsedRegion_when_prefixSizeGrowsOrClosedFenceIsLarge() {
        for (count in listOf(100, 1000, 10000)) {
            val engine = IncrementalEngine()
            val text = (0 until count).joinToString("\n\n") { "paragraph $it" }
            engine.fullParse(text)
            val first = engine.document.children.first()
            val last = engine.document.children.last()
            val offset = text.indexOf("paragraph ${count / 2}") + 10
            engine.applyEdit(EditOperation.Replace(offset, 1, "x"))
            assertTrue(engine.lastUpdate.parsedLines <= 4, "count=$count stats=${engine.lastUpdate}")
            assertNull(engine.lastUpdate.fullParseReason)
            assertSame(first, engine.document.children.first())
            assertSame(last, engine.document.children.last())
            engine.applyEdit(EditOperation.Append(" tail"))
            assertTrue(engine.lastUpdate.parsedLines <= 4, "append count=$count stats=${engine.lastUpdate}")
        }
        val engine = IncrementalEngine(CommonMarkFlavour)
        engine.beginStream()
        engine.append("```\n" + "code\n".repeat(10000) + "```\nparagraph\n\n")
        val code = engine.document.children.first()
        engine.append("tail")
        assertTrue(engine.lastUpdate.parsedLines <= 3, engine.lastUpdate.toString())
        assertSame(code, engine.document.children.first())
    }

    @Test
    fun should_keepOneSourceAndReusePrefix_when_publicEditsAndAppendCallsAreInterleaved() {
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.parse("first\n\nlast")
        val first = parser.document.children.first()
        assertEditedDocument(parser, "first\n\nlaXst", parser.insert(9, "X"))
        assertSame(first, parser.document.children.first(), "first edit must not copy into a second parser")
        assertEditedDocument(parser, "first\n\nlaXst tail", parser.append(" tail"))
        assertEditedDocument(parser, "first\n\nlast tail", parser.delete(9, 1))
        assertEditedDocument(parser, "first\n\nlast tail!", parser.applyEdit(EditOperation.Append("!")))
        assertSame(first, parser.document.children.first())
    }

    @Test
    fun should_preserveStreamingModeAndFinishEditedInput_when_editingDuringAnActiveStream() {
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.beginStream()
        parser.append("first\n\nlast")
        parser.replace(7, 4, "**tail")
        assertTrue(parser.isStreaming)
        parser.append(" more")
        assertEditedDocument(parser, "first\n\n**tail more", parser.endStream())
        assertFalse(parser.isStreaming)
    }

    @Test
    fun should_removeAbortedDisplayRepairs_when_laterEditingAnUnrelatedPrefix() {
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.beginStream()
        parser.append("first\n\n**open")
        parser.abort().semanticSnapshot()
        assertEditedDocument(parser, "fXirst\n\n**open", parser.insert(1, "X"))
    }

    @Test
    fun should_keepAdjacentDefinitionPrefixLocal_when_tailCannotContinueABibliography() {
        val engine = IncrementalEngine()
        engine.beginStream()
        engine.append("[ref]: /target\n\ntail [read][ref]")
        val definition = engine.document.children.first()
        repeat(8) {
            engine.append(" text")
            assertNull(engine.lastUpdate.fullParseReason, engine.lastUpdate.toString())
            assertTrue(engine.lastUpdate.parsedLines <= 2)
            assertSame(definition, engine.document.children.first())
        }
    }

    @Test
    fun should_keepTailRepairsAtomic_when_editingBeforeAnAppendableBlankLine() {
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.beginStream()
        parser.append("first\n\n**open\n").semanticSnapshot()
        val actual = parser.insert(10, "X")
        val reference = MarkdownParser(CommonMarkFlavour).apply { beginStream(); append("first\n\n**oXpen\n") }
        assertEquals(reference.document.semanticSnapshot(), actual.semanticSnapshot())
        assertNodeCoordinatesEqual(reference.document, actual)
    }

    @Test
    fun should_preserveRawCrLfPairState_when_editingBetweenStreamingChunks() {
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.beginStream()
        parser.append("first\r")
        parser.insert(1, "X")
        parser.append("\nnext")
        assertEditedDocument(parser, "fXirst\nnext", parser.endStream())
    }

    @Test
    fun should_keepRemovedNodeOwnedByOldRoot_when_otherNodesAreReusedInNewRoot() {
        val parser = MarkdownParser(CommonMarkFlavour)
        val old = parser.parse("first\n\nremoved\n\nlast")
        val removed = old.children[1]
        val last = old.children.last()
        parser.delete(7, 9)
        assertSame(old, removed.parent)
        assertSame(last, parser.document.children.last())
        assertSame(parser.document, last.parent)
        assertParents(parser.document)
    }

    @Test
    fun should_preserveRootIdentityMetadataAndDiagnostics_when_customFullProcessorPublishesThem() {
        var observed: Document? = null
        val lengths = mutableListOf<Int>()
        val diagnostics = com.hrm.markdown.parser.lint.DiagnosticResult()
        val processor = object : PostProcessor {
            override val priority = 100
            override fun process(document: Document) {
                observed = document
                lengths += document.sourceRange.length
                document.contentHash = 42L
                document.diagnostics = diagnostics
            }
        }
        val flavour = CommonMarkFlavour.copy( postProcessors = listOf(processor) )
        val parser = MarkdownParser(flavour)
        parser.parse("one")
        assertSame(observed, parser.document)
        assertSame(diagnostics, parser.document.diagnostics)
        assertEquals(42L, parser.document.contentHash)
        parser.append(" more")
        assertEquals(listOf(3, 8), lengths)
        assertSame(observed, parser.document)
        assertSame(diagnostics, parser.document.diagnostics)
        assertEquals(42L, parser.document.contentHash)
    }

    @Test
    fun should_leavePublishedStateUntouched_when_invalidEditTargetsBufferedOrAbortedSource() {
        for (aborted in listOf(false, true)) {
            val engine = IncrementalEngine(CommonMarkFlavour, appendCoalesceThreshold = 32)
            engine.beginStream()
            engine.append("first\n\n**open")
            engine.append(" pending")
            if (aborted) engine.abort()
            val document = engine.document
            val snapshot = document.semanticSnapshot()
            val text = engine.currentText()
            assertFailsWith<IllegalArgumentException> { engine.applyEdit(EditOperation.Insert(Int.MAX_VALUE, "x")) }
            assertSame(document, engine.document)
            assertEquals(snapshot, engine.document.semanticSnapshot())
            assertEquals(text, engine.currentText())
            assertEquals(!aborted, engine.isStreaming)
        }
    }

    private fun assertParents(node: Node) {
        if (node is ContainerNode) node.children.forEach { child ->
            assertSame(node, child.parent, "${child::class.simpleName} parent")
            assertParents(child)
        }
    }
}
