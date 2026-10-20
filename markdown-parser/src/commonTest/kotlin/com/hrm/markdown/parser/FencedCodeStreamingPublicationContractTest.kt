package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.FencedCodeBlock
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FencedCodeStreamingPublicationContractTest {
    @Test
    fun should_publishCodeAtExistingBoundaries_when_coalescingSixteenCharacters() {
        val parser = MarkdownParser(CommonMarkFlavour, appendCoalesceThreshold = 16)
        parser.beginStream()
        val initial = parser.append("```kotlin\n")
        val block = initial.children.filterIsInstance<FencedCodeBlock>().single()
        for (character in "abcdefghijklmno") {
            assertSame(initial, parser.append(character.toString()))
            assertEquals("", block.literal)
        }
        val committed = parser.append("p")
        assertSame(block, committed.children.single())
        assertEquals("abcdefghijklmnop\n", block.literal)

        assertSame(committed, parser.append("XYZ"))
        assertEquals("abcdefghijklmnop\n", block.literal)
        val newlineCommit = parser.append("\n")
        assertSame(block, newlineCommit.children.single())
        assertEquals("abcdefghijklmnopXYZ\n", block.literal)

        assertSame(newlineCommit, parser.append("tail"))
        parser.append("\n```")
        assertSame(block, parser.document.children.single())
        assertEquals("abcdefghijklmnopXYZ\ntail\n", block.literal)
    }

    @Test
    fun should_flushBufferedCode_when_streamEndsOrIsCancelledBeforeThreshold() {
        for (cancel in listOf(false, true)) {
            val parser = MarkdownParser(CommonMarkFlavour, appendCoalesceThreshold = 16)
            parser.beginStream()
            val before = parser.append("```text\nline one\n")
            assertSame(before, parser.append("tail"))
            assertEquals("line one\n", before.children.filterIsInstance<FencedCodeBlock>().single().literal)
            val after = if (cancel) parser.abort() else parser.endStream()
            assertEquals("line one\ntail\n", after.children.filterIsInstance<FencedCodeBlock>().single().literal)
        }
    }
}
