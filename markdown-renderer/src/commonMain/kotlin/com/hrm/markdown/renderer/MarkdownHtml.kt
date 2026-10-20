package com.hrm.markdown.renderer

import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.html.HtmlDirectiveBlockSnapshot
import com.hrm.markdown.parser.html.HtmlDirectiveInlineSnapshot
import com.hrm.markdown.parser.html.HtmlRenderer
import com.hrm.markdown.runtime.DirectiveBlockSnapshot
import com.hrm.markdown.runtime.DirectiveInlineSnapshot
import com.hrm.markdown.runtime.MarkdownDirectivePipeline
import com.hrm.markdown.runtime.MarkdownDirectivePlugin
import com.hrm.markdown.runtime.MarkdownDirectiveRegistry

/**
 * 高层 HTML 导出入口：统一走 runtime transform pipeline，避免绕过插件导致输出不一致。
 */
object MarkdownHtml {
    fun render(
        markdown: String,
        parserConfig: MarkdownParserConfig = MarkdownParserConfig.Default,
        directivePlugins: List<MarkdownDirectivePlugin> = emptyList(),
        softBreak: String? = null,
        escapeHtml: Boolean = false,
        xhtml: Boolean = true,
        softBreakPolicy: MarkdownSoftBreakPolicy = MarkdownSoftBreakPolicy.Space,
    ): String {
        val registry = MarkdownDirectiveRegistry(directivePlugins)
        val normalized = MarkdownDirectivePipeline(registry).transform(markdown).markdown

        val parser = MarkdownParser(
            flavour = parserConfig.flavour,
            customEmojiMap = parserConfig.customEmojiMap,
            enableAsciiEmoticons = parserConfig.enableAsciiEmoticons,
            enableLinting = parserConfig.enableLinting,
        )
        val document = parser.parse(normalized)

        return render(
            document = document,
            directivePlugins = directivePlugins,
            softBreak = softBreak ?: softBreakPolicy.htmlSoftBreak(xhtml),
            escapeHtml = escapeHtml,
            xhtml = xhtml,
        )
    }

    fun render(
        document: Document,
        directivePlugins: List<MarkdownDirectivePlugin> = emptyList(),
        softBreak: String? = null,
        escapeHtml: Boolean = false,
        xhtml: Boolean = true,
        softBreakPolicy: MarkdownSoftBreakPolicy = MarkdownSoftBreakPolicy.Space,
    ): String {
        val registry = MarkdownDirectiveRegistry(directivePlugins)
        val renderer = HtmlRenderer(
            softBreak = softBreak ?: softBreakPolicy.htmlSoftBreak(xhtml),
            escapeHtml = escapeHtml,
            xhtml = xhtml,
            directiveBlockFallback = { snapshot: HtmlDirectiveBlockSnapshot ->
                registry.findHtmlDirectiveFallback(snapshot.tagName)?.render(
                    DirectiveBlockSnapshot(
                        tagName = snapshot.tagName,
                        args = snapshot.args,
                        hasContent = snapshot.hasContent,
                    )
                )
            },
            directiveInlineFallback = { snapshot: HtmlDirectiveInlineSnapshot ->
                registry.findHtmlInlineDirectiveFallback(snapshot.tagName)?.render(
                    DirectiveInlineSnapshot(
                        tagName = snapshot.tagName,
                        args = snapshot.args,
                        alternateText = snapshot.alternateText,
                    )
                )
            }
        )
        return renderer.render(document)
    }
}

private fun MarkdownSoftBreakPolicy.htmlSoftBreak(xhtml: Boolean): String = when (this) {
    MarkdownSoftBreakPolicy.Space -> "\n"
    MarkdownSoftBreakPolicy.LineBreak -> if (xhtml) "<br />\n" else "<br>\n"
}
