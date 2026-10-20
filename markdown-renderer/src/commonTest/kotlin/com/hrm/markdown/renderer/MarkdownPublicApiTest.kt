package com.hrm.markdown.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.codehigh.renderer.CodeBlock
import com.hrm.codehigh.renderer.CodeColors
import com.hrm.codehigh.renderer.CodeDefaults
import com.hrm.codehigh.renderer.CodeLayout
import com.hrm.codehigh.renderer.CodeTypography
import com.hrm.codehigh.renderer.ProvideCodeDefaults
import com.hrm.codehigh.renderer.measureCodeBlockSize
import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.FencedCodeBlock
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class MarkdownPublicApiTest {
    @Test
    fun should_dispatch_native_code_token_clicks_to_the_latest_handler() = runComposeUiTest {
        var prefix by mutableStateOf("initial")
        val clicks = mutableListOf<String>()
        setContent {
            val label = prefix
            CodeBlock(code = "val sample = 42", language = "kotlin", onTokenClick = { clicks += "$label:${it.text}" })
        }
        onNodeWithText("val sample = 42").performTouchInput { click(Offset(1f, center.y)) }
        runOnIdle { prefix = "updated" }
        onNodeWithText("val sample = 42").performTouchInput { click(Offset(1f, center.y)) }
        assertEquals(listOf("initial:val", "updated:val"), clicks)
    }

    @Test
    fun should_keep_zero_visible_lines_supported_and_allow_expand_then_collapse() = runComposeUiTest {
        setContent {
            CodeBlock(code = "first line\nsecond line", maxVisibleLines = 0, showCopyButton = false)
        }
        onNodeWithText("first line").assertDoesNotExist()
        onNode(hasText("▼", substring = true)).performClick()
        onNodeWithText("first line").assertExists()
        onNodeWithText("second line").assertExists()
        onNode(hasText("▲", substring = true)).performClick()
        onNodeWithText("first line").assertDoesNotExist()
    }

    @Test
    fun should_inherit_native_defaults_with_local_overrides_and_keep_original_base_palette() = runComposeUiTest {
        var base: CodeColors? = null
        var inherited: CodeColors? = null
        var overridden: CodeColors? = null
        val supplied = CodeDefaults.solarizedLightColors()
        val typography = CodeTypography(text = TextStyle(fontSize = 21.sp))
        val layout = CodeLayout(contentPadding = PaddingValues(18.dp))
        var actualTypography: CodeTypography? = null
        var actualLayout: CodeLayout? = null
        setContent {
            val baseColors = CodeDefaults.colors()
            SideEffect { base = baseColors }
            ProvideCodeDefaults(colors = supplied, typography = typography, layout = layout) {
                val native = CodeDefaults.colors()
                val local = CodeDefaults.colors(keyword = Color.Magenta)
                val nativeTypography = CodeDefaults.typography()
                val nativeLayout = CodeDefaults.layout()
                SideEffect {
                    inherited = native; overridden = local
                    actualTypography = nativeTypography; actualLayout = nativeLayout
                }
            }
        }
        waitForIdle()
        assertEquals(CodeDefaults.oneDarkProColors(), base)
        assertEquals(supplied, inherited)
        assertEquals(Color.Magenta, assertNotNull(overridden).keyword)
        assertEquals(supplied.background, assertNotNull(overridden).background)
        assertEquals(typography, actualTypography)
        assertEquals(layout, actualLayout)
    }

    @Test
    fun should_measure_and_paint_custom_code_styles_with_the_same_geometry() = runComposeUiTest {
        val document = MarkdownParser().parse("before-api\n\n```kotlin {title=\"api.kt\"}\nval api = 42\n```\n\nafter-api\n\n`inline-api`")
        val code = document.children.filterIsInstance<FencedCodeBlock>().single()
        var large by mutableStateOf(false)
        var expectedGap = 0f
        setContent {
            val typography = CodeTypography(
                text = TextStyle(fontSize = if (large) 28.sp else 14.sp, lineHeight = if (large) 44.sp else 22.sp),
                inlineCode = TextStyle(fontSize = if (large) 26.sp else 13.sp, lineHeight = if (large) 40.sp else 20.sp),
                title = TextStyle(fontSize = if (large) 24.sp else 12.sp),
            )
            val geometry = CodeLayout(contentPadding = PaddingValues(if (large) 20.dp else 8.dp))
            val resolved = ResolvedMarkdownStyle(codeTypography = typography, codeLayout = geometry, codeColors = CodeDefaults.lightColors())
            val size = measureCodeBlockSize(code.literal, code.language, title = code.attributes.pairs["title"].orEmpty(), typography = resolved.codeTypography, layout = resolved.codeLayout,
                density = LocalDensity.current, textMeasurer = rememberTextMeasurer())
            val spacing = with(LocalDensity.current) { resolved.layout.blockSpacing.toPx() * 2 }
            SideEffect { expectedGap = size.height + spacing }
            Box(Modifier.size(500.dp, 1000.dp)) {
                Markdown(document = document, codeTypography = typography, codeLayout = geometry,
                    codeColors = CodeDefaults.lightColors(), enableScroll = false, enableSelection = false)
            }
        }
        waitForIdle()
        fun gap(): Float = onNodeWithText("after-api").fetchSemanticsNode().boundsInRoot.top -
            onNodeWithText("before-api").fetchSemanticsNode().boundsInRoot.bottom
        val initialInline = onNodeWithText("inline-api").fetchSemanticsNode().boundsInRoot.height
        val initialCode = onNodeWithText("val api = 42").fetchSemanticsNode().boundsInRoot.height
        assertTrue(abs(gap() - expectedGap) <= 2f, "expected $expectedGap, actual ${gap()}")
        runOnIdle { large = true }
        waitForIdle()
        assertTrue(abs(gap() - expectedGap) <= 2f, "expected $expectedGap, actual ${gap()}")
        assertTrue(onNodeWithText("inline-api").fetchSemanticsNode().boundsInRoot.height > initialInline * 1.5f)
        assertTrue(onNodeWithText("val api = 42").fetchSemanticsNode().boundsInRoot.height > initialCode * 1.5f)
    }

    @Test
    fun should_use_provider_defaults_and_keep_instance_overrides_local() = runComposeUiTest {
        var actual: ResolvedMarkdownStyle? = null
        var inherited: MarkdownColors? = null
        val codeColors = CodeDefaults.draculaProColors()
        val codeTypography = CodeTypography(text = TextStyle(fontSize = 25.sp))
        val codeLayout = CodeLayout(contentPadding = PaddingValues(18.dp))
        val localCodeLayout = codeLayout.copy(contentPadding = PaddingValues(9.dp))
        var inheritedCodeLayout: CodeLayout? = null
        setContent {
            ProvideMarkdownDefaults(
                colors = MarkdownDefaults.darkColors(),
                typography = MarkdownTypography(text = TextStyle(fontSize = 23.sp)),
                codeColors = codeColors,
                codeTypography = codeTypography,
                codeLayout = codeLayout,
            ) {
                val colors = MarkdownDefaults.colors(link = Color.Magenta)
                val inheritedLayout = MarkdownDefaults.codeLayout()
                SideEffect { inherited = colors; inheritedCodeLayout = inheritedLayout }
                Markdown(document = MarkdownParser().parse("instance"), colors = colors, codeLayout = localCodeLayout,
                    typography = MarkdownTypography(text = TextStyle(color = Color.Green, fontSize = 19.sp)),
                    enableScroll = false, components = MarkdownComponents(header = {
                        val style = LocalMarkdownStyle.current
                        SideEffect { actual = style }
                    }))
            }
        }
        waitForIdle()
        assertEquals(MarkdownDefaults.darkColors().text, assertNotNull(inherited).text)
        assertEquals(Color.Magenta, assertNotNull(actual).colors.link)
        assertEquals(Color.Green, assertNotNull(actual).bodyStyle.color)
        assertEquals(19.sp, assertNotNull(actual).bodyStyle.fontSize)
        assertEquals(codeColors, assertNotNull(actual).codeColors)
        assertEquals(codeTypography, assertNotNull(actual).codeTypography)
        assertEquals(codeLayout, inheritedCodeLayout)
        assertEquals(localCodeLayout, assertNotNull(actual).codeLayout)
    }

    @Test
    fun should_scroll_the_actual_virtual_list_and_exclude_header_from_block_indexes() = runComposeUiTest {
        val document = MarkdownParser().parse((0..39).joinToString("\n\n") { "block-$it" })
        var viewState: MarkdownState? = null
        var scope: CoroutineScope? = null
        var scrollEnabled by mutableStateOf(true)
        var result: Boolean? = null
        setContent {
            val state = rememberMarkdownState()
            val coroutineScope = rememberCoroutineScope()
            SideEffect { viewState = state; scope = coroutineScope }
            Box(Modifier.size(300.dp, 180.dp)) {
                Markdown(document = document, state = state, enableScroll = scrollEnabled,
                    components = MarkdownComponents(header = { Box(Modifier.height(80.dp)) { BasicText("header") } }))
            }
        }
        waitForIdle()
        runOnIdle { assertNotNull(scope).launch { result = assertNotNull(viewState).scrollToBlock(15) } }
        waitForIdle()
        assertEquals(true, result)
        assertEquals(15, assertNotNull(viewState).firstVisibleBlockIndex)
        runOnIdle { assertNotNull(scope).launch { result = assertNotNull(viewState).scrollToEnd() } }
        waitForIdle()
        assertFalse(assertNotNull(viewState).canScrollForward)
        runOnIdle { scrollEnabled = false }
        waitForIdle()
        runOnIdle { assertNotNull(scope).launch { result = assertNotNull(viewState).scrollToBlock(5) } }
        waitForIdle()
        assertEquals(false, result)
    }
}
