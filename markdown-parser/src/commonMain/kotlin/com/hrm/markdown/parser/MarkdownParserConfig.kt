package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.immutableMapCopy
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour

/**
 * 完整解析、流式追加、编辑与渲染入口共享的不可变解析配置。
 *
 * 构造时复制并封存 Emoji 别名。复用配置或通过 [copy] 修改其他设置时，
 * 继续共享同一个不可变映射。
 */
class MarkdownParserConfig(
    val flavour: MarkdownFlavour = ExtendedFlavour,
    customEmojiMap: Map<String, String> = emptyMap(),
    val enableAsciiEmoticons: Boolean = false,
    val enableLinting: Boolean = false,
    /** 0 表示逐次提交；大于 0 时，遇到换行或累计达到该字符数才提交缓冲内容。 */
    val appendCoalesceThreshold: Int = 0,
) {
    val customEmojiMap: Map<String, String> = immutableMapCopy(customEmojiMap)

    init {
        require(appendCoalesceThreshold >= 0) { "appendCoalesceThreshold must be non-negative" }
    }

    private val valueHashCode = run {
        var result = flavour.hashCode()
        result = 31 * result + this.customEmojiMap.hashCode()
        result = 31 * result + enableAsciiEmoticons.hashCode()
        result = 31 * result + enableLinting.hashCode()
        31 * result + appendCoalesceThreshold
    }

    /** 使用当前配置创建状态独立的解析器。 */
    fun newParser(): MarkdownParser = MarkdownParser(this)

    fun copy(
        flavour: MarkdownFlavour = this.flavour,
        customEmojiMap: Map<String, String> = this.customEmojiMap,
        enableAsciiEmoticons: Boolean = this.enableAsciiEmoticons,
        enableLinting: Boolean = this.enableLinting,
        appendCoalesceThreshold: Int = this.appendCoalesceThreshold,
    ): MarkdownParserConfig {
        if (flavour === this.flavour && customEmojiMap === this.customEmojiMap &&
            enableAsciiEmoticons == this.enableAsciiEmoticons && enableLinting == this.enableLinting &&
            appendCoalesceThreshold == this.appendCoalesceThreshold
        ) return this
        return MarkdownParserConfig(
            flavour, customEmojiMap, enableAsciiEmoticons, enableLinting, appendCoalesceThreshold,
        )
    }

    override fun equals(other: Any?): Boolean = this === other || other is MarkdownParserConfig &&
        flavour === other.flavour && customEmojiMap == other.customEmojiMap &&
        enableAsciiEmoticons == other.enableAsciiEmoticons && enableLinting == other.enableLinting &&
        appendCoalesceThreshold == other.appendCoalesceThreshold

    override fun hashCode(): Int = valueHashCode

    override fun toString(): String = "MarkdownParserConfig(flavour=$flavour, customEmojiMap=$customEmojiMap, " +
        "enableAsciiEmoticons=$enableAsciiEmoticons, enableLinting=$enableLinting, " +
        "appendCoalesceThreshold=$appendCoalesceThreshold)"

    companion object {
        val Default = MarkdownParserConfig()
        val LlmStreaming = MarkdownParserConfig(appendCoalesceThreshold = 16)
    }
}
