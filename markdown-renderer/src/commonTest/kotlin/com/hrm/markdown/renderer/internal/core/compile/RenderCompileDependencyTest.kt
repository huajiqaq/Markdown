package com.hrm.markdown.renderer.internal.core.compile

import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.renderer.MarkdownRenderOptions
import com.hrm.markdown.renderer.MarkdownSoftBreakPolicy
import com.hrm.markdown.renderer.ResolvedMarkdownStyle
import com.hrm.markdown.renderer.internal.MarkdownEngineHost
import com.hrm.markdown.renderer.internal.RendererFacadeState
import com.hrm.markdown.renderer.internal.core.model.HeadingBlockModel
import com.hrm.markdown.renderer.internal.selection.buildSelectionIndex
import com.hrm.markdown.runtime.MarkdownBlockDirectiveRenderer
import com.hrm.markdown.runtime.MarkdownDirectivePlugin
import com.hrm.markdown.runtime.MarkdownDirectiveRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RenderCompileDependencyTest {
    @Test
    fun should_retain_ir_when_only_streaming_or_directive_renderers_change() {
        val document = MarkdownParser().parse("# Title\n\nfirst\nsecond\n\n{% custom %}")
        val session = RenderCompileSession()
        val host = MarkdownEngineHost(compiler = session)
        val facade = RendererFacadeState(
            theme = ResolvedMarkdownStyle(),
            config = MarkdownRenderOptions.Default,
            directiveRegistry = MarkdownDirectiveRegistry.Empty,
            isStreaming = true,
        )
        val original = host.compile(document, facade)
        val plugin = object : MarkdownDirectivePlugin {
            override val id = "render-only"
            override val blockDirectiveRenderers: Map<String, MarkdownBlockDirectiveRenderer> = mapOf("custom" to { })
        }
        val registry = MarkdownDirectiveRegistry(listOf(plugin))
        val changed = host.compile(document, facade.copy(directiveRegistry = registry, isStreaming = false))

        assertSame(original, changed)
        assertEquals(0, session.lastUpdate.visitedNodes)
        assertEquals(0, session.lastUpdate.compiledRootBlocks)
        assertFalse(session.lastUpdate.coldRebuild)
    }

    @Test
    fun should_recompile_and_update_visible_semantics_when_soft_breaks_or_heading_numbering_change() {
        val document = MarkdownParser().parse("# Title\n\nfirst\nsecond")
        val session = RenderCompileSession()
        val host = MarkdownEngineHost(compiler = session)
        val facade = RendererFacadeState(
            ResolvedMarkdownStyle(), MarkdownRenderOptions.Default, MarkdownDirectiveRegistry.Empty, false,
        )
        val original = host.compile(document, facade)
        assertNull((original.blocks.first() as HeadingBlockModel).numbering)
        assertEquals("first second", buildSelectionIndex(original.blocks).entries.last().text)

        val updated = host.compile(document, facade.copy(config = facade.config.copy(
            softBreakPolicy = MarkdownSoftBreakPolicy.LineBreak,
            enableHeadingNumbering = true,
        )))
        assertNotSame(original, updated)
        assertTrue(session.lastUpdate.coldRebuild)
        assertTrue(session.lastUpdate.visitedNodes > 0)
        assertEquals("1", (updated.blocks.first() as HeadingBlockModel).numbering)
        assertEquals("first\nsecond", buildSelectionIndex(updated.blocks).entries.last().text)
        assertEquals(DefaultRenderModelCompiler.compile(document, RenderCompileEnvironment(
            enableHeadingNumbering = true,
            softBreakPolicy = MarkdownSoftBreakPolicy.LineBreak,
        )), updated)
        assertSame(updated, host.compile(document, facade.copy(config = facade.config.copy(
            softBreakPolicy = MarkdownSoftBreakPolicy.LineBreak,
            enableHeadingNumbering = true,
        ), isStreaming = true)))
    }
}
