package com.hrm.markdown.preview

import androidx.compose.runtime.Composable
import com.hrm.markdown.renderer.Markdown
import com.hrm.markdown.renderer.MarkdownParserConfig

internal val headingPreviewGroups = listOf(
    PreviewGroup(
        id = "atx_headings",
        title = "ATX 标题",
        description = "1-6 级标题",
        items = listOf(
            PreviewItem(
                id = "h1",
                title = "一级标题",
                markdown = "# 一级标题",
                content = { Markdown(markdown = "# 一级标题") }
            ),
            PreviewItem(
                id = "h2",
                title = "二级标题",
                markdown = "## 二级标题",
                content = { Markdown(markdown = "## 二级标题") }
            ),
            PreviewItem(
                id = "h3",
                title = "三级标题",
                markdown = "### 三级标题",
                content = { Markdown(markdown = "### 三级标题") }
            ),
            PreviewItem(
                id = "h4",
                title = "四级标题",
                markdown = "#### 四级标题",
                content = { Markdown(markdown = "#### 四级标题") }
            ),
            PreviewItem(
                id = "h5",
                title = "五级标题",
                markdown = "##### 五级标题",
                content = { Markdown(markdown = "##### 五级标题") }
            ),
            PreviewItem(
                id = "h6",
                title = "六级标题",
                markdown = "###### 六级标题",
                content = { Markdown(markdown = "###### 六级标题") }
            ),
        )
    ),
    PreviewGroup(
        id = "heading_with_inline",
        title = "标题中的行内元素",
        description = "标题中包含粗体、斜体、代码等",
        items = listOf(
            PreviewItem(
                id = "heading_bold",
                title = "粗体标题",
                markdown = "## 标题包含 **粗体**",
                content = { Markdown(markdown = "## 标题包含 **粗体**") }
            ),
            PreviewItem(
                id = "heading_code",
                title = "代码标题",
                markdown = "### 使用 `MarkdownParser` 解析",
                content = { Markdown(markdown = "### 使用 `MarkdownParser` 解析") }
            ),
            PreviewItem(
                id = "heading_mixed",
                title = "混合标题",
                markdown = "## **Kotlin** *Multiplatform* `KMP`",
                content = { Markdown(markdown = "## **Kotlin** *Multiplatform* `KMP`") }
            ),
        )
    ),
    PreviewGroup(
        id = "all_headings",
        title = "所有标题级别",
        description = "一次展示 1-6 级标题",
        items = listOf(
            PreviewItem(
                id = "all_levels",
                title = "完整标题层级",
                markdown = """
# 一级标题
## 二级标题
### 三级标题
#### 四级标题
##### 五级标题
###### 六级标题
                """.trimIndent(),
                content = {
                    Markdown(
                        markdown = """
# 一级标题
## 二级标题
### 三级标题
#### 四级标题
##### 五级标题
###### 六级标题
                        """.trimIndent(),
                    )
                }
            ),
        )
    ),
    PreviewGroup(
        id = "heading_numbering",
        title = "目录自动编号",
        description = "enableHeadingNumbering 自动为标题添加层级编号",
        items = listOf(
            PreviewItem(
                id = "numbering_basic",
                title = "基础标题编号",
                content = {
                    Markdown(
                        markdown = """
# 概述

## 安装

### 环境要求

### 安装步骤

## 使用指南

### 快速开始

### 进阶用法

# 参考
                        """.trimIndent(),
                        enableHeadingNumbering = true,
                    )
                }
            ),
            PreviewItem(
                id = "numbering_comparison",
                title = "编号 vs 无编号对比",
                content = {
                    Markdown(
                        markdown = """
# 第一章

## 第一节

## 第二节

### 小节

# 第二章

## 第一节
                        """.trimIndent(),
                        enableHeadingNumbering = true,
                    )
                }
            ),
            PreviewItem(
                id = "numbering_disabled",
                title = "禁用编号（默认）",
                content = {
                    Markdown(
                        markdown = """
# 概述

## 安装

### 环境要求

## 使用指南
                        """.trimIndent(),
                    )
                }
            ),
        )
    ),
)
