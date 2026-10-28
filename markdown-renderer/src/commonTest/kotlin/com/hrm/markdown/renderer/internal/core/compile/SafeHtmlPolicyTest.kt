package com.hrm.markdown.renderer.internal.core.compile

import com.hrm.markdown.parser.html.HtmlTagParser
import com.hrm.markdown.parser.html.HtmlTagToken
import com.hrm.markdown.renderer.internal.core.model.BlockTextAlignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SafeHtmlPolicyTest {
    @Test
    fun should_accept_only_attributes_that_are_fully_mapped() {
        assertIs<SafeInlineHtmlAction.Span>(inlineAction("<strong>"))

        assertIs<SafeInlineHtmlAction.Span>(
            inlineAction("<span class='bold italic' style='color:#ff000080'>")
        )
        assertNull(inlineAction("<span style='position:fixed'>"))
        // 事件处理器属性是行为载体而非数据，出现即拒绝。
        assertNull(inlineAction("<strong onclick='alert(1)'>"))
        assertNull(inlineAction("<mark onerror='alert(1)'>"))

        assertIs<SafeInlineHtmlAction.Image>(
            inlineAction("<img src='https://example.com/a.png' width='24Px'>")
        )
        assertNull(inlineAction("<img src='https://example.com/a.png' onerror='alert(1)'>"))
        assertNull(inlineAction("<a href='javascript:alert(1)'>"))
        assertNull(inlineAction("<a href='jav&#x61;script:alert(1)'>"))
    }

    @Test
    fun should_accept_only_fully_supported_block_alignment_attributes() {
        val action = assertNotNull(
            SafeHtmlPolicy.blockAction(
                token = tag("<div style='text-align:right'>"),
                inheritedAlignment = BlockTextAlignment.INHERIT,
            )
        )

        assertEquals(SafeBlockHtmlRole.CONTAINER, action.role)
        assertEquals(BlockTextAlignment.RIGHT, action.alignment)
        assertNull(
            SafeHtmlPolicy.blockAction(
                token = tag("<div style='text-align:center;color:red'>"),
                inheritedAlignment = BlockTextAlignment.INHERIT,
            )
        )
        assertNull(
            SafeHtmlPolicy.blockAction(
                token = tag("<div onclick='alert(1)'>"),
                inheritedAlignment = BlockTextAlignment.INHERIT,
            )
        )
    }

    private fun inlineAction(literal: String): SafeInlineHtmlAction? =
        SafeHtmlPolicy.inlineAction(tag(literal))

    private fun tag(literal: String): HtmlTagToken =
        assertNotNull(HtmlTagParser.parse(literal), literal)

    @Test
    fun should_pass_unknown_attributes_through_as_opaque_data() {
        val strong = assertIs<SafeInlineHtmlAction.Span>(inlineAction("<strong data-x='1' title='t'>"))
        val strongMark = assertNotNull(strong.marks.singleOrNull())
        assertEquals("strong", strongMark.kind)
        assertEquals(mapOf("data-x" to "1", "title" to "t"), strongMark.attributes)

        val mark = assertIs<SafeInlineHtmlAction.Span>(inlineAction("<mark data-a='b' class='c'>"))
        val markSpan = assertNotNull(mark.marks.singleOrNull())
        assertEquals("highlight", markSpan.kind)
        assertEquals(mapOf("data-a" to "b", "class" to "c"), markSpan.attributes)

        // span：class 仍是渲染器解释的属性，未知类名保守拒绝；非渲染属性透传。
        assertNull(inlineAction("<span class='bold custom'>"))
        val span = assertIs<SafeInlineHtmlAction.Span>(inlineAction("<span class='bold' data-k='v'>"))
        val spanMark = assertNotNull(span.marks.singleOrNull())
        assertEquals("bold", spanMark.payload["class"])
        assertEquals(mapOf("data-k" to "v"), spanMark.attributes)

        assertNull(inlineAction("<span style='position:fixed' data-k='v'>"))
    }
}
