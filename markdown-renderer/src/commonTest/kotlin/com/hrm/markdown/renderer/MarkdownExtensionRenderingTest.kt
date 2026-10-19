package com.hrm.markdown.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hrm.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MarkdownExtensionRenderingTest {
    @Test
    fun should_show_complete_nested_code_body_with_header_at_multiple_font_scales() {
        for (fontScale in listOf(1f, 1.8f)) runComposeUiTest {
            val document = MarkdownParser().parse("> ```kotlin {title=sample.kt}\n> val nestedValue = 42\n> ```\n\nafter")
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.width(360.dp)) {
                        Markdown(
                            document,
                            enableScroll = false,
                            components = MarkdownComponents(codeBlockHeader = MarkdownBlockSlot(48.dp) {
                                BasicText("custom toolbar")
                            }),
                        )
                    }
                }
            }
            waitForIdle()
            val code = onNodeWithText("val nestedValue = 42", useUnmergedTree = true)
            code.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            code.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val bounds = code.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= layouts.single().size.height, "Code body was clipped at fontScale=$fontScale")
            val after = onNodeWithText("after", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(after.top >= bounds.bottom, "Following content overlapped code")
        }
    }

    @Test
    fun should_enable_citation_actions_and_use_latest_callback_with_selection_disabled() = runComposeUiTest {
        var interactions by mutableStateOf(MarkdownInteractions.Default)
        val document = MarkdownParser().parse("Claim [@source].")
        val calls = mutableListOf<String>()
        var event: MarkdownCitationClickEvent? = null
        setContent {
            Markdown(document, enableScroll = false, enableSelection = false, interactions = interactions)
        }
        waitForIdle()
        runOnIdle {
            interactions = MarkdownInteractions(onCitationClick = { event = it; calls += "old" })
        }
        waitForIdle()
        onAllNodes(hasClickAction(), useUnmergedTree = true).onFirst().performClick()
        fun textLayout(): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasText("source", substring = true),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        val before = textLayout()
        runOnIdle { interactions = MarkdownInteractions(onCitationClick = { event = it; calls += "new" }) }
        waitForIdle()
        assertSame(before.multiParagraph, textLayout().multiParagraph, "Replacing a handler must reuse shaped text")
        onAllNodes(hasClickAction(), useUnmergedTree = true).onFirst().performClick()
        assertEquals(listOf("old", "new"), calls)
        assertEquals("source", assertNotNull(event).key)
        assertTrue(assertNotNull(event.anchorBoundsInWindow).width > 0)
    }

    @Test
    fun should_use_platform_uri_handler_when_link_action_is_not_overridden() = runComposeUiTest {
        var opened: String? = null
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { opened = uri }
        }
        val document = MarkdownParser().parse("[guide](https://example.com/guide)")
        setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                Markdown(document, enableScroll = false, enableSelection = false)
            }
        }
        onAllNodes(hasClickAction(), useUnmergedTree = true).onFirst().performClick()
        assertEquals("https://example.com/guide", opened)
    }

    @Test
    fun should_wrap_custom_images_and_resolve_gallery_with_selection_disabled() = runComposeUiTest {
        var event: MarkdownImageClickEvent? = null
        val document = MarkdownParser().parse("prefix ![first](same.png) ![second](same.png)")
        setContent {
            Markdown(
                document, enableScroll = false, enableSelection = false,
                components = MarkdownComponents(image = { data, modifier -> BasicText(data.altText, modifier) }),
                interactions = MarkdownInteractions(onImageClick = { event = it }),
            )
        }
        waitForIdle()
        onAllNodes(hasClickAction(), useUnmergedTree = true)[1].performClick()
        val click = assertNotNull(event)
        assertEquals("second", click.image.altText)
        assertEquals(1, click.gallery.initialIndex)
        assertEquals(2, click.gallery.images.size)
        assertTrue(assertNotNull(click.anchorBoundsInWindow).width > 0)
    }

    @Test
    fun should_measure_chrome_before_body_and_keep_toolbar_outside_table_scroll() = runComposeUiTest {
        val document = MarkdownParser().parse("""
            ```kotlin
            val value = 1
            ```

            | header |
            | --- |
            | body |
        """.trimIndent())
        setContent {
            Markdown(
                document,
                enableScroll = false,
                components = MarkdownComponents(
                    codeBlockHeader = MarkdownBlockSlot(36.dp) {
                        Box(Modifier.size(10.dp).testTag("code-toolbar"))
                    },
                    tableToolbar = MarkdownBlockSlot(28.dp) {
                        BasicText("table toolbar", Modifier.testTag("table-toolbar"))
                    },
                ),
            )
        }
        waitForIdle()
        val toolbar = onNodeWithTag("table-toolbar").fetchSemanticsNode().boundsInRoot
        val body = onNodeWithText("header", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(body.top >= toolbar.bottom)
        onNodeWithTag("code-toolbar").assertExists()
    }
}
