package com.hrm.markdown.renderer

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.renderer.internal.core.compile.DefaultRenderModelCompiler
import com.hrm.markdown.renderer.internal.core.compile.RenderCompileEnvironment
import com.hrm.markdown.renderer.internal.selection.buildSelectionIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkdownSoftBreakTest {
    @Test
    fun should_apply_soft_break_policy_to_nested_text_and_copy_without_changing_ast() {
        val document = MarkdownParser().parse("> first\n> **second\n> third**\n> fourth  \n> fifth")
        fun text(policy: MarkdownSoftBreakPolicy): String {
            val rendered = DefaultRenderModelCompiler.compile(document, RenderCompileEnvironment(
                softBreakPolicy = policy,
            ))
            return buildSelectionIndex(rendered.blocks).entries.joinToString("\n") { it.text }
        }
        assertEquals("first second third fourth\nfifth", text(MarkdownSoftBreakPolicy.Space))
        assertEquals("first\nsecond\nthird\nfourth\nfifth", text(MarkdownSoftBreakPolicy.LineBreak))
        assertEquals("first second third fourth\nfifth", text(MarkdownSoftBreakPolicy.Space))
    }

    @Test
    fun should_export_matching_html_and_preserve_explicit_override() {
        val policy = MarkdownSoftBreakPolicy.LineBreak
        assertEquals("<p>first\nsecond</p>\n", MarkdownHtml.render("first\nsecond"))
        assertEquals("<p>first<br />\nsecond</p>\n", MarkdownHtml.render("first\nsecond", softBreakPolicy = policy))
        assertEquals("<p>first<br>\nsecond</p>\n", MarkdownHtml.render("first\nsecond", softBreakPolicy = policy, xhtml = false))
        assertEquals("<p>first second</p>\n", MarkdownHtml.render("first\nsecond", softBreakPolicy = policy, softBreak = " "))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun should_keep_streaming_parser_snapshot_when_only_display_policy_changes() = runComposeUiTest {
        var policy by mutableStateOf(MarkdownSoftBreakPolicy.Space)
        var document: Document? = null
        setContent {
            val snapshot = rememberStreamingDocument("first\nsecond", true)
            androidx.compose.foundation.text.BasicText(policy.name)
            SideEffect { document = snapshot?.document }
        }
        waitForIdle()
        val original = document
        assertTrue(original != null)
        runOnIdle { policy = MarkdownSoftBreakPolicy.LineBreak }
        waitForIdle()
        assertSame(original, document)
    }
}
