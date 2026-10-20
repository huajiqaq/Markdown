package com.hrm.markdown.preview

import com.hrm.markdown.renderer.MarkdownComponents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hrm.markdown.renderer.Markdown
import com.hrm.markdown.renderer.MarkdownDefaults

private val fullDarkThemeMarkdown = """
# Markdown Dark Theme Showcase

这是一份用于展示 **强制暗色模式** 的完整 Markdown 文档，包含标题、列表、引用、代码块、表格、链接、脚注等常见元素。

## Feature Overview

- Bold / *italic* / ~~strikethrough~~
- `inline code`
- [GitHub](https://github.com/)
- Superscript H^2^O and subscript CO~2~

> 这是一个引用块，用来观察暗色主题下的边框和文字层级。

### Task List

- [x] 支持基础 Markdown
- [x] 支持脚注跳转
- [ ] 支持更多主题预设

### Code Block

```kotlin
@Composable
fun Greeting(name: String) {
    Text("Hello, ${'$'}name!")
}
```

### Table

| Feature | Status | Note |
|--------|--------|------|
| Theme | Done | `MarkdownDefaults.darkColors()` |
| Footnote | Done | Jump + back navigation |
| Slot | Done | Header / footer are in same scroll container |

### Footnotes

脚注引用示例[^theme]，用于验证暗色模式下的上标、跳转与返回链路。

[^theme]: 暗色主题示例脚注，应该使用深色背景与浅色正文样式正常展示。
""".trimIndent()

internal val themePreviewGroups = listOf(
    PreviewGroup(
        id = "forced_theme",
        title = "主题展示",
        description = "强制主题模式与完整文档示例",
        items = listOf(
            PreviewItem(
                id = "forced_dark_full_markdown",
                title = "强制暗色模式（完整 Markdown）",
                content = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0D1117))
                            .padding(12.dp)
                    ) {
                        Markdown(
                            markdown = fullDarkThemeMarkdown,
                            colors = MarkdownDefaults.darkColors(),
                            components = MarkdownComponents(header = {
                                Text(
                                    text = "Forced Dark Theme",
                                    color = Color(0xFFE6EDF3),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }),
                        )
                    }
                }
            )
        )
    )
)
