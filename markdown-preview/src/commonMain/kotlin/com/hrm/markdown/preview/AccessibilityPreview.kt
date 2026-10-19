package com.hrm.markdown.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hrm.markdown.renderer.Markdown
import com.hrm.markdown.renderer.MarkdownInteractions

private val accessibilityMarkdown = """
# 阅读与导航

这是一段包含 **粗体** 和 *斜体* 的长正文。调整窗口宽度后，视觉换行应保持源码阅读顺序，并合并为同一朗读单元。

安装指南
--------

阅读 [安装说明](https://example.com/install) 或 [使用指南](https://example.com/guide)。链接应保留独立的辅助操作。

## 只读任务状态

- [x] 已完成任务：正文与勾选状态一起呈现。
- [ ] 待完成任务：没有编辑操作。
  - [x] 内层任务应保持独立。
- [ ] 阅读 [任务文档](https://example.com/task)。

<h2>HTML 标题也参与标题导航</h2>
""".trimIndent()

internal val accessibilityPreviewGroup = PreviewGroup(
    id = "accessibility",
    title = "无障碍阅读",
    description = "标题导航、正文阅读顺序、链接操作与只读任务状态",
    items = listOf(
        PreviewItem(
            id = "accessible_document",
            title = "混合内容阅读",
            markdown = accessibilityMarkdown,
            content = {
                var lastLink by remember { mutableStateOf("尚未打开链接") }
                Column(Modifier.fillMaxWidth()) {
                    Text(lastLink)
                    Markdown(
                        markdown = accessibilityMarkdown,
                        enableScroll = false,
                        interactions = MarkdownInteractions(onLinkClick = { lastLink = "已打开：$it" }),
                    )
                }
            },
        ),
    ),
)
