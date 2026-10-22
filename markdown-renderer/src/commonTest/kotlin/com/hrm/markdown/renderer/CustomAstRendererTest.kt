package com.hrm.markdown.renderer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.ast.Text
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(ExperimentalTestApi::class)
class CustomAstRendererTest {
    @Test
    fun should_render_new_parser_commits_and_reject_mutating_published_nodes() = runComposeUiTest {
        val parser = MarkdownParser()
        var document by mutableStateOf(parser.parse("before"))
        val text = (document.children.single() as Paragraph).children.single() as Text

        setContent {
            Markdown(
                document = document,
                modifier = Modifier.width(320.dp),
                enableScroll = false,
                enableSelection = false,
            )
        }

        onNodeWithText("before").assertExists()
        runOnIdle {
            assertFailsWith<IllegalStateException> { text.literal = "untracked" }
            document = parser.replace(0, "before".length, "after")
        }
        onNodeWithText("before").assertDoesNotExist()
        onNodeWithText("after").assertExists()
    }
}
