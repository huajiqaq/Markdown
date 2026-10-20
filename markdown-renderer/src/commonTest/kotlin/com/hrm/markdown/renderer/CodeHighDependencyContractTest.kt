package com.hrm.markdown.renderer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.hrm.codehigh.renderer.CodeBlock
import kotlin.test.Test

/** One upstream dependency smoke check; Markdown integration is covered by its public entry point. */
@OptIn(ExperimentalTestApi::class)
class CodeHighDependencyContractTest {
    @Test
    fun should_expandAndCollapseZeroVisibleLines_when_codeHighDependencyIsLoaded() = runComposeUiTest {
        setContent {
            CodeBlock(code = "first line\nsecond line", maxVisibleLines = 0, showCopyButton = false)
        }
        onNodeWithText("first line").assertDoesNotExist()
        onNode(hasText("▼", substring = true)).performClick()
        onNodeWithText("first line").assertExists()
        onNodeWithText("second line").assertExists()
        onNode(hasText("▲", substring = true)).performClick()
        onNodeWithText("first line").assertDoesNotExist()
    }
}
