package com.hrm.markdown.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.hrm.latex.fonts.katexFontFamilies
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig

/** 配置和测量器一起创建，供文档布局、行内内容与公式块共用。 */
internal class MarkdownLatexState(
    val config: LatexConfig,
    val measurer: LatexMeasurerState,
)

internal val LocalMarkdownLatexState = compositionLocalOf<MarkdownLatexState> {
    error("MarkdownLatexState must be provided by MarkdownDocumentRenderer")
}

/** 仅补齐缺省字体，不改写调用方配置；字体字节加载完成后自动刷新渲染状态。 */
@Composable
internal fun rememberMarkdownLatexState(config: LatexConfig): MarkdownLatexState {
    val fonts = config.fontFamilies ?: katexFontFamilies()
    val resolvedConfig = remember(config, fonts) {
        if (config.fontFamilies != null) config else config.copy(fontFamilies = fonts)
    }
    val measurer = rememberLatexMeasurer(resolvedConfig)
    return remember(resolvedConfig, measurer) { MarkdownLatexState(resolvedConfig, measurer) }
}
