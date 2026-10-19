package com.hrm.markdown.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.hrm.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MarkdownAccessibilityTest {
    @Test
    fun should_exposeHeadingTextOnce_when_renderingAtxSetextAndHtmlHeadings() {
        for (scroll in listOf(false, true)) runComposeUiTest {
            setContent { AccessibilitySample("# ATX title\n\nSetext title\n===\n\n<h2>HTML title</h2>", scroll) }
            val headings = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .fetchSemanticsNodes()
            assertEquals(listOf("ATX title", "Setext title", "HTML title"), headings.map { node ->
                node.config[SemanticsProperties.Text].joinToString("") { it.text }
            })
        }
    }

    @Test
    fun should_mergeWrappedTextInSourceOrder_when_paragraphUsesInlineStyles() = runComposeUiTest {
        val text = "开头文字接着粗体然后继续阅读直到段落结束，换行以后也应按照源码顺序朗读。"
        setContent { AccessibilitySample("开头文字**接着粗体**然后继续阅读直到段落结束，换行以后也应按照源码顺序朗读。", width = 120) }
        val nodes = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
        assertEquals(1, nodes.size, "A wrapped paragraph should expose one reading unit")
        assertEquals(text, nodes.single().config[SemanticsProperties.Text].joinToString("") { it.text })
        assertTrue(nodes.single().config[SemanticsProperties.IsTraversalGroup])
        val fragments = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TraversalIndex),
            useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(fragments.size > 1, "Exercise a paragraph painted using multiple fragments")
        assertEquals(fragments.indices.map { it.toFloat() }, fragments.map {
            it.config[SemanticsProperties.TraversalIndex]
        })
    }

    @Test
    fun should_exposeLabelAndReadOnlyStateOnce_when_renderingTasks() {
        for (scroll in listOf(false, true)) runComposeUiTest {
            setContent { AccessibilitySample("- [x] **Completed** task\n- [ ] Pending task", scroll) }
            val tasks = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
                .fetchSemanticsNodes()
            assertEquals(2, tasks.size)
            assertEquals(listOf(ToggleableState.On, ToggleableState.Off), tasks.map {
                it.config[SemanticsProperties.ToggleableState]
            })
            assertEquals(listOf("Completed task", "Pending task"), tasks.map {
                it.config[SemanticsProperties.Text].joinToString("") { part -> part.text }
            })
            tasks.forEachIndexed { index, task ->
                assertEquals(index, task.config[SemanticsProperties.CollectionItemInfo].rowIndex)
                assertFalse(task.config.contains(SemanticsActions.OnClick), "Read-only tasks must not pretend to be editable")
            }
            onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
                useUnmergedTree = true).assertCountEquals(2)
            val collection = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo))
                .fetchSemanticsNodes().map { it.config[SemanticsProperties.CollectionInfo] }
                .single { it.rowCount == 2 }
            assertEquals(2, collection.rowCount)
            assertEquals(1, collection.columnCount)
        }
    }

    @Test
    fun should_preserveNativeLinkAction_when_mergingParagraphAndTaskText() {
        for (source in listOf("Read [the guide](/guide).", "- [x] Read [the guide](/guide).")) runComposeUiTest {
            var clicked: String? = null
            setContent { AccessibilitySample(source, onLinkClick = { clicked = it }) }
            onAllNodes(hasClickAction(), useUnmergedTree = true).onFirst().performClick()
            assertEquals("/guide", clicked)
        }
    }

    @Test
    fun should_keepNestedItemsSeparate_when_mergingParentListText() = runComposeUiTest {
        setContent { AccessibilitySample("- [x] Parent\n  - [ ] Child\n- [ ] Sibling") }
        val tasks = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .fetchSemanticsNodes()
        assertEquals(3, tasks.size)
        assertEquals(setOf("Parent", "Child", "Sibling"), tasks.map {
            it.config[SemanticsProperties.Text].joinToString("") { part -> part.text }
        }.toSet())
    }
}

@Composable
private fun AccessibilitySample(
    source: String,
    scroll: Boolean = false,
    width: Int = 300,
    onLinkClick: ((String) -> Unit)? = null,
) {
    Box(Modifier.size(width.dp, 500.dp)) {
        Markdown(
            document = MarkdownParser().parse(source),
            theme = MarkdownTheme.light(),
            enableScroll = scroll,
            interactions = MarkdownInteractions(onLinkClick = onLinkClick),
        )
    }
}
