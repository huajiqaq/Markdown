package com.hrm.markdown.parser

import com.hrm.markdown.parser.core.SourceText
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.random.Random
import kotlin.test.*

class SourceTextEditModelTest {
    @Test
    fun should_preserveTextLinesOffsetsAndHashes_when_replayingMixedEditsAcrossTreeRotations() {
        for (seed in listOf(17, 83, 2026)) {
            val random = Random(seed)
            var model = "first\n\n中文 😀\nlast\n"
            var source = SourceText.of(model)
            val history = mutableListOf<Pair<SourceText, String>>()
            val fragments = listOf("", "x", "\n", "\n\n", "\r\n", "中文😀", "\u0000", "head\nbody\ntail")
            repeat(1000) { step ->
                if (step % 37 == 0) history += source to model
                val offset = random.nextInt(model.length + 1)
                val deleted = random.nextInt(minOf(24, model.length - offset) + 1)
                val inserted = fragments[random.nextInt(fragments.size)]
                model = model.replaceRange(offset, offset + deleted, normalizedTestInput(inserted))
                source = SourceText.applyEditFast(source, offset, deleted, inserted)
                assertSource(model, source, "seed=$seed step=$step")
            }
            // Future edits must not mutate previously published sources or cached flat strings.
            history.forEach { (snapshot, text) -> assertSource(text, snapshot, "historical snapshot") }
        }
    }

    @Test
    fun should_remainBalanced_when_repeatedlyAppendingPrependingAndDeletingLines() {
        var source = SourceText.of("")
        repeat(2000) { i ->
            val offset = if (i % 2 == 0) 0 else source.length
            source = SourceText.applyEditFast(source, offset, 0, "line $i\n")
            assertTrue(source.treeHeight <= 2 * ceil(log2(source.lineCount + 1.0)).toInt())
        }
        repeat(1000) {
            val first = source.lineContent(0) + "\n"
            source = SourceText.applyEditFast(source, 0, first.length, "")
            assertTrue(source.treeHeight <= 2 * ceil(log2(source.lineCount + 1.0)).toInt())
        }
        assertSource(source.content, source, "balanced after deletion")
    }

    @Test
    fun should_rejectInvalidEditsAtomically_when_offsetsOrLengthsAreOutOfRange() {
        val source = SourceText.of("abc\ndef")
        for ((offset, length) in listOf(-1 to 0, 8 to 0, 0 to -1, 1 to Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SourceText.applyEditFast(source, offset, length, "x") }
            assertSource("abc\ndef", source, "invalid edit")
        }
        assertSame(source, SourceText.applyEditFast(source, 2, 0, ""))
    }

    private fun assertSource(model: String, source: SourceText, context: String) {
        val starts = mutableListOf(0)
        model.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
        assertEquals(model, source.content, context)
        assertEquals(model.length, source.length, context)
        assertEquals(starts.size, source.lineCount, context)
        for (line in starts.indices) {
            val end = starts.getOrElse(line + 1) { model.length }
            assertEquals(starts[line], source.lineStart(line), context)
            assertEquals(end, source.lineEnd(line), context)
            assertEquals(model.substring(starts[line], end).removeSuffix("\n"), source.lineContent(line), context)
        }
        for (offset in 0..model.length) {
            val line = starts.indexOfLast { it <= offset }
            assertEquals(line, source.lineAtOffset(offset), context)
            assertEquals(offset - starts[line], source.columnAtOffset(offset), context)
        }
        val rebuilt = SourceText.of(model)
        for (start in starts.indices) {
            val end = minOf(starts.size, start + 3)
            assertEquals(model.substring(starts[start], starts.getOrElse(end) { model.length }),
                source.substring(starts[start], starts.getOrElse(end) { model.length }), context)
            assertEquals(rebuilt.contentHash(LineRange(start, end)), source.contentHash(LineRange(start, end)), context)
        }
    }
}
