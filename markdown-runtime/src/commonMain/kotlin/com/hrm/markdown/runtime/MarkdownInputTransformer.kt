package com.hrm.markdown.runtime

/**
 * Markdown 输入转换器。
 *
 * 用于把外部自定义语法转换成官方 directive 语法，避免污染 parser。
 * 行为不变时复用同一实例；行为改变时提供新实例。转换链按实例和声明的流式契约识别输入处理配置，
 * 不受指令渲染回调替换影响。
 */
interface MarkdownInputTransformer {
    val id: String

    /**
     * Declares how this transformer participates in streaming.
     *
     * The default [MarkdownTransformerStreamingSupport.RestartOnRewrite] keeps streaming enabled.
     * Every transformed revision is checked against the previous transformed prefix; the parser
     * session is restarted atomically if existing output was rewritten.
     *
     * Use [MarkdownTransformerStreamingSupport.AppendSafe] only for the stronger guarantee that a
     * longer append-only input always preserves the complete previous transformed prefix. Use
     * [MarkdownTransformerStreamingSupport.Unsupported] to explicitly require isolated full parses.
     */
    val streamingSupport: MarkdownTransformerStreamingSupport
        get() = MarkdownTransformerStreamingSupport.RestartOnRewrite

    fun transform(input: String): MarkdownTransformResult
}

enum class MarkdownTransformerStreamingSupport {
    /** Always use an isolated full parse for each transformed revision. */
    Unsupported,

    /** Stream by default and restart the parser session whenever transformed output rewrites. */
    RestartOnRewrite,

    /** The transformed output is guaranteed to preserve every previous append-only prefix. */
    AppendSafe,
}
