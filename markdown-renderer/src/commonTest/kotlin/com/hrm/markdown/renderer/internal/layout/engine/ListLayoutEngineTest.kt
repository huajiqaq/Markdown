package com.hrm.markdown.renderer.internal.layout.engine

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.renderer.DiagramHostRegistry
import com.hrm.markdown.renderer.MarkdownConfig
import com.hrm.markdown.renderer.MarkdownTheme
import com.hrm.markdown.renderer.internal.MarkdownEngineHost
import com.hrm.markdown.renderer.internal.RendererFacadeState
import com.hrm.markdown.renderer.internal.layout.list.listItemContentIndentPx
import com.hrm.markdown.renderer.internal.layout.list.measureOrderedListMarkerMetrics
import com.hrm.markdown.renderer.internal.layout.model.InternalLayoutBlockModel
import com.hrm.markdown.renderer.internal.layout.model.InternalLayoutDocumentModel
import com.hrm.markdown.renderer.internal.layout.model.LayoutInlineBlockModel
import com.hrm.markdown.renderer.internal.layout.model.LayoutListBlockModel
import com.hrm.markdown.runtime.MarkdownDirectiveRegistry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ListLayoutEngineTest {
    @Test
    fun should_reserveCheckboxAndGapInNestedChildGeometry_when_taskTextWraps() = runComposeUiTest {
        val density = Density(density = 2f, fontScale = 1.5f)
        val theme = MarkdownTheme.light()
        val width = 640f
        var document: InternalLayoutDocumentModel? = null
        val markdown = """
- [ ] 外层任务正文比较长，需要换行以后仍然保持正文缩进。
  - [x] 内层任务正文也比较长，需要换行以后仍然保留间距。
- [x] 第二项任务正文
        """.trimIndent()
        setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                val facade = RendererFacadeState(
                    theme = theme,
                    config = MarkdownConfig.Default,
                    codeTheme = null,
                    imageRenderer = null,
                    onLinkClick = null,
                    directiveRegistry = MarkdownDirectiveRegistry.Empty,
                    isStreaming = false,
                    enableSelection = false,
                )
                val host = MarkdownEngineHost()
                document = host.layout(
                    renderDocument = host.compile(MarkdownParser().parse(markdown), facade),
                    facadeState = facade,
                    viewportWidth = width,
                    blockSpacing = with(density) { theme.blockSpacing.toPx() },
                    density = density,
                    textMeasurer = rememberTextMeasurer(),
                    latexMeasurer = rememberLatexMeasurer(),
                    diagramHostRegistry = DiagramHostRegistry(),
                )
            }
        }
        waitForIdle()
        val list = assertNotNull(document).blocks.single() as LayoutListBlockModel
        assertEquals(2, list.items.size)
        val nested = list.items.first().children.filterIsInstance<LayoutListBlockModel>().single()
        val markerWidth = with(density) { (20.dp + 8.dp).toPx() }
        val indent = with(density) { theme.listIndent.toPx() } + markerWidth
        for (item in list.items + nested.items) {
            assertTrue(item.taskListItem)
            assertClose(markerWidth, item.markerWidthPx, "checkbox column includes the gap")
            assertClose(item.frame.left + indent, item.contentFrame.left, "content start")
            assertClose(item.frame.width - indent, item.contentFrame.width, "content width")
            val paragraph = item.children.first() as LayoutInlineBlockModel
            assertClose(item.contentFrame.left, paragraph.contentFrame.left, "paragraph start")
            assertClose(item.contentFrame.width, paragraph.contentFrame.width, "paragraph width")
            assertInlineBlocksStayWithinListContent(item.children, item.contentFrame.width)
        }
        assertTrue((list.items.first().children.first() as LayoutInlineBlockModel).lines.size > 1)
        assertTrue((nested.items.single().children.first() as LayoutInlineBlockModel).lines.size > 1)
    }

    @Test
    fun should_fitProportionalDigitsAndFontScaling_when_markerMetricsUseSingleSample() = runComposeUiTest {
        setContent {
            val measurer = rememberTextMeasurer()
            val density = Density(density = 2f, fontScale = 1.5f)
            for (family in listOf(FontFamily.SansSerif, FontFamily.Serif, FontFamily.Monospace)) {
                val style = TextStyle(fontFamily = family, fontSize = 24.sp, letterSpacing = 0.5.sp,
                    fontFeatureSettings = "pnum")
                val metrics = measureOrderedListMarkerMetrics(style, density, measurer)
                for (number in (1..200).toList() + listOf(999, 1000, 987654321)) {
                    val width = measurer.measure("$number.", style = style, density = density, softWrap = false).size.width
                    assertTrue(metrics.widthPx(number.toString().length, density) >= width + with(density) { 4.dp.toPx() },
                        "Marker $number must fit with $family and scaled proportional digits")
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun should_reserveSharedMarkerWidthInChildGeometry_when_largeNumberingCrossesDigitBoundary() = runComposeUiTest {
        val theme = MarkdownTheme.light().copy(bodyStyle = TextStyle(fontSize = 24.sp, lineHeight = 36.sp))
        val width = 480f
        var document: InternalLayoutDocumentModel? = null
        var indent = 0f
        var unorderedIndent = 0f
        var minimumMarkerWidth = 0f
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1.5f)) {
                val density = LocalDensity.current
                val textMeasurer = rememberTextMeasurer()
                indent = with(density) { theme.listIndent.toPx() }
                unorderedIndent = density.listItemContentIndentPx(theme, taskListItem = false, ordered = false)
                minimumMarkerWidth = textMeasurer.measure("100.", style = theme.bodyStyle).size.width +
                    with(density) { 4.dp.toPx() }
                val facade = RendererFacadeState(
                    theme = theme,
                    config = MarkdownConfig.Default,
                    codeTheme = null,
                    imageRenderer = null,
                    onLinkClick = null,
                    directiveRegistry = MarkdownDirectiveRegistry.Empty,
                    isStreaming = false,
                    enableSelection = false,
                )
                val host = MarkdownEngineHost()
                document = host.layout(
                    renderDocument = host.compile(
                        MarkdownParser().parse("99. 第一项正文需要换行\n    - 嵌套正文也需要换行\n100. 第二项正文需要换行"),
                        facade,
                    ),
                    facadeState = facade,
                    viewportWidth = width,
                    blockSpacing = with(density) { theme.blockSpacing.toPx() },
                    density = density,
                    textMeasurer = textMeasurer,
                    latexMeasurer = rememberLatexMeasurer(),
                    diagramHostRegistry = DiagramHostRegistry(),
                )
            }
        }
        waitForIdle()
        val list = assertNotNull(document).blocks.single() as LayoutListBlockModel
        assertEquals(2, list.items.size)
        val markerWidth = list.items.first().markerWidthPx
        assertTrue(markerWidth >= minimumMarkerWidth)
        list.items.forEach { item ->
            assertEquals(markerWidth, item.markerWidthPx)
            assertClose(width - indent - markerWidth, item.contentFrame.width, "ordered item content width")
            val paragraph = item.children.filterIsInstance<LayoutInlineBlockModel>().first()
            assertClose(item.contentFrame.left, paragraph.contentFrame.left, "paragraph start")
            assertClose(item.contentFrame.width, paragraph.contentFrame.width, "paragraph width")
        }
        val nested = list.items.first().children.filterIsInstance<LayoutListBlockModel>().single().items.single()
        assertClose(list.items.first().contentFrame.width - unorderedIndent, nested.contentFrame.width, "nested width")
    }

    @Test
    fun should_measure_nested_list_children_with_marker_indent_removed_from_available_width() = runComposeUiTest {
        val markdown = """
- 第一层列表项包含很长的中文描述，并且紧跟多个标点，观察缩进后剩余宽度是否仍能正确换行。
  - 第二层列表项继续使用中英文混排 MixedTextWithoutSpaces 和中文标点（A、B、C）。
        """.trimIndent()
        val theme = MarkdownTheme()
        val viewportWidthPx = 180f
        var document: InternalLayoutDocumentModel? = null
        var unorderedIndentPx = 0f

        setContent {
            val density = LocalDensity.current
            val facadeState = RendererFacadeState(
                theme = theme,
                config = MarkdownConfig.Default,
                codeTheme = null,
                imageRenderer = null,
                onLinkClick = null,
                directiveRegistry = MarkdownDirectiveRegistry.Empty,
                isStreaming = false,
                enableSelection = false,
            )
            unorderedIndentPx = density.listItemContentIndentPx(
                theme = theme,
                taskListItem = false,
                ordered = false,
            )
            val host = MarkdownEngineHost()
            val renderDocument = host.compile(
                document = MarkdownParser().parse(markdown),
                facadeState = facadeState,
            )
            document = host.layout(
                renderDocument = renderDocument,
                facadeState = facadeState,
                viewportWidth = viewportWidthPx,
                blockSpacing = with(density) { theme.blockSpacing.toPx() },
                density = density,
                textMeasurer = rememberTextMeasurer(),
                latexMeasurer = rememberLatexMeasurer(),
                diagramHostRegistry = DiagramHostRegistry(),
            )
        }

        waitForIdle()

        val rootList = assertNotNull(document).blocks.single() as LayoutListBlockModel
        val firstItem = rootList.items.single()
        assertClose(
            expected = viewportWidthPx - unorderedIndentPx,
            actual = firstItem.contentFrame.width,
            label = "top-level list item content width",
        )

        val nestedList = firstItem.children.filterIsInstance<LayoutListBlockModel>().single()
        val nestedItem = nestedList.items.single()
        assertClose(
            expected = firstItem.contentFrame.width - unorderedIndentPx,
            actual = nestedItem.contentFrame.width,
            label = "nested list item content width",
        )
        assertInlineBlocksStayWithinListContent(nestedItem.children, nestedItem.contentFrame.width)
    }

    private fun assertInlineBlocksStayWithinListContent(
        blocks: List<InternalLayoutBlockModel>,
        maxWidthPx: Float,
    ) {
        blocks.forEach { block ->
            when (block) {
                is LayoutInlineBlockModel -> assertTrue(
                    actual = block.contentFrame.width <= maxWidthPx + 0.5f,
                    message = "Inline block width ${block.contentFrame.width} exceeded list content width $maxWidthPx.",
                )

                is LayoutListBlockModel -> block.items.forEach { item ->
                    assertInlineBlocksStayWithinListContent(item.children, item.contentFrame.width)
                }

                else -> Unit
            }
        }
    }

    private fun assertClose(
        expected: Float,
        actual: Float,
        label: String,
    ) {
        assertTrue(
            actual = abs(expected - actual) <= 0.5f,
            message = "Expected $label to be $expected px, got $actual px.",
        )
    }
}
