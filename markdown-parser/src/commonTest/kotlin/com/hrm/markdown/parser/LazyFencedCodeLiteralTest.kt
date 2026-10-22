package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.FencedCodeBlock
import kotlin.test.Test
import kotlin.test.assertEquals

class LazyFencedCodeLiteralTest {
    @Test
    fun should_materializeOnce_when_readingACommittedLiteralRepeatedly() {
        val code = FencedCodeBlock(literal = "initial")
        var calls = 0
        code.setLazyLiteral { calls++; "committed\n" }
        assertEquals(0, calls)
        assertEquals("committed\n", code.literal)
        assertEquals("committed\n", code.literal)
        assertEquals(1, calls)
    }

    @Test
    fun should_discardDeferredValue_when_callerSetsPublicLiteral() {
        val code = FencedCodeBlock()
        code.setLazyLiteral { error("The obsolete source must not be evaluated") }
        code.literal = "custom value"
        assertEquals("custom value", code.literal)
    }

    @Test
    fun should_shareCommittedSourceWithoutReadingIt_when_copyingLiteralState() {
        val source = FencedCodeBlock()
        val target = FencedCodeBlock()
        var calls = 0
        source.setLazyLiteral { calls++; "snapshot" }
        target.copyLiteralFrom(source)
        assertEquals(0, calls)
        source.literal = "replacement"
        assertEquals("snapshot", target.literal)
        assertEquals("snapshot", target.literal)
        assertEquals("replacement", source.literal)
        assertEquals(1, calls)
    }
}
