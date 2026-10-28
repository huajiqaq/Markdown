package com.hrm.markdown.renderer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 行内元素装饰请求。与 [MarkdownInlineElementClickEvent] 同源分发：
 * 每个命中语义 mark（`==highlight==`、`<mark>` 等）的文本 run 在绘制前询问一次，
 * 返回 null 表示保持默认渲染。
 */
@Immutable
data class MarkdownInlineDecorationRequest(
    val kind: String,
    val text: String,
    val payload: Map<String, String> = emptyMap(),
    /** 元素原始属性，库不解释；使用方按自己的约定读取。 */
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * 绘制在文本下方的装饰线。跨行文本按可见行逐行绘制，
 * 每行只覆盖该行内实际存在的字符区间。
 *
 * 三种线型按以下优先级生效：圆点（[dotDiameter]）→ 虚线（[dashWidth] + [gapWidth]）→ 实线。
 */
@Immutable
data class MarkdownInlineUnderline(
    val color: Color,
    val strokeWidth: Dp = 1.dp,
    /** 虚线段长；与 [gapWidth] 同时提供时绘制虚线，否则为实线。 */
    val dashWidth: Dp? = null,
    /** 虚线间隔。 */
    val gapWidth: Dp? = null,
    /** 圆点直径。提供时绘制一系列圆点（点线），忽略 [dashWidth]/[gapWidth]。 */
    val dotDiameter: Dp? = null,
    /** 圆点之间的间隔，仅在 [dotDiameter] 提供时生效。 */
    val dotGap: Dp = 3.dp,
    /** 相对文本行底边向上抬升的距离。 */
    val offset: Dp = 2.dp,
    /**
     * 在装饰线**末尾**绘制一个标记点（例如“该片段有评论”）。
     * null 表示不画；提供时画在最后一行装饰线的右端。
     */
    val endMarker: MarkdownInlineEndMarker? = null,
) {
    /** 圆点连成实线（[dotGap] 小于直径时视觉连续）；供只想区分线型的调用方快速构造。 */
    companion object {
        fun solid(color: Color, strokeWidth: Dp = 1.dp, offset: Dp = 2.dp) =
            MarkdownInlineUnderline(color = color, strokeWidth = strokeWidth, offset = offset)
    }
}

/** 装饰线末端的标记点（用于“有评论”等状态提示）。 */
@Immutable
data class MarkdownInlineEndMarker(
    val color: Color,
    val diameter: Dp = 4.dp,
    /** 标记点与装饰线末端的间距。 */
    val gap: Dp = 3.dp,
)
