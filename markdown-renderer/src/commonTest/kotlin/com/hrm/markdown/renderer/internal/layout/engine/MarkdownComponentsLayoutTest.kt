package com.hrm.markdown.renderer.internal.layout.engine

import com.hrm.markdown.renderer.MarkdownComponents

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.renderer.*
import com.hrm.markdown.renderer.internal.MarkdownEngineHost
import com.hrm.markdown.renderer.internal.RendererFacadeState
import com.hrm.markdown.renderer.internal.layout.model.LayoutRenderBlockModel
import com.hrm.markdown.runtime.MarkdownDirectiveRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class MarkdownComponentsLayoutTest {
    @Test
    fun should_invalidate_only_affected_blocks_and_include_nested_header_height() = runComposeUiTest {
        val sessions = mutableListOf<MarkdownLayoutSession>()
        var densityValue = 1f
        setContent {
            val density = LocalDensity.current
            densityValue = density.density
            val textMeasurer = rememberTextMeasurer()
            val latexMeasurer = rememberLatexMeasurer()
            val host = remember { MarkdownEngineHost() }
            val facade = remember {
                RendererFacadeState(ResolvedMarkdownStyle(), MarkdownRenderOptions.Default, MarkdownDirectiveRegistry.Empty, false)
            }
            val document = remember {
                host.compile(MarkdownParser().parse("""
                    plain

                    ```kotlin
                    value
                    ```

                    | table |
                    | --- |
                    | cell |

                    > ```kotlin
                    > nested
                    > ```
                """.trimIndent()), facade)
            }
            val registry = remember { DiagramHostRegistry() }
            remember(density, textMeasurer, latexMeasurer) {
                for (height in listOf(0.dp, 40.dp, 60.dp)) {
                    sessions += host.layoutSession(
                        document, facade, 320f,
                        density = density, textMeasurer = textMeasurer,
                        latexMeasurer = latexMeasurer, diagramHostRegistry = registry,
                        components = MarkdownComponents(
                            codeBlockHeader = MarkdownBlockSlot(height) {},
                            tableToolbar = MarkdownBlockSlot(20.dp) {},
                        ),
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle {
            val (base, first, second) = sessions
            assertSame(base.blockAt(0), second.blockAt(0))
            assertSame(first.blockAt(2), second.blockAt(2))
            assertNotSame(first.blockAt(1), second.blockAt(1))
            assertEquals(40f * densityValue, first.blockAt(1).frame.height - base.blockAt(1).frame.height, 0.01f)
            assertEquals(20f * densityValue, second.blockAt(3).frame.height - first.blockAt(3).frame.height, 0.01f)
            val nested = (second.blockAt(3) as LayoutRenderBlockModel).children.single()
            assertEquals(60f * densityValue, nested.contentFrame.top - nested.frame.top, 0.01f)
        }
    }
}
