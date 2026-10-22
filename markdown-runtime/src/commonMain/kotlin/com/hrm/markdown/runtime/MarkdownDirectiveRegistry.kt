package com.hrm.markdown.runtime

import com.hrm.markdown.parser.log.HLog

private const val TAG = "MarkdownDirectiveRegistry"

class MarkdownDirectiveRegistry(
    directivePlugins: List<MarkdownDirectivePlugin>,
) {
    val directivePlugins: List<MarkdownDirectivePlugin> = directivePlugins
        .sortedWith(compareBy<MarkdownDirectivePlugin> { it.priority }.thenBy { it.id })
        .toList()

    private val blockRenderers = LinkedHashMap<String, MarkdownBlockDirectiveRenderer>()
    private val inlineRenderers = LinkedHashMap<String, MarkdownInlineDirectiveRenderer>()
    private val htmlFallbacks = LinkedHashMap<String, HtmlDirectiveFallback>()
    private val htmlInlineFallbacks = LinkedHashMap<String, HtmlInlineDirectiveFallback>()
    /** 输入处理身份不包含渲染回调和 HTML 降级输出。 */
    val inputPipeline: MarkdownDirectivePipeline

    val streamingSupport: MarkdownTransformerStreamingSupport get() = inputPipeline.streamingSupport
    val supportsStreaming: Boolean get() = inputPipeline.supportsStreaming
    val supportsStreamingFastPath: Boolean get() = inputPipeline.supportsStreamingFastPath
    val hasTransformers: Boolean get() = inputPipeline.hasTransformers

    init {
        val transformers = mutableListOf<MarkdownInputTransformer>()
        for (plugin in this.directivePlugins) {
            transformers += plugin.inputTransformers
            for ((tagName, renderer) in plugin.blockDirectiveRenderers) {
                if (blockRenderers.containsKey(tagName)) {
                    HLog.w(TAG) { "block directive renderer overridden: tag=$tagName, plugin=${plugin.id}" }
                }
                blockRenderers[tagName] = renderer
            }
            for ((tagName, renderer) in plugin.inlineDirectiveRenderers) {
                if (inlineRenderers.containsKey(tagName)) {
                    HLog.w(TAG) { "inline directive renderer overridden: tag=$tagName, plugin=${plugin.id}" }
                }
                inlineRenderers[tagName] = renderer
            }
            for ((tagName, fallback) in plugin.htmlDirectiveFallbacks) {
                if (htmlFallbacks.containsKey(tagName)) {
                    HLog.w(TAG) { "html directive fallback overridden: tag=$tagName, plugin=${plugin.id}" }
                }
                htmlFallbacks[tagName] = fallback
            }
            for ((tagName, fallback) in plugin.htmlInlineDirectiveFallbacks) {
                if (htmlInlineFallbacks.containsKey(tagName)) {
                    HLog.w(TAG) { "html inline directive fallback overridden: tag=$tagName, plugin=${plugin.id}" }
                }
                htmlInlineFallbacks[tagName] = fallback
            }
        }
        inputPipeline = if (transformers.isEmpty()) MarkdownDirectivePipeline.Empty else MarkdownDirectivePipeline(transformers)
    }

    fun findBlockDirectiveRenderer(tagName: String): MarkdownBlockDirectiveRenderer? = blockRenderers[tagName]

    fun findInlineDirectiveRenderer(tagName: String): MarkdownInlineDirectiveRenderer? = inlineRenderers[tagName]

    fun findHtmlDirectiveFallback(tagName: String): HtmlDirectiveFallback? = htmlFallbacks[tagName]

    fun findHtmlInlineDirectiveFallback(tagName: String): HtmlInlineDirectiveFallback? = htmlInlineFallbacks[tagName]

    companion object {
        val Empty = MarkdownDirectiveRegistry(emptyList())
    }
}
