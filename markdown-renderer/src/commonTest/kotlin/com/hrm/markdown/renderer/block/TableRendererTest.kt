package com.hrm.markdown.renderer.block

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import com.hrm.markdown.renderer.Markdown
import com.hrm.markdown.renderer.MarkdownTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TableRendererTest {
    @Test
    fun should_use_precomputed_geometry_and_keep_sourceColumnOrder_inRtl() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                TableGridLayout(
                    rowCount = 2,
                    columnCount = 2,
                    availableWidthPx = null,
                    fixedColumnWidthsPx = intArrayOf(40, 80),
                    fixedRowHeightsPx = intArrayOf(30, 50),
                ) {
                    Box(Modifier.testTag("r0c0"))
                    Box(Modifier.testTag("r0c1"))
                    Box(Modifier.testTag("r1c0"))
                    Box(Modifier.testTag("r1c1"))
                }
            }
        }

        val first = onNodeWithTag("r0c0").fetchSemanticsNode().boundsInRoot
        val second = onNodeWithTag("r0c1").fetchSemanticsNode().boundsInRoot
        val nextRow = onNodeWithTag("r1c0").fetchSemanticsNode().boundsInRoot

        assertEquals(40f, first.width, 0.5f)
        assertEquals(80f, second.width, 0.5f)
        assertEquals(30f, first.height, 0.5f)
        assertEquals(50f, nextRow.height, 0.5f)
        assertTrue(first.left < second.left, "RTL must not reverse Markdown source columns")
        assertTrue(first.top < nextRow.top)
    }

    @Test
    fun tableRowsShareASingleBorderLine() = runComposeUiTest {
        setContent {
            Markdown(
                markdown = "| English | 中文标题 |\n| --- | --- |\n| Latin | 中文正文 |\n| Second | 第二行 |",
                modifier = Modifier.fillMaxWidth().background(Color.White),
                theme = MarkdownTheme.light().copy(tableBorderColor = Color.Red),
                enableScroll = false,
                enableSelection = false,
            )
        }

        val pixels = onRoot().captureToImage().toPixelMap()

        fun isBorder(c: Color) = c.red > 0.8f && c.green < 0.6f && c.blue < 0.6f
        fun borderRows(x: Int) = (0 until pixels.height).filter { y -> isBorder(pixels[x, y]) }

        // 同一行的中英文单元格内容高度可能不同：网格线若由每个 cell 各自描边，左右两列量到的
        // 横线位置就不一致；改成整行共享一条线之后，两侧探测到的横线位置必须完全相同。
        val left = borderRows(2)
        val right = borderRows(pixels.width - 3)
        assertTrue(left.size >= 4, "表格边框没有被绘制")
        assertEquals(right, left, "同一行的左右单元格必须共享同一条横向网格线")

        // 三行表格只有四条横向边界线；数量变多说明 cell 又各自描了一遍边。
        val leftSet = left.toSet()
        assertEquals(4, left.count { it - 1 !in leftSet }, "每行边界只能有一条共享网格线")
    }
}
