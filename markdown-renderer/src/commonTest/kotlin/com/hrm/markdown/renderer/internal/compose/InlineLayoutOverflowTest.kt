package com.hrm.markdown.renderer.internal.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hrm.markdown.renderer.inline.InlinePlaceholderId
import com.hrm.markdown.renderer.inline.inlineWidgetPaintPayload
import com.hrm.markdown.renderer.internal.core.identity.RenderIdentity
import com.hrm.markdown.renderer.internal.core.model.DirectiveInlineWidgetModel
import com.hrm.markdown.renderer.internal.layout.inline.InlineFlowInput
import com.hrm.markdown.renderer.internal.layout.inline.InlineFlowSegment
import com.hrm.markdown.renderer.internal.layout.inline.InlinePlaceholderLayoutSpec
import com.hrm.markdown.renderer.internal.layout.inline.buildInlineLayoutBlockModel
import com.hrm.markdown.renderer.internal.layout.inline.computeInlineFlowLayout
import com.hrm.markdown.renderer.internal.layout.model.LayoutRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class InlineLayoutOverflowTest {
    @Test
    fun should_measureAndScrollNaturalWidth_when_payloadFillsItsSlot() = runComposeUiTest {
        var contentSize = IntSize.Zero
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                OverflowFixture {
                    Box(Modifier.fillMaxSize().onSizeChanged { contentSize = it })
                }
            }
        }
        waitForIdle()
        assertEquals(IntSize(900, 40), contentSize)
        val scroller = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        val range = scroller.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(600f, range.maxValue())

        scroller.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(600f, 0f) }
        waitForIdle()
        assertEquals(600f, range.value(), "The entire payload must be reachable")
    }

    @Test
    fun should_boundNestedScroller_when_inlineWidgetOverflows() = runComposeUiTest {
        var innerScrollState: ScrollState? = null
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                OverflowFixture {
                    val scrollState = rememberScrollState()
                    innerScrollState = scrollState
                    Box(Modifier.horizontalScroll(scrollState)) {
                        Box(Modifier.size(1200.dp, 40.dp))
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(300, assertNotNull(innerScrollState).maxValue)
        val ranges = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map {
            it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue()
        }
        assertEquals(listOf(300f, 600f), ranges.sorted())
    }

    @Test
    fun should_keepPayloadScrollable_when_payloadHasExplicitSize() = runComposeUiTest {
        var contentSize = IntSize.Zero
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                OverflowFixture {
                    Box(Modifier.size(900.dp, 40.dp).onSizeChanged { contentSize = it })
                }
            }
        }
        waitForIdle()
        assertEquals(IntSize(900, 40), contentSize)
        val range = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(600f, range.maxValue())
    }

    @Test
    fun should_keepOriginalSlotWithoutScroller_when_inlineWidgetFitsLine() = runComposeUiTest {
        var contentSize = IntSize.Zero
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                OverflowFixture(naturalWidthPx = 80f) {
                    Box(Modifier.fillMaxSize().onSizeChanged { contentSize = it })
                }
            }
        }
        waitForIdle()
        assertEquals(IntSize(80, 40), contentSize)
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertDoesNotExist()
    }

    @Test
    fun should_roundNaturalWidthUpToPixels_when_widthIsFractionalAtHigherDensity() = runComposeUiTest {
        var contentSize = IntSize.Zero
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                OverflowFixture(naturalWidthPx = 900.25f) {
                    Box(Modifier.fillMaxSize().onSizeChanged { contentSize = it })
                }
            }
        }
        waitForIdle()
        assertEquals(IntSize(901, 40), contentSize)
        val range = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(601f, range.maxValue())
    }
}

@Composable
private fun OverflowFixture(
    naturalWidthPx: Float = 900f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val identity = RenderIdentity(1L, 1L, 1L, 1L)
    val widget = DirectiveInlineWidgetModel(
        identity = RenderIdentity(2L, 1L, 1L, 1L),
        tagName = "custom",
        args = emptyMap(),
        alternateText = "wide widget",
    )
    val id = InlinePlaceholderId.from(widget)
    val placeholder = InlinePlaceholderLayoutSpec("wide widget", naturalWidthPx, 40f)
    val layout = computeInlineFlowLayout(
        input = InlineFlowInput(listOf(InlineFlowSegment.InlineRun(id, placeholder))),
        style = TextStyle.Default,
        density = density,
        textMeasurer = textMeasurer,
        maxWidthPx = 300f,
        maxLines = Int.MAX_VALUE,
    )
    val block = buildInlineLayoutBlockModel(
        identity = identity,
        frame = LayoutRect(0f, 0f, 300f, layout.heightPx),
        contentFrame = LayoutRect(0f, 0f, 300f, layout.heightPx),
        style = TextStyle.Default,
        layout = layout,
        inlinePayloads = mapOf(id to inlineWidgetPaintPayload("wide widget", naturalWidthPx, 40f, content)),
        widgetById = mapOf(id to widget),
    )
    PaintInlineLayoutContent(block, Modifier.width(with(density) { 300f.toDp() }))
}
