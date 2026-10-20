package com.hrm.markdown.preview

import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.GFMFlavour
import com.hrm.markdown.renderer.Markdown
import com.hrm.markdown.renderer.MarkdownParserConfig

/**
 * Flavour 配置预览 — 展示不同方言对同一 Markdown 文本的解析差异。
 */
internal val flavourConfigPreviewGroups = listOf(
    PreviewGroup(
        id = "flavour_comparison",
        title = "方言对比",
        description = "同一段 Markdown 在不同 Flavour 下的渲染差异",
        items = listOf(
            PreviewItem(
                id = "extended_flavour",
                title = "ExtendedFlavour（默认）",
                content = {
                    Markdown(
                        markdown = flavourComparisonMarkdown,
                    )
                }
            ),
            PreviewItem(
                id = "gfm_flavour",
                title = "GFMFlavour",
                content = {
                    Markdown(
                        markdown = flavourComparisonMarkdown,
                        parserConfig = MarkdownParserConfig(flavour = GFMFlavour),
                    )
                }
            ),
            PreviewItem(
                id = "commonmark_flavour",
                title = "CommonMarkFlavour",
                content = {
                    Markdown(
                        markdown = flavourComparisonMarkdown,
                        parserConfig = MarkdownParserConfig(flavour = CommonMarkFlavour),
                    )
                }
            ),
        )
    ),
    PreviewGroup(
        id = "extended_only",
        title = "Extended 专属语法",
        description = "仅 ExtendedFlavour 支持的语法（数学、高亮、自定义容器等）",
        items = listOf(
            PreviewItem(
                id = "math_extended_vs_gfm",
                title = "数学公式（Extended vs GFM）",
                content = {
                    Markdown(
                        markdown = mathMarkdown,
                    )
                }
            ),
            PreviewItem(
                id = "math_gfm",
                title = "数学公式（GFM 不支持）",
                content = {
                    Markdown(
                        markdown = mathMarkdown,
                        parserConfig = MarkdownParserConfig(flavour = GFMFlavour),
                    )
                }
            ),
        )
    ),
    PreviewGroup(
        id = "custom_emoji",
        title = "自定义 Emoji",
        description = "通过 MarkdownParserConfig 配置自定义 Emoji 别名",
        items = listOf(
            PreviewItem(
                id = "custom_emoji_map",
                title = "自定义 Emoji 映射",
                content = {
                    Markdown(
                        markdown = """
                            自定义 Emoji 测试 :rocket: :star: :custom:
                        """.trimIndent(),
                        parserConfig = MarkdownParserConfig(customEmojiMap = mapOf(
                                "custom" to "\uD83C\uDF89",
                            )),
                    )
                }
            ),
        )
    ),
)

private val flavourComparisonMarkdown = """
# 方言对比测试

## 表格（GFM/Extended 支持，CommonMark 不支持）

| 功能 | CommonMark | GFM | Extended |
|------|:----------:|:---:|:--------:|
| 表格 | - | ✓ | ✓ |
| 删除线 | - | ✓ | ✓ |
| 高亮 | - | - | ✓ |

## 删除线（GFM/Extended 支持）

~~这是删除线文本~~

## 高亮（仅 Extended 支持）

==这是高亮文本==

## 标准语法（所有方言都支持）

**粗体** *斜体* `代码`

- 列表项 1
- 列表项 2

> 引用块
""".trimIndent()

private val mathMarkdown = """
## 数学公式

行内公式：${'$'}E = mc^2${'$'}

块级公式：

${'$'}${'$'}
\int_{-\infty}^{\infty} e^{-x^2} dx = \sqrt{\pi}
${'$'}${'$'}
""".trimIndent()
