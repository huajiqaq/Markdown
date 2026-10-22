package com.hrm.markdown.preview

import com.hrm.markdown.parser.MarkdownParserConfig

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.renderer.Markdown

/**
 * 语法验证/Linting 和中文本地化优化预览。
 */
internal val lintingPreviewGroups = listOf(
    PreviewGroup(
        id = "linting_heading",
        title = "标题层级检测",
        description = "检测标题层级跳跃",
        items = listOf(
            PreviewItem(
                id = "heading_skip",
                title = "标题层级跳跃",
                content = {
                    LintingDemo(
                        markdown = """
                            # 一级标题
                            
                            ### 三级标题（跳过了二级）
                            
                            ###### 六级标题（跳过了四五级）
                        """.trimIndent(),
                        description = "解析器检测到标题从 h1 跳到 h3，以及从 h3 跳到 h6"
                    )
                }
            ),
            PreviewItem(
                id = "heading_ok",
                title = "标题层级正常",
                content = {
                    LintingDemo(
                        markdown = """
                            # 一级标题
                            
                            ## 二级标题
                            
                            ### 三级标题
                        """.trimIndent(),
                        description = "标题层级连续，无警告"
                    )
                }
            ),
        )
    ),
    PreviewGroup(
        id = "linting_footnote",
        title = "脚注检测",
        description = "检测无效脚注引用和未使用的脚注定义",
        items = listOf(
            PreviewItem(
                id = "invalid_footnote",
                title = "无效脚注引用",
                content = {
                    LintingDemo(
                        markdown = """
                            这段文本引用了一个不存在的脚注[^missing]。
                        """.trimIndent(),
                        description = "脚注 [^missing] 没有对应定义，报告 ERROR"
                    )
                }
            ),
            PreviewItem(
                id = "unused_footnote",
                title = "未使用的脚注定义",
                content = {
                    LintingDemo(
                        markdown = """
                            这段文本没有引用任何脚注。
                            
                            [^unused]: 这个脚注定义从未被使用。
                        """.trimIndent(),
                        description = "脚注 [^unused] 已定义但未被引用，报告 WARNING"
                    )
                }
            ),
        )
    ),
    PreviewGroup(
        id = "linting_duplicate_id",
        title = "重复标题 ID",
        description = "检测多个标题生成相同 slug",
        items = listOf(
            PreviewItem(
                id = "duplicate_heading",
                title = "重复标题",
                content = {
                    LintingDemo(
                        markdown = """
                            # Hello
                            
                            正文内容...
                            
                            ## Hello
                            
                            更多内容...
                        """.trimIndent(),
                        description = "两个标题生成相同 slug 'hello'，第二个报告 WARNING"
                    )
                }
            ),
        )
    ),
)

internal val cjkPreviewGroups = listOf(
    PreviewGroup(
        id = "cjk_emphasis",
        title = "CJK 强调识别",
        description = "全角标点和中文场景下的强调解析",
        items = listOf(
            PreviewItem(
                id = "cjk_fullwidth_period",
                title = "全角句号后的强调",
                content = {
                    Markdown(markdown = "*中文斜体*。这是全角句号后的文本。")
                }
            ),
            PreviewItem(
                id = "cjk_fullwidth_comma",
                title = "全角逗号后的强调",
                content = {
                    Markdown(markdown = "**粗体**，继续后续内容。")
                }
            ),
            PreviewItem(
                id = "cjk_fullwidth_brackets",
                title = "全角括号中的强调",
                content = {
                    Markdown(markdown = "说：*这是强调*！以及（*括号内的强调*）。")
                }
            ),
            PreviewItem(
                id = "cjk_quotes_adjacent",
                title = "引号/标点紧邻中文的强调",
                content = {
                    Markdown(
                        markdown = """
                            不需要额外插入空格，强调/粗体应正常闭合：
                            
                            输入（源码）：
                            
                            ````md
                            **'确实'**厉害啊
                            **"渴望"**和**"行动力"**的能量
                            厉害啊**'确实'**
                            *'确实'*厉害啊
                            **‘确实’**厉害啊
                            ````
                            
                            渲染效果：
                            
                            1. **'确实'**厉害啊
                            2. **"渴望"**和**"行动力"**的能量
                            3. 厉害啊**'确实'**
                            4. *'确实'*厉害啊
                            5. **‘确实’**厉害啊
                        """.trimIndent()
                    )
                }
            ),
            PreviewItem(
                id = "cjk_book_title",
                title = "书名号中的粗体",
                content = {
                    Markdown(markdown = "推荐阅读《**Kotlin 实战**》这本书。")
                }
            ),
            PreviewItem(
                id = "cjk_mixed",
                title = "中英文混合",
                content = {
                    Markdown(markdown = "这是一段包含 *English emphasis* 和 **中文粗体** 的混合文本。")
                }
            ),
        )
    ),
    PreviewGroup(
        id = "cjk_underscore",
        title = "CJK 下划线规则",
        description = "下划线在 CJK 上下文中的词内规则",
        items = listOf(
            PreviewItem(
                id = "cjk_underscore_with_space",
                title = "空格分隔的下划线强调",
                content = {
                    Markdown(markdown = "这是 _下划线斜体_ 文本，使用空格分隔。")
                }
            ),
            PreviewItem(
                id = "cjk_underscore_no_space",
                title = "无空格的下划线（不解析为强调）",
                content = {
                    Markdown(markdown = "文件名：我_的_文档 — 下划线不会被解析为斜体。")
                }
            ),
        )
    ),
    PreviewGroup(
        id = "ruby_text",
        title = "Ruby 注音",
        description = "{漢字|かんじ} 中日文注音标注",
        items = listOf(
            PreviewItem(
                id = "ruby_japanese",
                title = "日文注音",
                content = {
                    Markdown(
                        markdown = "{漢字|かんじ}の{読|よ}み{方|かた}を{学|まな}ぶ。"
                    )
                }
            ),
            PreviewItem(
                id = "ruby_chinese",
                title = "中文拼音",
                content = {
                    Markdown(
                        markdown = "{中文|zhōngwén}是一种{美丽|měilì}的{语言|yǔyán}。"
                    )
                }
            ),
            PreviewItem(
                id = "ruby_mixed",
                title = "注音与普通文本混排",
                content = {
                    Markdown(
                        markdown = """
在学习日语时，{漢字|かんじ}的读法非常重要。

例如：**{東京|とうきょう}** 是日本的首都，{大阪|おおさか}则是第二大城市。
                        """.trimIndent()
                    )
                }
            ),
        )
    ),
)

@Composable
private fun LintingDemo(markdown: String, description: String) {
    val parser = remember(markdown) {
        MarkdownParser(MarkdownParserConfig(enableLinting = true)).also { it.parse(markdown) }
    }
    val diagnostics = remember(parser) { parser.diagnostics }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 渲染 Markdown
        Markdown(markdown = markdown)

        Spacer(modifier = Modifier.height(12.dp))

        // 显示诊断结果
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "诊断结果",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (diagnostics.hasIssues) {
                    for (d in diagnostics.diagnostics) {
                        Text(
                            text = "[${d.severity}] 第${d.line + 1}行: ${d.message}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = when (d.severity) {
                                com.hrm.markdown.parser.lint.DiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
                                com.hrm.markdown.parser.lint.DiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                } else {
                    Text(
                        text = "No issues found.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
