package com.hrm.markdown.renderer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.hrm.latex.fonts.KatexFonts
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.hrm.latex.renderer.model.LatexFontRegistry
import com.hrm.latex.renderer.model.systemLatexFontFamilies
import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.renderer.internal.MarkdownEngineHost
import com.hrm.markdown.renderer.internal.RendererFacadeState
import com.hrm.markdown.renderer.internal.layout.model.LayoutInlineBlockModel
import com.hrm.markdown.renderer.internal.layout.model.LayoutWidgetBlockModel
import com.hrm.markdown.runtime.MarkdownDirectiveRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MarkdownLatexConfigTest {
    @Test
    fun should_prefer_registered_fonts_and_preserve_explicit_font_overrides() {
        val previous = LatexFontRegistry.provider
        val registered = systemLatexFontFamilies().copy(main = FontFamily.Serif)
        val explicit = registered.copy(main = FontFamily.Monospace)
        LatexFontRegistry.provider = { registered }
        try {
            runComposeUiTest {
                setContent {
                    val config = LatexConfig()
                    val state = rememberMarkdownLatexState(config)
                    val explicitState = rememberMarkdownLatexState(config.copy(fontFamilies = explicit))
                    val formula = "\\text{WWWWiiii}"
                    val registeredSize = assertNotNull(state.measurer.measure(formula))
                    val explicitSize = assertNotNull(explicitState.measurer.measure(formula))
                    assertSame(config, state.config)
                    assertSame(explicit, explicitState.config.fontFamilies)
                    assertEquals(
                        state.measurer.measure(formula, config.copy(fontFamilies = registered)),
                        registeredSize,
                    )
                    assertNotEquals(registeredSize.widthPx, explicitSize.widthPx)
                }
                waitForIdle()
            }
        } finally {
            LatexFontRegistry.provider = previous
        }
    }

    @Test
    fun should_share_state_within_document_and_isolate_other_markdown_instances() = runComposeUiTest {
        val firstConfig = MarkdownConfig()
        val secondConfig = MarkdownConfig(
            latexConfig = LatexConfig(fontSize = 32.sp, fontFamilies = systemLatexFontFamilies()),
        )
        val document = MarkdownParser().parse("行内 \$x^2\$\n\n\$\$\nx^2\n\$\$")
        var headerState: MarkdownLatexState? = null
        var footerState: MarkdownLatexState? = null
        var otherState: MarkdownLatexState? = null
        var recompose: (() -> Unit)? = null

        setContent {
            var tick by remember { mutableStateOf(0) }
            Column {
                Markdown(
                    document = document,
                    config = firstConfig,
                    enableScroll = false,
                    enableSelection = false,
                    header = {
                        val state = LocalMarkdownLatexState.current
                        // 运行时字体解析不应改写对下游公开的原始配置。
                        assertSame(firstConfig, LocalMarkdownConfig.current)
                        SideEffect { headerState = state }
                    },
                    footer = {
                        val state = LocalMarkdownLatexState.current
                        SideEffect { footerState = state }
                        BasicText(tick.toString())
                    },
                )
                Markdown(
                    document = document,
                    config = secondConfig,
                    enableScroll = false,
                    enableSelection = false,
                    header = {
                        val state = LocalMarkdownLatexState.current
                        SideEffect { otherState = state }
                    },
                )
            }
            recompose = { tick++ }
        }

        waitForIdle()
        val initial = assertNotNull(headerState)
        assertSame(initial, footerState)
        assertNotSame(initial, assertNotNull(otherState))
        assertSame(secondConfig.latexConfig, assertNotNull(otherState).config)
        runOnIdle { assertNotNull(recompose).invoke() }
        waitForIdle()
        assertSame(initial, headerState)
        assertSame(initial, footerState)
    }

    @Test
    fun should_load_installed_katex_fonts_when_no_fonts_are_configured() {
        val previous = LatexFontRegistry.provider
        KatexFonts.install()
        try {
            runComposeUiTest {
                var fontBytes: ByteArray? = null
                setContent {
                    val fonts = assertNotNull(LatexFontRegistry.provider).invoke()
                    val config = LatexConfig()
                    val state = rememberMarkdownLatexState(config)
                    assertSame(config, state.config)
                    assertEquals(
                        state.measurer.measure("x^2", config.copy(fontFamilies = fonts)),
                        assertNotNull(state.measurer.measure("x^2")),
                    )
                    SideEffect { fontBytes = fonts.mainBytes }
                }
                waitUntil { fontBytes != null }
                assertTrue(assertNotNull(fontBytes).isNotEmpty())
            }
        } finally {
            LatexFontRegistry.provider = previous
        }
    }

    @Test
    fun should_use_system_fonts_when_no_provider_is_installed() {
        val previous = LatexFontRegistry.provider
        LatexFontRegistry.provider = null
        try {
            runComposeUiTest {
                setContent {
                    val config = LatexConfig()
                    val state = rememberMarkdownLatexState(config)
                    assertSame(config, state.config)
                    assertEquals(
                        state.measurer.measure("x^2", config.copy(fontFamilies = systemLatexFontFamilies())),
                        assertNotNull(state.measurer.measure("x^2")),
                    )
                }
                waitForIdle()
            }
        } finally {
            LatexFontRegistry.provider = previous
        }
    }

    @Test
    fun should_apply_full_config_to_inline_and_block_layout_after_config_changes() = runComposeUiTest {
        val formula = "\\frac{a+b}{x^2}"
        val fonts = systemLatexFontFamilies().copy(main = FontFamily.Monospace)
        val initialConfig = LatexConfig(
            fontSize = 24.sp,
            theme = LatexTheme.dark(),
            fontFamilies = fonts,
            accessibilityEnabled = true,
            enableLayoutCache = true,
            onHyperlinkClick = {},
        )
        var updateConfig: (() -> Unit)? = null
        var inlineHeight = 0f
        var blockHeight = 0f

        setContent {
            var requestedConfig by remember { mutableStateOf(initialConfig) }
            val latexState = rememberMarkdownLatexState(requestedConfig)
            val latexConfig = latexState.config
            // 显式字体配置必须完整保留，包括回调、主题和所有渲染选项。
            assertSame(requestedConfig, latexConfig)
            val config = MarkdownConfig(latexConfig = requestedConfig)
            val theme = MarkdownTheme.light()
            val density = LocalDensity.current
            val measurer = latexState.measurer
            val textMeasurer = rememberTextMeasurer()
            val host = remember { MarkdownEngineHost() }
            val document = remember { MarkdownParser().parse("\$$formula\$\n\n\$\$\n$formula\n\$\$") }
            val facade = RendererFacadeState(
                theme, config, null, null, null, MarkdownDirectiveRegistry.Empty, false, false,
            )
            val layout = host.layout(
                renderDocument = host.compile(document, facade),
                facadeState = facade,
                viewportWidth = 400f,
                density = density,
                textMeasurer = textMeasurer,
                latexMeasurer = measurer,
                diagramHostRegistry = remember { DiagramHostRegistry() },
            )
            val dimensions = assertNotNull(measurer.measure(formula, latexConfig))
            val inline = (layout.blocks[0] as LayoutInlineBlockModel).inlinePayloads.values.single()
            val block = layout.blocks[1] as LayoutWidgetBlockModel
            val padding = with(density) { theme.codeBlockPadding.toPx() }
            assertEquals(dimensions.widthPx, inline.placeholder.widthPx, 0.01f)
            assertEquals(dimensions.heightPx, inline.placeholder.heightPx, 0.01f)
            assertEquals(dimensions.heightPx + 2 * padding, block.measurement.heightPx, 0.01f)
            SideEffect {
                inlineHeight = inline.placeholder.heightPx
                blockHeight = block.measurement.heightPx
                updateConfig = { requestedConfig = requestedConfig.copy(fontSize = 36.sp) }
            }
        }

        waitForIdle()
        val previousInlineHeight = inlineHeight
        val previousBlockHeight = blockHeight
        runOnIdle { assertNotNull(updateConfig).invoke() }
        waitForIdle()
        assertTrue(inlineHeight > previousInlineHeight)
        assertTrue(blockHeight > previousBlockHeight)
    }
}
