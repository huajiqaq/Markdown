package com.hrm.markdown.parser

import com.hrm.markdown.parser.core.SourceText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceTextChunkedLineTest {
    @Test
    fun should_keepExactlyOneFinalEmptyLine_when_longLineGainsAndLosesItsTerminator() {
        val text = "long line".repeat(1024)
        val initial = SourceText.of(text)
        val terminated = SourceText.applyEditFast(initial, initial.length, 0, "\n")
        assertEquivalent(text + "\n", terminated)
        val joined = SourceText.applyEditFast(terminated, text.length, 1, "")
        assertEquivalent(text, joined)
        assertEquivalent("", SourceText.applyEditFast(joined, 0, joined.length, ""))
        assertEquivalent(text, initial)
        assertEquivalent(text + "\n", terminated)
    }

    @Test
    fun should_preserveSnapshotsAndRangeHashes_when_appendingAndSplittingLongPhysicalLines() {
        var model = "中文😀0123456789".repeat(1024)
        var source = SourceText.of(model)
        val original = source
        val originalText = model
        repeat(512) {
            model += "abcdefghijklmnop"
            source = SourceText.applyEditFast(source, source.length, 0, "abcdefghijklmnop")
        }
        assertEquivalent(model, source)
        val insertion = source.length / 2
        model = model.replaceRange(insertion, insertion + 11, "\nnew\nlines\n")
        source = SourceText.applyEditFast(source, insertion, 11, "\nnew\nlines\n")
        assertEquivalent(model, source)
        source = SourceText.applyEditFast(source, 1, source.length - 2, "small")
        model = model.take(1) + "small" + model.takeLast(1)
        assertEquivalent(model, source)
        assertEquivalent(originalText, original)
    }

    @Test
    fun should_matchIndependentStringEdits_when_longLinesJoinAndCrossChunkBoundaries() {
        val random = Random(20261022)
        var model = "a".repeat(5000) + "\n" + "中😀b".repeat(1500) + "\nend"
        var source = SourceText.of(model)
        val history = mutableListOf<Pair<SourceText, String>>()
        val fragments = listOf("", "x", "\n", "\r\n", "\u0000", "😀", "long".repeat(300))
        repeat(300) { step ->
            if (step % 23 == 0) history += source to model
            val offset = random.nextInt(model.length + 1)
            val deleted = random.nextInt(minOf(1200, model.length - offset) + 1)
            val inserted = fragments[random.nextInt(fragments.size)]
            val normalized = inserted.replace("\r\n", "\n").replace('\r', '\n').replace('\u0000', '\uFFFD')
            model = model.replaceRange(offset, offset + deleted, normalized)
            source = SourceText.applyEditFast(source, offset, deleted, inserted)
            assertEquivalent(model, source)
            repeat(minOf(12, model.length)) {
                val position = random.nextInt(model.length)
                assertEquals(model[position], source[position])
                val end = minOf(model.length, position + random.nextInt(1200))
                assertEquals(model.substring(position, end), source.substring(position, end))
            }
        }
        history.forEach { (snapshot, text) -> assertEquivalent(text, snapshot) }
    }

    private fun assertEquivalent(text: String, source: SourceText) {
        assertEquals(text, source.content)
        val lines = text.split('\n')
        assertEquals(lines.size, source.lineCount)
        var offset = 0
        lines.forEachIndexed { index, line ->
            assertEquals(offset, source.lineStart(index))
            assertEquals(line, source.lineContent(index))
            assertEquals(index, source.lineAtOffset(offset))
            offset += line.length + if (index < lines.lastIndex) 1 else 0
            assertEquals(offset, source.lineEnd(index))
        }
        val rebuilt = SourceText.of(text)
        for (start in lines.indices) {
            val range = LineRange(start, minOf(lines.size, start + 3))
            assertEquals(rebuilt.contentHash(range), source.contentHash(range))
        }
    }
}
