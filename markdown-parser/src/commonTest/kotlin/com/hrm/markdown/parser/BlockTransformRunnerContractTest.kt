package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.postprocessors.BlockPostProcessor
import com.hrm.markdown.parser.block.postprocessors.BlockTransformRunner
import com.hrm.markdown.parser.block.postprocessors.PostProcessor
import com.hrm.markdown.parser.block.postprocessors.PostProcessorRegistry
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.inline.InlineParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlockTransformRunnerContractTest {
    @Test
    fun should_keepOracleBuildersAndBuiltinLazyContentWritable_when_laterGlobalPassesAddDefinitions() {
        val document = Document()
        val paragraph = Paragraph().apply {
            setTrustedLazyInlineContent("[^n]") { content, parent ->
                InlineParser(document).parseInlines(content, parent)
            }
        }
        document.appendChild(paragraph)
        val global = object : PostProcessor {
            override val priority = 200
            override fun process(document: Document) {
                assertFalse(paragraph.isInlineParsed)
                paragraph.blockAttributes = mapOf("after" to "block")
                document.footnoteDefinitions["n"] = FootnoteDefinition("n")
            }
        }
        PostProcessorRegistry(listOf(BlockPostProcessor { it }, global)).processAll(document)

        assertFalse(paragraph.isInlineParsed)
        assertEquals("block", paragraph.blockAttributes["after"])
        val reference = assertIs<FootnoteReference>(paragraph.children.single())
        assertEquals(1, reference.index)
        val definition = document.footnoteDefinitions.getValue("n")
        assertEquals(1, definition.index)
        definition.index = 2
        document.linkDefinitions["later"] = LinkReferenceDefinition("later", "/target")
        document.appendChild(Paragraph())
        assertEquals(2, definition.index)
        assertEquals(2, document.children.size)
    }

    @Test
    fun should_authorizeOnlyTheCurrentBlock_when_chainingPublishedTransformPasses() {
        var first: Paragraph? = null
        var secondPassParagraphs = 0
        val uppercase = BlockPostProcessor { block ->
            if (block is Paragraph) {
                block.children.filterIsInstance<Text>().forEach { it.literal = it.literal.uppercase() }
                if (block.rawContent == "first") first = block
            }
            block
        }
        val suffix = BlockPostProcessor { block ->
            if (block is Paragraph) {
                secondPassParagraphs++
                first?.takeIf { it !== block }?.let { captured ->
                    assertFailsWith<IllegalStateException> { captured.rawContent = "escaped" }
                    assertFailsWith<IllegalStateException> { (captured.children.single() as Text).literal = "escaped" }
                }
                block.children.filterIsInstance<Text>().forEach { it.literal += "!" }
            }
            block
        }
        val flavour = CommonMarkFlavour.with(uppercase, suffix)
        val parser = MarkdownParser(flavour)
        val source = "first\n\nmiddle\n\nlast"
        val initial = parser.parse(source)
        assertEquals(listOf("FIRST!", "MIDDLE!", "LAST!"), initial.children.filterIsInstance<Paragraph>().map {
            (it.children.single() as Text).literal
        })
        assertEquals(3, secondPassParagraphs)
        val retained = first!!
        secondPassParagraphs = 0
        val updated = parser.replace(source.indexOf("last"), 4, "next")
        assertSame(retained, updated.children.first())
        assertTrue(secondPassParagraphs in 1..2)
        assertEquals("NEXT!", ((updated.children.last() as Paragraph).children.single() as Text).literal)
        assertFailsWith<IllegalStateException> { retained.rawContent = "after publication" }
    }

    @Test
    fun should_revokePublicationAuthorityBeforeInputLazyCallbacks_when_runningInsideAnEngineMutationScope() {
        val previous = MarkdownParser(CommonMarkFlavour).parse("prefix")
        val retained = (previous.children.single() as Paragraph).children.single() as Text
        var lazyCalls = 0
        val input = CustomContainer().apply {
            setLazyInlineContent("fresh") { content, parent ->
                lazyCalls++
                assertFailsWith<IllegalStateException> { retained.literal = "escaped" }
                parent.appendChild(Text(content))
            }
        }
        val document = Document().also { it.appendChild(input) }
        withDocumentMutation(previous) {
            BlockTransformRunner.process(BlockPostProcessor { it }, document, previous)
        }
        assertEquals(1, lazyCalls)
        assertEquals("prefix", retained.literal)
        assertEquals("fresh", (input.children.single() as Text).literal)
        assertFailsWith<IllegalStateException> { (input.children.single() as Text).literal = "after callback" }
    }

    @Test
    fun should_protectTheTemporaryRootAndPendingInputs_when_callbacksCaptureSiblingReferences() {
        val previous = MarkdownParser(CommonMarkFlavour).parse("published")
        val firstText = Text("first")
        val secondText = Text("second")
        val first = Paragraph().apply { appendChild(firstText) }
        val second = Paragraph().apply { appendChild(secondText) }
        var rootLazyCalls = 0
        val document = Document().apply {
            appendChild(first)
            setLazyInlineContent("second") { _, parent ->
                rootLazyCalls++
                assertFailsWith<IllegalStateException> { firstText.literal = "root lazy sibling" }
                parent.appendChild(second)
            }
        }
        var callbacks = 0
        val processor = BlockPostProcessor { block ->
            callbacks++
            assertEquals(null, block.parent)
            if (block === first) {
                assertFailsWith<IllegalStateException> { second.rawContent = "pending sibling" }
                assertFailsWith<IllegalStateException> { secondText.literal = "pending descendant" }
            } else {
                val capturedParent = assertIs<Document>(first.parent)
                assertSame(document, capturedParent)
                assertFailsWith<IllegalStateException> {
                    capturedParent.appendChild(FootnoteDefinition("injected"))
                }
                assertFailsWith<IllegalStateException> {
                    capturedParent.setLazyInlineContent("injected") { _, parent -> parent.appendChild(Paragraph()) }
                }
                assertFailsWith<IllegalStateException> { firstText.literal = "processed sibling" }
            }
            (block as Paragraph).children.filterIsInstance<Text>().forEach { it.literal += "!" }
            block
        }
        withDocumentMutation(previous) {
            BlockTransformRunner.process(processor, document, previous)
        }
        assertEquals(2, callbacks)
        assertEquals(1, rootLazyCalls)
        assertEquals(listOf(first, second), document.children)
        assertEquals("first!", firstText.literal)
        assertEquals("second!", secondText.literal)
        assertFailsWith<IllegalStateException> { document.appendChild(Paragraph()) }
        assertFailsWith<IllegalStateException> { secondText.literal = "after callback" }
    }

    @Test
    fun should_skipDefinitionInputsAndRejectDefinitionOutputs_when_publicLazyContentHidesDefinitions() {
        var inputLazyCalls = 0
        var blockCalls = 0
        val input = CustomContainer().apply {
            setLazyInlineContent("definition") { _, parent ->
                inputLazyCalls++
                parent.appendChild(FootnoteDefinition("kept"))
            }
        }
        val document = Document().also { it.appendChild(input) }
        BlockTransformRunner.process(BlockPostProcessor { blockCalls++; null }, document)
        assertEquals(1, inputLazyCalls)
        assertEquals(0, blockCalls)
        assertSame(input, document.children.single())

        var outputLazyCalls = 0
        val output = CustomContainer().apply {
            setLazyInlineContent("definition") { _, parent ->
                outputLazyCalls++
                parent.appendChild(LinkReferenceDefinition("new", "/target"))
            }
        }
        val ordinary = Document().also { it.appendChild(Paragraph()) }
        assertFailsWith<IllegalArgumentException> {
            BlockTransformRunner.process(BlockPostProcessor { output }, ordinary)
        }
        assertEquals(1, outputLazyCalls)
    }

    @Test
    fun should_rejectAmbiguousProcessorScopesBeforeRegistration_when_usingTheInternalOracleRegistry() {
        val dualScope = object : PostProcessor, BlockPostProcessor {
            override fun process(document: Document) = Unit
            override fun processBlock(block: Node): Node = block
        }
        assertFailsWith<IllegalArgumentException> { PostProcessorRegistry(listOf(dualScope)) }
        val registry = PostProcessorRegistry()
        assertFailsWith<IllegalArgumentException> { registry.register(dualScope) }
        assertFailsWith<IllegalArgumentException> { registry.registerAll(PostProcessor { }, dualScope) }
        assertTrue(registry.sortedSnapshot().isEmpty())
    }
}
