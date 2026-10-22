package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.postprocessors.BlockPostProcessor
import com.hrm.markdown.parser.block.postprocessors.HeadingIdProcessor
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.hrm.markdown.parser.incremental.EditOperation
import com.hrm.markdown.parser.incremental.IncrementalEngine
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.*

class BlockPostProcessorContractTest {
    @Test
    fun should_rejectCapturedPublishedOutputBeforeReparenting_when_aBlockCallbackReturnsTheStablePrefix() {
        var captured: Paragraph? = null
        val flavour = object : MarkdownFlavour by CommonMarkFlavour {
            override val postProcessors = listOf(BlockPostProcessor { block -> captured ?: block })
        }
        val parser = MarkdownParser(flavour)
        val source = "prefix\n\ntail"
        val initial = parser.parse(source)
        val prefix = initial.children.first() as Paragraph
        captured = prefix
        val prefixId = prefix.persistentId
        val publication = assertNotNull(initial.publication)

        assertFailsWith<IllegalStateException> { parser.insert(source.length, "!") }
        assertSame(initial, prefix.parent)
        assertSame(prefix, initial.children.first())
        assertEquals("prefix", prefix.rawContent)
        assertEquals(prefixId, prefix.persistentId)
        assertTrue(publication.isCurrent)
        assertEquals(initial.children.size, initial.children.map { it.persistentId }.toSet().size)
        assertFailsWith<IllegalStateException> { prefix.rawContent = "escape" }
    }

    @Test
    fun should_transformOnlyChangedBlocksAndProtectRetainedNodes_when_stablePrefixGrows() {
        for (prefixSize in listOf(16, 4096)) {
            var visitedParagraphs = 0
            var retainedPrefix: Paragraph? = null
            val processor = BlockPostProcessor { block ->
                assertEquals(null, block.parent)
                retainedPrefix?.let { prefix ->
                    assertFailsWith<IllegalStateException> { prefix.rawContent = "illegal" }
                    val text = prefix.children.first() as Text
                    assertFailsWith<IllegalStateException> { text.literal = "illegal" }
                }
                if (block is Paragraph) {
                    visitedParagraphs++
                    block.children.filterIsInstance<Text>().forEach { it.literal = it.literal.uppercase() }
                }
                block
            }
            val flavour = object : MarkdownFlavour by CommonMarkFlavour {
                override val postProcessors = listOf(processor)
            }
            val source = buildString {
                repeat(prefixSize) { append("paragraph $it\n\n") }
                append("tail")
            }
            val engine = IncrementalEngine(flavour)
            val initial = engine.fullParse(source)
            retainedPrefix = initial.children.first() as Paragraph
            val previous = assertNotNull(initial.publication)
            visitedParagraphs = 0
            val updated = engine.applyEdit(EditOperation.Append("!"))
            assertTrue(visitedParagraphs in 1..2, "prefix=$prefixSize visited=$visitedParagraphs")
            assertTrue(engine.lastUpdate.parsedLines <= 4, "prefix=$prefixSize stats=${engine.lastUpdate}")
            assertSame(retainedPrefix, updated.children.first())
            assertEquals("paragraph 0", retainedPrefix.rawContent)
            assertEquals("TAIL!", ((updated.children.last() as Paragraph).children.single() as Text).literal)
            val publication = assertNotNull(updated.publication)
            assertSame(previous.lineageId, publication.lineageId)
            assertFalse(publication.globalDependenciesChanged)
            assertFalse(previous.isCurrent)
            val independentlyParsed = MarkdownParser(flavour).parse(source + "!")
            assertEquals(independentlyParsed.semanticSnapshot(), updated.semanticSnapshot())
        }
    }

    @Test
    fun should_supportReplacementAndRemovalWithAutomaticMetadata_when_usingALambda() {
        val processor = BlockPostProcessor { block ->
            when ((block as? Paragraph)?.rawContent) {
                "drop" -> null
                "replace" -> Paragraph().apply { appendChild(Text("replacement")) }
                else -> block
            }
        }
        val flavour = object : MarkdownFlavour by CommonMarkFlavour {
            override val postProcessors = listOf(processor)
        }
        val parser = MarkdownParser(flavour)
        val source = "keep\n\ndrop\n\nreplace"
        val initial = parser.parse(source)
        assertEquals(2, initial.children.filterIsInstance<Paragraph>().size)
        val replacement = initial.children.last() as Paragraph
        assertEquals("replacement", (replacement.children.single() as Text).literal)
        assertEquals(4, replacement.lineRange.startLine)
        assertSame(initial, replacement.parent)
        val updated = parser.insert(source.length, "!")
        assertEquals("replace!", (updated.children.last() as Paragraph).rawContent)
        assertFalse(assertNotNull(updated.publication).globalDependenciesChanged)
    }

    @Test
    fun should_matchTheFullPipeline_when_aBlockCallbackIntroducesHeadingsBeforeIdGeneration() {
        val processor = object : BlockPostProcessor {
            override val priority = 0
            override fun processBlock(block: Node): Node? = if (block is Paragraph) {
                Heading(2).apply {
                    rawContent = block.rawContent
                    block.children.toList().forEach { appendChild(Text((it as Text).literal)) }
                }
            } else block
        }
        val flavour = object : MarkdownFlavour by CommonMarkFlavour {
            override val postProcessors = listOf(processor, HeadingIdProcessor())
        }
        val parser = MarkdownParser(flavour)
        val source = "same\n\nsame\n\ntail"
        parser.parse(source)
        val updated = parser.insert(source.length, "!")
        val reference = MarkdownParser(flavour).parse(source + "!")
        assertEquals(reference.semanticSnapshot(), updated.semanticSnapshot())
        assertEquals(listOf("same", "same-1", "tail"), updated.children.filterIsInstance<Heading>().map { it.id })
        assertTrue(assertNotNull(updated.publication).globalDependenciesChanged)
    }

    @Test
    fun should_rejectReferenceDefinitionCreation_when_aBlockCallbackRequiresDocumentWideSemantics() {
        val flavour = object : MarkdownFlavour by CommonMarkFlavour {
            override val postProcessors = listOf(BlockPostProcessor {
                FootnoteDefinition("new").apply { appendChild(Paragraph()) }
            })
        }
        assertFailsWith<IllegalArgumentException> { MarkdownParser(flavour).parse("ordinary") }
    }

    @Test
    fun should_protectDefinitionValuesAndMaps_when_aBlockCallbackCapturesPublishedReferences() {
        var published: Document? = null
        var callbacks = 0
        val processor = BlockPostProcessor { block ->
            callbacks++
            published?.let { document ->
                assertFailsWith<IllegalStateException> {
                    document.linkDefinitions["injected"] = LinkReferenceDefinition("injected", "/bad")
                }
                assertFailsWith<IllegalStateException> {
                    document.linkDefinitions.getValue("ref").destination = "/bad"
                }
            }
            block
        }
        val flavour = object : MarkdownFlavour by CommonMarkFlavour {
            override val postProcessors = listOf(processor)
        }
        val parser = MarkdownParser(flavour)
        val source = "[ref]: /target\n\nfirst\n\ntail"
        published = parser.parse(source)
        callbacks = 0
        val updated = parser.insert(source.length, "!")
        assertTrue(callbacks in 1..2)
        assertEquals(setOf("ref"), updated.linkDefinitions.keys)
        assertEquals("/target", updated.linkDefinitions.getValue("ref").destination)
        assertSame(published.linkDefinitions, updated.linkDefinitions)
        assertFalse(assertNotNull(updated.publication).globalDependenciesChanged)
    }
}
