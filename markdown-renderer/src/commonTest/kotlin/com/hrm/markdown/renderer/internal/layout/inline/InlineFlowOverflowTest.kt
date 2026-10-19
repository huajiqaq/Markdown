package com.hrm.markdown.renderer.internal.layout.inline

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.hrm.markdown.renderer.inline.InlinePlaceholderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InlineFlowOverflowTest {
    @Test
    fun overWideInlineContentIsClampedToTheAvailableLineWidth() = runComposeUiTest {
        var layout: InlineFlowLayout? = null
        setContent {
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            layout = computeInlineFlowLayout(
                input = InlineFlowInput(
                    segments = listOf(
                        InlineFlowSegment.TextRun(AnnotatedString("前后")),
                        InlineFlowSegment.InlineRun(
                            id = InlinePlaceholderId(1L),
                            placeholder = InlinePlaceholderLayoutSpec(
                                alternateText = "长公式",
                                widthPx = 900f,
                                heightPx = 40f,
                            ),
                        ),
                    ),
                ),
                style = TextStyle(fontSize = 16.sp),
                density = density,
                textMeasurer = textMeasurer,
                maxWidthPx = 300f,
                maxLines = Int.MAX_VALUE,
            )
        }

        val computed = requireNotNull(layout)
        val math = computed.lines
            .flatMap { it.items }
            .filterIsInstance<LineItem.InlineItem>()
            .single()
        assertEquals(300f, math.widthPx, 0.01f, "超宽行内内容在排版时必须夹到可用行宽")
        assertTrue(
            computed.lines.all { it.lineWidthPx <= 300f },
            "任何一行的宽度都不得超出可用宽度",
        )
    }
}
