package com.hrm.markdown.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RendererExtensionsPreviewTest {
    @Test
    fun should_keep_nested_code_fully_visible_after_clicking_actual_preview_toolbar() = runComposeUiTest {
        val sample = rendererExtensionsPreviewGroup.items.single { it.id == "standard_block_chrome" }
        setContent {
            MaterialTheme {
                Box(Modifier.width(760.dp)) {
                    LazyColumn { item { PreviewCard(sample, modifier = Modifier.heightIn(max = 280.dp)) } }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("查看代码", useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        waitForIdle()
        fun codeDimensions(): Pair<Float, Int> {
            val code = onNodeWithText("嵌套代码块也保留操作栏高度", useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            code.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return code.fetchSemanticsNode().boundsInRoot.height to layouts.single().size.height
        }
        val before = codeDimensions()
        onNodeWithText("查看代码", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()
        val after = codeDimensions()
        // The code body has a horizontal scroller; target the surrounding vertical viewport.
        val viewport = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true,
        ).onLast()
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000f) }
        waitForIdle()
        val scrolled = codeDimensions()
        assertTrue(viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        assertTrue(scrolled.first >= scrolled.second && scrolled.first > 0f,
            "Nested code clipped after toolbar action and scrolling: before=$before, after=$after, scrolled=$scrolled")
    }
}
