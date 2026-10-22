package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.core.SourceText
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.hrm.markdown.parser.incremental.EditOperation
import com.hrm.markdown.parser.incremental.IncrementalEngine
import com.hrm.markdown.parser.lint.DiagnosticResult
import com.hrm.markdown.parser.lint.LintingPostProcessor
import com.hrm.markdown.parser.log.HLog

/**
 * Markdown 解析器的主入口。
 *
 * 支持三种模式：
 * - **完整解析**：一次性解析整个 Markdown 文本。
 * - **流式解析**：面向 LLM 流式输出场景，支持 append-only 增量解析，
 *   自动修复未关闭的语法结构（围栏代码块、强调、链接等），保障正常展示。
 * - **编辑模式**：支持任意位置的 insert/delete/replace 操作，
 *   利用增量解析引擎精准定位脏区并增量更新 AST。
 *
 * ## 完整解析
 * ```kotlin
 * // 默认使用扩展版（包含所有特性）
 * val parser = MarkdownParser()
 * val document = parser.parse("# Hello\n\nWorld")
 *
 * // 使用特定方言
 * import com.hrm.markdown.parser.flavour.CommonMarkFlavour
 * import com.hrm.markdown.parser.flavour.GFMFlavour
 *
 * val commonMarkParser = MarkdownParser(CommonMarkFlavour)
 * val gfmParser = MarkdownParser(GFMFlavour)
 * ```
 *
 * ## 流式解析（LLM 场景）
 * ```kotlin
 * val parser = MarkdownParser()
 * parser.beginStream()
 * parser.append("# Hello")
 * parser.append(" World\n\n")
 * parser.append("This is **bold")
 * val doc = parser.document // "bold" 会自动补全 **
 * parser.endStream()
 * ```
 *
 * ## 编辑模式
 * ```kotlin
 * val parser = MarkdownParser()
 * parser.parse("# Hello\n\nWorld")
 * parser.insert(offset = 13, text = " of Markdown")
 * val doc = parser.document // 增量更新后的 AST
 * ```
 *
 * ## 语法验证（Linting）
 * ```kotlin
 * val parser = MarkdownParser(MarkdownParserConfig(enableLinting = true))
 * val doc = parser.parse("# Title\n\n### Skipped h2\n\n[^missing]")
 * val diagnostics = parser.diagnostics
 * println(diagnostics) // 输出标题层级跳跃和无效脚注引用的诊断
 * ```
 *
 * @param config 完整解析、流式追加与编辑共享的不可变配置。
 */
class MarkdownParser(
    val config: MarkdownParserConfig = MarkdownParserConfig.Default,
) {
    constructor(flavour: MarkdownFlavour) : this(MarkdownParserConfig(flavour = flavour))

    private val engine = IncrementalEngine(
        flavour = config.flavour,
        customEmojiMap = config.customEmojiMap,
        enableAsciiEmoticons = config.enableAsciiEmoticons,
        lintingProcessor = if (config.enableLinting) LintingPostProcessor() else null,
        appendCoalesceThreshold = config.appendCoalesceThreshold,
    )
    /**
     * 语法验证诊断结果。
     *
     * 配置 `enableLinting = true` 时自动检测语法问题。
     * 每次 [parse]、[endStream] 或编辑操作后自动更新。
     * 与当前 [document] 保持一致；[beginStream] 清空文档时也清空可见诊断。
     */
    val diagnostics: DiagnosticResult
        get() = document.diagnostics

    /**
     * 当前文档 AST。每次解析/编辑后更新。
     */
    val document: Document
        get() = engine.document

    /**
     * 当前源文本。每次解析/编辑后更新。
     */
    val sourceText: SourceText
        get() = engine.sourceText

    /**
     * 当前是否处于流式接收中。
     */
    val isStreaming: Boolean get() = engine.isStreaming

    /**
     * 解析完整的 Markdown 输入文本。
     * 返回 AST 的根 Document 节点。
     */
    fun parse(input: String): Document {
        HLog.i(TAG) { "parse input=${input.length} chars" }
        return engine.fullParse(input)
    }

    // ────── 流式解析 API ──────

    /**
     * 开始一次新的流式会话。清空之前的状态。
     */
    fun beginStream() {
        HLog.i(TAG, "beginStream")
        engine.beginStream()
    }

    /**
     * 追加一段文本（通常是 LLM 的一个 token 或一个 chunk）。
     * 默认逐次提交增量解析并修复未关闭的语法；启用合并阈值后，
     * 遇到换行或累计达到阈值才提交，结束或取消时刷新剩余缓冲。
     *
     * @return 最近一次已提交的文档；仍在缓冲时返回上次文档。
     */
    fun append(chunk: String): Document {
        return engine.append(chunk)
    }

    /**
     * 流结束。执行最终解析（不再做未完成块修复）。
     *
     * @return 最终的 Document
     */
    fun endStream(): Document {
        HLog.i(TAG, "endStream")
        return engine.endStream()
    }

    /**
     * 中断流（用户取消等）。以当前状态做最终快照。
     *
     * @return 当前状态的 Document
     */
    fun abort(): Document {
        return engine.abort()
    }

    /**
     * 获取当前完整文本。
     */
    fun currentText(): String {
        return engine.currentText()
    }

    // ────── 编辑 API（新增） ──────

    /**
     * 在指定偏移量处插入文本。
     * 利用增量解析引擎，只重解析受影响的区域。
     *
     * @param offset 插入位置的字符偏移量
     * @param text 要插入的文本
     * @return 更新后的 Document
     */
    fun insert(offset: Int, text: String): Document {
        return engine.applyEdit(EditOperation.Insert(offset, text))
    }

    /**
     * 从指定偏移量开始删除指定长度的文本。
     *
     * @param offset 删除起始位置的字符偏移量
     * @param length 要删除的字符数
     * @return 更新后的 Document
     */
    fun delete(offset: Int, length: Int): Document {
        return engine.applyEdit(EditOperation.Delete(offset, length))
    }

    /**
     * 替换指定范围的文本。
     *
     * @param offset 替换起始位置的字符偏移量
     * @param length 要替换的原文长度
     * @param newText 新的文本内容
     * @return 更新后的 Document
     */
    fun replace(offset: Int, length: Int, newText: String): Document {
        return engine.applyEdit(EditOperation.Replace(offset, length, newText))
    }

    /**
     * 应用通用编辑操作。
     *
     * @param edit 编辑操作
     * @return 更新后的 Document
     */
    fun applyEdit(edit: EditOperation): Document {
        return engine.applyEdit(edit)
    }

    companion object {
        private const val TAG = "MarkdownParser"

        /**
         * 便捷方法：将 Markdown 输入解析为 Document AST。
         *
         * @param input Markdown 文本
         * @param config 不可变解析配置
         */
        fun parseToDocument(input: String, config: MarkdownParserConfig = MarkdownParserConfig.Default): Document {
            return MarkdownParser(config).parse(input)
        }
    }
}
