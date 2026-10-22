package com.hrm.markdown.renderer

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.html.HtmlRenderer
import com.hrm.markdown.runtime.MarkdownBlockDirectiveRenderer
import com.hrm.markdown.runtime.MarkdownDirectivePlugin
import com.hrm.markdown.runtime.MarkdownInputTransformer
import com.hrm.markdown.runtime.MarkdownSourceMap
import com.hrm.markdown.runtime.MarkdownTransformResult
import com.hrm.markdown.runtime.MarkdownTransformerStreamingSupport
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MarkdownPluginLifecycleTest {
    @Test
    fun should_keep_streaming_document_and_mapping_when_only_renderers_change() =
        assertRendererReplacementKeepsSession(SegmentTransformer())

    @Test
    fun should_keep_streaming_document_when_render_only_plugins_have_no_transformers() =
        assertRendererReplacementKeepsSession(null)

    @Test
    fun should_keep_full_parse_document_and_mapping_when_only_renderers_change() =
        assertRendererReplacementKeepsSession(SegmentTransformer(), isStreaming = false)

    private fun assertRendererReplacementKeepsSession(
        transformer: SegmentTransformer?,
        isStreaming: Boolean = true,
    ) = runComposeUiTest {
        val body = "stable\n\n{% custom %}\n{% endcustom %}\n\ntail"
        val page = TestPage(if (transformer == null) body else "input|$body", transformer, isStreaming)
        setContent { page.Content() }
        waitUntil(timeoutMillis = 10_000) { page.snapshots.isNotEmpty() }
        onNodeWithText("first-renderer").assertExists()
        onNodeWithText("tail").assertExists()
        val original = page.latest
        assertTrue(original.document.children.last() is Paragraph, "尾段必须位于已闭合指令之外的顶层")
        val stableBlock = original.document.children.first()
        val lineage = assertNotNull(original.document.publication).lineageId
        val transformCalls = transformer?.calls?.get()

        runOnIdle {
            val replacement = listOf(renderPlugin("second-renderer", transformer))
            assertEquals(page.plugins, replacement)
            page.plugins = replacement
        }
        waitForIdle()
        onNodeWithText("second-renderer").assertExists()
        onNodeWithText("first-renderer").assertDoesNotExist()
        runOnIdle {
            assertSame(original.document, page.latest.document)
            assertSame(original.sourceMap, page.latest.sourceMap)
            assertSame(lineage, assertNotNull(page.latest.document.publication).lineageId)
            assertEquals(transformCalls, transformer?.calls?.get())
        }

        if (isStreaming) {
            runOnIdle { page.markdown += " growth" }
            waitUntil(timeoutMillis = 10_000) { page.snapshots.lastOrNull()?.html?.contains("tail growth") == true }
            runOnIdle {
                assertSame(lineage, assertNotNull(page.latest.document.publication).lineageId)
                assertSame(stableBlock, page.latest.document.children.first())
            }
            onNodeWithText("second-renderer").assertExists()
            onNodeWithText("tail growth").assertExists()
        }
    }

    @Test
    fun should_restart_source_processing_when_same_id_transformer_is_replaced() = runComposeUiTest {
        val first = SegmentTransformer(beforeSeparator = true)
        val page = TestPage("left|right", first)
        setContent { page.Content() }
        waitUntil(timeoutMillis = 10_000) { page.snapshots.lastOrNull()?.html == "<p>left</p>\n" }
        val original = page.latest
        val originalLineage = assertNotNull(original.document.publication).lineageId
        assertEquals(0, original.sourceMap.mapOutputOffset(0))

        val replacement = SegmentTransformer()
        runOnIdle {
            val plugins = listOf(renderPlugin("second-renderer", replacement))
            assertEquals(page.plugins, plugins)
            page.plugins = plugins
        }
        waitUntil(timeoutMillis = 10_000) { page.snapshots.lastOrNull()?.html == "<p>right</p>\n" }
        runOnIdle {
            assertNotSame(original.document, page.latest.document)
            assertNotSame(originalLineage, assertNotNull(page.latest.document.publication).lineageId)
            assertEquals(5, page.latest.sourceMap.mapOutputOffset(0))
            assertEquals(10, page.latest.sourceMap.mapOutputOffset(5))
            assertEquals(1, first.calls.get())
            assertEquals(1, replacement.calls.get())
            assertTrue(assertNotNull(page.latest.document.publication).isCurrent)
        }
        onNodeWithText("right").assertExists()
        onNodeWithText("left").assertDoesNotExist()
    }

    @Test
    fun should_update_document_overload_renderer_when_replacement_plugin_is_value_equal() = runComposeUiTest {
        val document = MarkdownParser().parse("{% custom %}")
        val original = listOf(renderPlugin("first-renderer", null))
        var plugins by mutableStateOf(original, referentialEqualityPolicy())
        setContent {
            Markdown(document = document, directivePlugins = plugins, enableScroll = false, enableSelection = false)
        }
        onNodeWithText("first-renderer").assertExists()

        val replacement = listOf(renderPlugin("second-renderer", null))
        assertEquals(original, replacement)
        runOnIdle { plugins = replacement }
        onNodeWithText("second-renderer").assertExists()
        onNodeWithText("first-renderer").assertDoesNotExist()
        assertTrue(assertNotNull(document.publication).isCurrent)
    }

    private class TestPage(
        markdown: String,
        transformer: SegmentTransformer?,
        private val isStreaming: Boolean = true,
    ) {
        var markdown by mutableStateOf(markdown)
        // 显式发布值相等的新列表，验证组件本身不会被插件的 equals 遮蔽更新。
        var plugins by mutableStateOf(listOf(renderPlugin("first-renderer", transformer)), referentialEqualityPolicy())
        val snapshots = ConcurrentLinkedQueue<PublishedSnapshot>()
        val latest get() = assertNotNull(snapshots.lastOrNull())

        @Composable
        fun Content() = Markdown(
            markdown = markdown,
            isStreaming = isStreaming,
            directivePlugins = plugins,
            enableScroll = false,
            enableSelection = false,
            components = MarkdownComponents(header = {
                val document = LocalRendererDocument.current
                val sourceMap = LocalMarkdownSourceMap.current
                SideEffect { snapshots += PublishedSnapshot(document, HtmlRenderer().render(document), sourceMap) }
            }),
        )
    }

    private data class PublishedSnapshot(val document: Document, val html: String, val sourceMap: MarkdownSourceMap)

    private class SegmentTransformer(private val beforeSeparator: Boolean = false) : MarkdownInputTransformer {
        override val id = "select-input-segment"
        override val streamingSupport = MarkdownTransformerStreamingSupport.AppendSafe
        val calls = AtomicInteger()

        override fun transform(input: String): MarkdownTransformResult {
            calls.incrementAndGet()
            val separator = input.indexOf('|')
            val start = if (beforeSeparator) 0 else separator + 1
            val end = if (beforeSeparator && separator >= 0) separator else input.length
            val output = input.substring(start, end)
            return MarkdownTransformResult(output, MarkdownSourceMap.Segmented(listOf(
                MarkdownSourceMap.Segmented.Segment(0, output.length, start, end),
            )))
        }
    }

    private class RenderPlugin(label: String, transformer: MarkdownInputTransformer?) : MarkdownDirectivePlugin {
        override val id = "renderer-plugin"
        override val inputTransformers = listOfNotNull(transformer)
        override val blockDirectiveRenderers: Map<String, MarkdownBlockDirectiveRenderer> = mapOf(
            "custom" to { BasicText(label) },
        )
        override fun equals(other: Any?): Boolean = other is RenderPlugin && id == other.id
        override fun hashCode(): Int = id.hashCode()
    }

    companion object {
        private fun renderPlugin(label: String, transformer: MarkdownInputTransformer?): MarkdownDirectivePlugin =
            RenderPlugin(label, transformer)
    }
}
