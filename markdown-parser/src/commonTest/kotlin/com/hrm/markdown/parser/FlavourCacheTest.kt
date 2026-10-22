package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.ast.Text
import com.hrm.markdown.parser.block.OpenBlock
import com.hrm.markdown.parser.block.postprocessors.AstPostProcessor
import com.hrm.markdown.parser.block.postprocessors.BlockPostProcessor
import com.hrm.markdown.parser.block.postprocessors.PostProcessor
import com.hrm.markdown.parser.block.starters.BlockStarter
import com.hrm.markdown.parser.core.LineCursor
import com.hrm.markdown.parser.flavour.*
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.*

class FlavourCacheTest {
    @Test
    fun should_reuseOwnedPlan_when_builtinOrCustomFlavourIsReused() {
        for (flavour in listOf(ExtendedFlavour, CommonMarkFlavour, GFMFlavour, CommonMarkFlavour.copy(enableGfmAutolinks = true))) {
            val first = FlavourCache.of(flavour)
            val second = FlavourCache.of(flavour)
            assertSame(first, second)
            assertSame(first.blockStarterRegistry, second.blockStarterRegistry)
            assertSame(first.newPostProcessorRegistry().sortedSnapshot(), second.newPostProcessorRegistry().sortedSnapshot())
        }
    }

    @Test
    fun should_isolatePlans_when_distinctConfigurationsAreCreated() {
        val first = CommonMarkFlavour.copy(enableGfmAutolinks = true)
        val second = CommonMarkFlavour.copy(enableGfmAutolinks = true)
        assertNotSame(FlavourCache.of(first), FlavourCache.of(second))
        assertSame(first.blockStarters, second.blockStarters, "Trusted frozen collections need no second copy")
        assertSame(first.postProcessors, second.postProcessors)
    }

    @Test
    fun should_preserveConfigurationIdentity_when_copyDoesNotChangeItsInputs() {
        assertSame(CommonMarkFlavour, CommonMarkFlavour.copy())
        assertSame(CommonMarkFlavour, CommonMarkFlavour.copy(enableExtendedInline = false))
        val alternative = CommonMarkFlavour.copy(enableExtendedInline = true)
        assertNotSame(CommonMarkFlavour, alternative)
        assertTrue(alternative.enableExtendedInline)
    }

    @Test
    fun should_rejectAmbiguousScope_when_aProcessorImplementsBothInterfaces() {
        val processor = object : PostProcessor, BlockPostProcessor {
            override fun process(document: Document) = Unit
            override fun processBlock(block: com.hrm.markdown.parser.ast.Node) = block
        }
        assertFailsWith<IllegalArgumentException> { CommonMarkFlavour.with(processor) }
    }

    @Test
    fun should_preserveAllSyntaxSettings_when_copyingOrAppendingExtensions() {
        val processor = BlockPostProcessor { it }
        val starter = unusedStarter()
        val original = MarkdownFlavour(
            CommonMarkFlavour.blockStarters,
            enableGfmAutolinks = false,
            enableExtendedInline = false,
            enableStrikethrough = false,
            enableEmphasisCoalescing = true,
        )
        for (derived in listOf(original.copy(), original.with(processor), original.with(starter))) {
            assertFalse(derived.enableGfmAutolinks)
            assertFalse(derived.enableExtendedInline)
            assertFalse(derived.enableStrikethrough)
            assertTrue(derived.enableEmphasisCoalescing)
        }
        val source = "# Title\n\n~~literal~~ ==literal=="
        assertEquals(
            MarkdownParser(CommonMarkFlavour).parse(source).semanticSnapshot(),
            MarkdownParser(CommonMarkFlavour.with(processor)).parse(source).semanticSnapshot(),
        )
    }

    @Test
    fun should_snapshotCollections_when_mutableInputsAreChangedBeforePlanCreation() {
        val starter = unusedStarter()
        val starters = mutableListOf(starter)
        val processor = PostProcessor { }
        val processors = mutableListOf<AstPostProcessor>(processor)
        val flavour = MarkdownFlavour(starters, processors)
        starters.clear()
        processors.clear()
        assertEquals(listOf(starter), flavour.blockStarters)
        assertEquals(listOf(processor), flavour.postProcessors)
        assertEquals(listOf(starter), FlavourCache.of(flavour).blockStarterRegistry.allStarters())
        assertFails { (flavour.blockStarters as MutableList<BlockStarter>).clear() }
        assertFails { (flavour.postProcessors as MutableList<AstPostProcessor>).clear() }
    }

    @Test
    fun should_appendBothProcessorScopesInOrder_when_usingWith() {
        val events = mutableListOf<String>()
        val global = PostProcessor { events += "global" }
        val local = BlockPostProcessor { block -> events += "block"; block }
        val final = PostProcessor { events += "final" }
        val flavour = CommonMarkFlavour.with(global, local).with(final)
        assertEquals(listOf(global, local, final), flavour.postProcessors)
        assertEquals(100, global.priority)
        assertEquals(100, local.priority)
        val document = Document().also { it.appendChild(Paragraph().also { p -> p.appendChild(Text("text")) }) }
        FlavourCache.of(flavour).newPostProcessorRegistry().processAll(document)
        assertEquals(listOf("global", "block", "final"), events)
    }

    @Test
    fun should_preserveProcessorPriority_when_compilingTheOwnedPlan() {
        val events = mutableListOf<String>()
        val later = PostProcessor { events += "later" }
        val earlier = object : PostProcessor {
            override val priority = 0
            override fun process(document: Document) { events += "earlier" }
        }
        val flavour = CommonMarkFlavour.with(later, earlier)
        FlavourCache.of(flavour).newPostProcessorRegistry().processAll(Document())
        assertEquals(listOf("earlier", "later"), events)
    }

    @Test
    fun should_protectCompiledRegistry_when_newStartersAreRegistered() {
        val cache = FlavourCache.of(CommonMarkFlavour)
        assertTrue(cache.blockStarterRegistry.isFrozen)
        assertFailsWith<IllegalStateException> { cache.blockStarterRegistry.register(unusedStarter()) }
        val copy = cache.newBlockStarterRegistry()
        val originalSize = copy.allStarters().size
        copy.register(unusedStarter())
        assertEquals(originalSize + 1, copy.allStarters().size)
        assertEquals(originalSize, cache.blockStarterRegistry.allStarters().size)
    }

    @Test
    fun should_keepParserLocalProcessorRegistrations_when_sharingAFlavour() {
        val cache = FlavourCache.of(CommonMarkFlavour)
        val first = cache.newPostProcessorRegistry()
        val second = cache.newPostProcessorRegistry()
        first.register(PostProcessor { })
        assertEquals(1, first.sortedSnapshot().size)
        assertEquals(0, second.sortedSnapshot().size)
    }

    private fun unusedStarter(): BlockStarter = object : BlockStarter {
        override val priority = 700
        override val canInterruptParagraph = false
        override fun tryStart(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? = null
    }
}
