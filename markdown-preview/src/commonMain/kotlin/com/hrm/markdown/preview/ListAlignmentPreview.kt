package com.hrm.markdown.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.sp
import com.hrm.markdown.renderer.Markdown
import com.hrm.markdown.renderer.MarkdownComponents
import com.hrm.markdown.renderer.MarkdownTypography

private val listAlignmentMarkdown = """
## 任务框与正文间隔 8 dp

- [x] 支持基础 Markdown
- [x] 支持脚注跳转
- [ ] 支持更多主题预设
- [ ] 嵌套任务
  - [x] 内层复选框与正文也保留 8 dp 间距。

## 中英文首行基线

1. 中文正文与编号应在同一基线上。
2. English text aligns with its marker.

- 中文长列表正文换行后，圆点仍应与第一行基线对齐。

## 跨位数编号

99. 这里是较长的正文，需要换行，编号不能被截断。
100. 两项正文起点相同，编号右对齐。

## 标题开头

1. ### 列表内标题

   标题后的正文。

## 图片开头保持顶部对齐

- ![本地示意图](preview://list-image =120x72)

  图片下面的说明。

## 嵌套和任务列表

1. 外层正文
    1. 内层正文
    2. 内层第二项
2. 外层第二项

## 任务框对齐首行文字中心

- [ ] 未完成任务
- [x] 已完成任务
- [ ] 这一项正文较长，会换行，复选框应对齐首行文字中心，而不是整段中心。
- [x] English task text wraps onto multiple lines; align the checkbox with the first line.
- [ ] ### 任务列表内标题

  标题后还有正文，复选框应与标题首行居中。
- [ ] ![图片任务](preview://list-image =120x72)

  图片开头保持顶部对齐。
""".trimIndent()

internal val listAlignmentPreviewGroup = PreviewGroup(
    id = "list_alignment",
    title = "列表对齐回归",
    description = "任务框 8 dp 间距、首行对齐与大字号跨位数编号",
    items = listOf(
        PreviewItem(
            id = "list_alignment_cases",
            title = "任务框间距、列表基线与编号宽度",
            markdown = listAlignmentMarkdown,
            content = { ListAlignmentPreview() },
        ),
    ),
)

@Composable
private fun ListAlignmentPreview() {
    var width by remember { mutableStateOf(280.dp) }
    var fontSize by remember { mutableStateOf(16.sp) }
    val typography = MarkdownTypography()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "任务框与正文间隔 8 dp。切换宽度与字号，检查首行对齐、换行与嵌套。默认缩进仍为 24 dp。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(180.dp, 280.dp).forEach { value ->
                FilterChip(
                    selected = width == value,
                    onClick = { width = value },
                    label = { Text("${value.value.toInt()} dp") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(16.sp, 24.sp, 32.sp).forEach { value ->
                FilterChip(
                    selected = fontSize == value,
                    onClick = { fontSize = value },
                    label = { Text("${value.value.toInt()} sp") },
                )
            }
        }
        Box(Modifier.weight(1f).width(width).border(1.dp, MaterialTheme.colorScheme.outline).padding(8.dp)) {
            Markdown(
                markdown = listAlignmentMarkdown,
                modifier = Modifier.fillMaxSize(),
                typography = typography.copy(text = typography.text.copy(fontSize = fontSize, lineHeight = fontSize * 1.5f)),
                components = MarkdownComponents(image = { data, modifier ->
                    Box(
                        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = data.altText,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }),
            )
        }
    }
}
