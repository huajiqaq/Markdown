package com.hrm.markdown.renderer

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.v2.runComposeUiTest
import com.hrm.markdown.parser.MarkdownParserConfig
import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.ast.FencedCodeBlock
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MarkdownPublicationLifecycleTest {
    @Test
    fun should_render_each_publication_when_open_fence_reuses_ast_nodes() =
        assertOpenFenceUpdates(parseInComponent = false)

    @Test
    fun should_render_each_source_append_when_open_fence_reuses_ast_nodes() =
        assertOpenFenceUpdates(parseInComponent = true)

    private fun assertOpenFenceUpdates(parseInComponent: Boolean) = runComposeUiTest {
        val config = MarkdownParserConfig(flavour = CommonMarkFlavour)
        val parser = config.newParser()
        parser.beginStream()
        var source by mutableStateOf("prefix\n\n```text\nseed")
        var publicationRevision by mutableLongStateOf(0L)
        if (!parseInComponent) publicationRevision = assertNotNull(parser.append(source).publication).revision
        var published: Document? = null
        // 固定插槽与组件实例，确保界面更新确实来自新提交，而非附带参数变化。
        val components = MarkdownComponents(header = {
            val document = LocalRendererDocument.current
            SideEffect { published = document }
        })
        setContent {
            if (parseInComponent) {
                Markdown(
                    markdown = source,
                    parserConfig = config,
                    isStreaming = true,
                    components = components,
                    enableScroll = false,
                    enableSelection = false,
                )
            } else {
                // Document 是当前读取视图，调用方通过提交版本变化请求重新读取。
                publicationRevision
                Markdown(
                    document = parser.document,
                    isStreaming = true,
                    components = components,
                    enableScroll = false,
                    enableSelection = false,
                )
            }
        }
        fun hasCode(expected: String): Boolean =
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes().any { node ->
                node.config[SemanticsProperties.Text].joinToString("") { it.text }.trimEnd('\n') == expected.trimEnd('\n')
            }
        waitUntil(timeoutMillis = 10_000) { hasCode("seed") }
        val original = assertNotNull(published)
        val prefix = original.children.first()
        val fence = original.children.filterIsInstance<FencedCodeBlock>().single()
        val lineage = assertNotNull(original.publication).lineageId
        var code = "seed"
        var previousRevision = assertNotNull(original.publication).revision
        repeat(3) { index ->
            val chunk = " body-$index"
            code += chunk
            runOnIdle {
                source += chunk
                if (!parseInComponent) {
                    val document = parser.append(chunk)
                    assertSame(fence, document.children.filterIsInstance<FencedCodeBlock>().single())
                    publicationRevision = assertNotNull(document.publication).revision
                }
            }
            waitUntil(timeoutMillis = 10_000) { hasCode(code) }
            runOnIdle {
                val document = assertNotNull(published)
                assertSame(prefix, document.children.first())
                assertSame(fence, document.children.filterIsInstance<FencedCodeBlock>().single())
                val publication = assertNotNull(document.publication)
                assertSame(lineage, publication.lineageId)
                val revision = publication.revision
                assertTrue(revision > previousRevision)
                previousRevision = revision
            }
        }
    }
}
