package com.hrm.markdown.renderer

import androidx.compose.runtime.Immutable
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour

/** Source parsing only. Display, callbacks, and native renderer settings never restart the parser. */
@Immutable
data class MarkdownParserConfig(
    val flavour: MarkdownFlavour = ExtendedFlavour,
    val customEmojiMap: Map<String, String> = emptyMap(),
    val enableAsciiEmoticons: Boolean = false,
    val enableLinting: Boolean = false,
    val appendCoalesceThreshold: Int = 0,
) {
    init { require(appendCoalesceThreshold >= 0) { "appendCoalesceThreshold must be non-negative" } }
    companion object {
        val Default = MarkdownParserConfig()
        val LlmStreaming = MarkdownParserConfig(appendCoalesceThreshold = 16)
    }
}
