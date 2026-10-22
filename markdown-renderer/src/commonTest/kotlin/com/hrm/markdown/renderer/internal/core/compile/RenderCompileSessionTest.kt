package com.hrm.markdown.renderer.internal.core.compile

import com.hrm.markdown.parser.MarkdownParserConfig
import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.CustomContainer
import com.hrm.markdown.parser.ast.DirectiveBlock
import com.hrm.markdown.parser.ast.DirectiveInline
import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.ast.Figure
import com.hrm.markdown.parser.ast.Image
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.ast.Table
import com.hrm.markdown.renderer.MarkdownSoftBreakPolicy
import com.hrm.markdown.renderer.internal.adapter.createDirectiveBlockRenderScope
import com.hrm.markdown.renderer.internal.adapter.createDirectiveInlineRenderScope
import com.hrm.markdown.renderer.internal.core.identity.RenderIdentity
import com.hrm.markdown.renderer.internal.core.model.CustomContainerBlockModel
import com.hrm.markdown.renderer.internal.core.model.DirectiveBlockModel
import com.hrm.markdown.renderer.internal.core.model.DirectiveInlineWidgetModel
import com.hrm.markdown.renderer.internal.core.model.FigureBlockModel
import com.hrm.markdown.renderer.internal.core.model.ImageWidgetModel
import com.hrm.markdown.renderer.internal.core.model.InternalRenderBlockModel
import com.hrm.markdown.renderer.internal.core.model.ParagraphBlockModel
import com.hrm.markdown.renderer.internal.core.model.TableBlockModel
import com.hrm.markdown.renderer.internal.core.model.ThematicBreakBlockModel
import com.hrm.markdown.renderer.internal.core.model.WidgetAtom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RenderCompileSessionTest {
    private val environment = RenderCompileEnvironment()

    @Test
    fun should_compile_only_the_published_tail_and_keep_old_ir_unchanged() {
        var previousVisitedCount: Int? = null
        for (prefixSize in listOf(100, 1000, 10000)) {
            val parser = MarkdownParser()
            parser.beginStream()
            val initial = parser.append(buildString {
                repeat(prefixSize) { append("stable **bold** with [link](/target)\n\n") }
                append("tail")
            })
            val session = RenderCompileSession()
            val before = session.compile(initial, environment)
            val originalTail = before.blocks.last()
            repeat(4) {
                val document = parser.append(" x")
                val after = session.compile(document, environment)
                assertFalse(session.lastUpdate.coldRebuild)
                assertTrue(session.lastUpdate.compiledRootBlocks <= 4, "Stable prefix was recompiled: $prefixSize")
                assertTrue(session.lastUpdate.visitedNodes <= 16, "Stable prefix was visited: $prefixSize")
                previousVisitedCount?.let { assertEquals(it, session.lastUpdate.visitedNodes) }
                previousVisitedCount = session.lastUpdate.visitedNodes
                assertSame(before.blocks.first(), after.blocks.first())
                assertNotSame(originalTail, after.blocks.last())
                assertSame(before.metadata, after.metadata)
                assertEquals(DefaultRenderModelCompiler.compile(document, environment), after)
                assertEquals(before.blocks.size, after.blocks.size)
            }
            assertSame(originalTail, before.blocks.last())
        }
    }

    @Test
    fun should_reuse_the_same_model_for_buffered_receives() {
        val parser = MarkdownParser(MarkdownParserConfig(appendCoalesceThreshold = 16))
        parser.beginStream()
        val session = RenderCompileSession()
        val before = session.compile(parser.append("stable\n\ntail tail tail tail"), environment)
        val after = session.compile(parser.append("short"), environment)
        assertSame(before, after)
        assertEquals(0, session.lastUpdate.compiledRootBlocks)
    }

    @Test
    fun should_fall_back_after_skipped_revisions_changed_environment_or_lineage() {
        val parser = MarkdownParser()
        parser.beginStream()
        val session = RenderCompileSession()
        session.compile(parser.append("stable\n\ntail"), environment)
        parser.append(" first")
        val document = parser.append(" second")
        assertEquals(DefaultRenderModelCompiler.compile(document, environment), session.compile(document, environment))
        assertTrue(session.lastUpdate.coldRebuild)
        val changedEnvironment = RenderCompileEnvironment(
            softBreakPolicy = MarkdownSoftBreakPolicy.LineBreak,
        )
        assertEquals(DefaultRenderModelCompiler.compile(document, changedEnvironment), session.compile(document, changedEnvironment))
        assertTrue(session.lastUpdate.coldRebuild)
        val unrelated = MarkdownParser().parse("unrelated")
        assertEquals(DefaultRenderModelCompiler.compile(unrelated, environment), session.compile(unrelated, environment))
        assertTrue(session.lastUpdate.coldRebuild)
        assertEquals(unrelated.children.size, session.lastUpdate.retainedSlots)
    }

    @Test
    fun should_rebuild_global_heading_dependencies_and_match_cold_compilation() {
        val parser = MarkdownParser()
        parser.beginStream()
        val session = RenderCompileSession()
        val numbered = RenderCompileEnvironment(enableHeadingNumbering = true)
        session.compile(parser.append("# First\n\ntext\n\n"), numbered)
        val document = parser.append("## Second\n")
        assertEquals(DefaultRenderModelCompiler.compile(document, numbered), session.compile(document, numbered))
        assertTrue(session.lastUpdate.coldRebuild)
    }

    @Test
    fun should_require_a_parser_publication_for_session_compilation() {
        assertFailsWith<IllegalArgumentException> { RenderCompileSession().compile(Document(), environment) }
    }

    @Test
    fun should_reject_expired_ast_views_and_preserve_their_compiled_ir() {
        val parser = MarkdownParser()
        parser.beginStream()
        val session = RenderCompileSession()
        val previousView = parser.append("```text\nold body\n")
        val previousIr = session.compile(previousView, environment)
        val oldCode = previousIr.blocks.single()
        val currentView = parser.append("new body\n")
        assertFailsWith<IllegalArgumentException> { session.compile(previousView, environment) }
        assertFailsWith<IllegalArgumentException> { DefaultRenderModelCompiler.compile(previousView, environment) }
        val currentIr = session.compile(currentView, environment)
        assertEquals(DefaultRenderModelCompiler.compile(currentView, environment), currentIr)
        assertSame(oldCode, previousIr.blocks.single())
        assertNotSame(oldCode, currentIr.blocks.single())
    }

    @Test
    fun should_preserve_ir_collections_when_builder_inputs_change_after_cold_compilation() {
        val alignments = mutableListOf(Table.Alignment.LEFT)
        val classes = mutableListOf("card")
        val blockArgs = linkedMapOf("value" to "old")
        val figureAttributes = linkedMapOf("class" to "figure")
        val imageAttributes = linkedMapOf("class" to "image")
        val inlineArgs = linkedMapOf("value" to "old-inline")
        val document = Document().apply {
            appendChild(Table().apply { columnAlignments = alignments })
            appendChild(CustomContainer(cssClasses = classes))
            appendChild(DirectiveBlock("test", blockArgs))
            appendChild(Figure(attributes = figureAttributes))
            appendChild(Paragraph().apply {
                appendChild(Image("image.png", attributes = imageAttributes))
                appendChild(DirectiveInline("test", inlineArgs))
            })
        }
        val model = DefaultRenderModelCompiler.compile(document, environment)
        alignments[0] = Table.Alignment.RIGHT
        classes[0] = "changed"
        blockArgs["value"] = "changed"
        figureAttributes["class"] = "changed"
        imageAttributes["class"] = "changed"
        inlineArgs["value"] = "changed"
        assertEquals(listOf(Table.Alignment.LEFT), (model.blocks[0] as TableBlockModel).columnAlignments)
        assertEquals(listOf("card"), (model.blocks[1] as CustomContainerBlockModel).cssClasses)
        assertEquals("old", (model.blocks[2] as DirectiveBlockModel).args["value"])
        assertEquals("figure", (model.blocks[3] as FigureBlockModel).attributes["class"])
        val widgets = (model.blocks[4] as ParagraphBlockModel).inline.atoms.filterIsInstance<WidgetAtom>()
        assertEquals("image", (widgets[0].widget as ImageWidgetModel).attributes["class"])
        assertEquals("old-inline", (widgets[1].widget as DirectiveInlineWidgetModel).args["value"])
    }

    @Test
    fun should_protect_public_directive_snapshots_and_ir_collection_views_from_cast_mutation() {
        val document = Document().apply {
            appendChild(DirectiveBlock("block", linkedMapOf("value" to "old", "other" to "value")))
            appendChild(Paragraph().apply {
                appendChild(DirectiveInline("inline", linkedMapOf("value" to "old", "other" to "value")))
            })
            appendChild(CustomContainer(cssClasses = listOf("a", "b")))
        }
        val model = DefaultRenderModelCompiler.compile(document, environment)
        val block = model.blocks[0] as DirectiveBlockModel
        val inline = ((model.blocks[1] as ParagraphBlockModel).inline.atoms.single() as WidgetAtom)
            .widget as DirectiveInlineWidgetModel
        val blockSnapshot = createDirectiveBlockRenderScope(block.tagName, block.args).directive
        val inlineSnapshot = createDirectiveInlineRenderScope(inline.tagName, inline.args, inline.alternateText).directive
        for (args in listOf(blockSnapshot.args, inlineSnapshot.args)) {
            assertFails { (args as MutableMap<String, String>)["value"] = "corrupt" }
            assertFails { (args.entries.first() as MutableMap.MutableEntry<String, String>).setValue("corrupt") }
            assertFails { (args.keys as MutableSet<String>).clear() }
            assertFails { (args.values as MutableCollection<String>).clear() }
            assertEquals("old", args["value"])
        }
        val classes = (model.blocks[2] as CustomContainerBlockModel).cssClasses
        assertFails { (classes as MutableList<String>)[0] = "corrupt" }
        assertEquals(listOf("a", "b"), classes)
    }

    @Test
    fun should_splice_persistent_root_slots_with_hidden_nodes_and_keep_old_snapshots() {
        val random = Random(1837)
        var serial = 0L
        fun slot(): InternalRenderBlockModel? = if (random.nextInt(4) == 0) null else {
            val id = ++serial
            ThematicBreakBlockModel(RenderIdentity(id, id, id, 0L))
        }
        val expected = MutableList(256) { slot() }
        var slots = RenderBlockSlots.from(expected)
        repeat(1000) {
            val old = slots
            val oldValues = old.toList()
            val start = random.nextInt(expected.size + 1)
            val removed = random.nextInt(minOf(12, expected.size - start) + 1)
            val inserted = List(random.nextInt(12)) { slot() }
            slots = slots.splice(start, removed, inserted)
            repeat(removed) { expected.removeAt(start) }
            expected.addAll(start, inserted)
            assertEquals(expected.size, slots.slotCount)
            assertEquals(expected.filterNotNull(), slots.toList())
            assertEquals(expected.filterNotNull(), slots.indices.map { slots[it] })
            assertEquals(oldValues, old.toList())
        }
        val empty = slots.splice(0, slots.slotCount, emptyList())
        assertEquals(0, empty.slotCount)
        assertEquals(emptyList(), empty.toList())
    }
}
