package com.hrm.markdown.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hrm.markdown.renderer.Markdown

private const val FillingWidgetUrl = "preview://fill"
private const val ScrollingWidgetUrl = "preview://scroll"
private val longInlineFormula = (1..24).joinToString(" + ") { "a_{$it}" }

private val inlineOverflowMarkdown = """
## 1. 行首长公式

从 a₁ 滑到 a₂₄，确认公式尾部完整可见。

${'$'}$longInlineFormula${'$'}

## 2. 正文中的长公式

公式前的正文 ${'$'}$longInlineFormula${'$'} 公式后的正文应换到下一行，不能被公式覆盖。

短公式 ${'$'}a+b=c${'$'} 应正常显示，无需横向滚动。

## 3. 填满容器的组件

下面的蓝色组件应从“起点”滑到“终点”。

组件前 ![填满容器](preview://fill =900x56) 组件后：正文应正常换行。

## 4. 自带横向滚动的组件

下面的分段组件可以连续横向滑动，直到看到“终点 10”。

组件前 ![自带滚动](preview://scroll =900x56) 组件后：页面应保持可纵向滚动。
""".trimIndent()

internal val inlineOverflowPreviewGroup = PreviewGroup(
    id = "inline_overflow_scroll",
    title = "行内超宽滚动",
    description = "长公式、填满容器组件和嵌套横向滚动的手动验证",
    items = listOf(
        PreviewItem(
            id = "inline_overflow_scroll_cases",
            title = "超宽行内内容：公式与组件",
            markdown = inlineOverflowMarkdown,
            content = { InlineOverflowPreview() },
        ),
    ),
)

@Composable
private fun InlineOverflowPreview() {
    var viewportWidth by remember { mutableStateOf(280.dp) }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "切换容器宽度，再横向滑动每个公式或组件，检查末尾标记和后续正文。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(180.dp, 280.dp).forEach { width ->
                FilterChip(
                    selected = viewportWidth == width,
                    onClick = { viewportWidth = width },
                    label = { Text("${width.value.toInt()} dp") },
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .width(viewportWidth)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .padding(8.dp),
        ) {
            Markdown(
                markdown = inlineOverflowMarkdown,
                modifier = Modifier.fillMaxSize(),
                // Fixed image dimensions provide real inline slots; all demo content is local.
                imageContent = { data, modifier ->
                    when (data.url) {
                        FillingWidgetUrl -> FillingInlineWidget(modifier)
                        ScrollingWidgetUrl -> ScrollingInlineWidget(modifier)
                        else -> Text(data.altText, modifier = modifier)
                    }
                },
            )
        }
    }
}

@Composable
private fun FillingInlineWidget(modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("起点", color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.weight(1f))
        Text("终点", color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ScrollingInlineWidget(modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(10) { index ->
            val background = if (index % 2 == 0) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
            val foreground = if (index % 2 == 0) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            }
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .fillMaxHeight()
                    .background(background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (index) {
                        0 -> "起点 1"
                        9 -> "终点 10"
                        else -> "第 ${index + 1} 段"
                    },
                    color = foreground,
                )
            }
        }
    }
}
