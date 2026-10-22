package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.OpenBlock
import com.hrm.markdown.parser.block.postprocessors.BlockPostProcessor
import com.hrm.markdown.parser.block.starters.BlockStarter
import com.hrm.markdown.parser.core.LineCursor
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.*

class FlavourPublicationIsolationTest {
    @Test
    fun should_revokePublishedTreeWritesDuringFreshParsing_when_customStarterCapturesAnOldPrefix() {
        var retainedText: Text? = null
        var checkedWrites = 0
        val starter = object : BlockStarter {
            override val priority = 0
            override val canInterruptParagraph = true
            override fun tryStart(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
                retainedText?.let { text ->
                    checkedWrites++
                    assertFailsWith<IllegalStateException> { text.literal = "illegal" }
                }
                return null
            }
        }
        val flavour = CommonMarkFlavour.copy(
            blockStarters = listOf(starter) + CommonMarkFlavour.blockStarters
        )
        val parser = MarkdownParser(flavour)
        val source = "prefix\n\ntail"
        val initial = parser.parse(source)
        retainedText = (initial.children.first() as Paragraph).children.single() as Text
        val updated = parser.insert(source.length, "!")
        assertTrue(checkedWrites > 0)
        assertEquals("prefix", retainedText!!.literal)
        assertEquals(MarkdownParser(CommonMarkFlavour).parse(source + "!").semanticSnapshot(), updated.semanticSnapshot())
        checkedWrites = 0
        val replaced = parser.parse("replacement")
        assertTrue(checkedWrites > 0)
        assertEquals("prefix", retainedText!!.literal)
        assertEquals(MarkdownParser(CommonMarkFlavour).parse("replacement").semanticSnapshot(), replaced.semanticSnapshot())
    }

    @Test
    fun should_discoverGlobalNodesBeforePublication_when_blockCallbackReturnsPublicLazyContent() {
        val processor = object : BlockPostProcessor {
            override val priority = 0
            override fun processBlock(block: Node): Node? = if (block is Paragraph &&
                block.rawContent.orEmpty().startsWith("tail")) {
                hiddenHeading(block.rawContent.orEmpty())
            } else block
        }
        val flavour = ExtendedFlavour.copy(
            postProcessors = listOf(processor) + ExtendedFlavour.postProcessors
        )
        val parser = MarkdownParser(flavour)
        val source = "[TOC]\n\nprefix\n\ntail"
        parser.parse(source)
        val updated = parser.insert(source.length, "!")
        assertTrue(updated.children.filterIsInstance<CustomContainer>().single().isInlineParsed)
        assertTrue(assertNotNull(updated.publication).globalDependenciesChanged)
        val reference = MarkdownParser(flavour).parse(source + "!")
        assertEquals(reference.semanticSnapshot(), updated.semanticSnapshot())
        val heading = (updated.children.filterIsInstance<CustomContainer>().single().children.single() as Heading)
        assertEquals("hidden-tail", heading.id)
    }

    @Test
    fun should_discoverPublicLazyStarterOutputBeforeDependencyChecks_when_editingHiddenHeadings() {
        val starter = object : BlockStarter {
            override val priority = 0
            override val canInterruptParagraph = true
            override fun tryStart(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
                if (!cursor.rest().startsWith("@hidden ")) return null
                val content = cursor.rest().removePrefix("@hidden ")
                cursor.advance(cursor.remaining)
                return OpenBlock(hiddenHeading(content), contentStartLine = lineIdx, lastLineIndex = lineIdx).apply {
                    fenceLength = 3
                }
            }
        }
        val flavour = ExtendedFlavour.copy(
            blockStarters = listOf(starter) + ExtendedFlavour.blockStarters
        )
        val parser = MarkdownParser(flavour)
        val source = "[TOC]\n\nprefix\n\n@hidden tail"
        parser.parse(source)
        val updated = parser.insert(source.length, "!")
        assertTrue(updated.children.filterIsInstance<CustomContainer>().single().isInlineParsed)
        assertTrue(assertNotNull(updated.publication).globalDependenciesChanged)
        val reference = MarkdownParser(flavour).parse(source + "!")
        assertEquals(reference.semanticSnapshot(), updated.semanticSnapshot())
        val heading = (updated.children.filterIsInstance<CustomContainer>().single().children.single() as Heading)
        assertEquals("hidden-tail", heading.id)
    }

    private fun hiddenHeading(content: String): CustomContainer = CustomContainer(type = "hidden").apply {
        setLazyInlineContent(content) { raw, parent ->
            parent.appendChild(Heading(2).apply {
                rawContent = "hidden $raw"
                appendChild(Text("hidden $raw"))
            })
        }
    }
}
