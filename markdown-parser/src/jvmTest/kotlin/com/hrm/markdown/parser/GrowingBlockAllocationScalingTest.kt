package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.FencedCodeBlock
import com.hrm.markdown.parser.core.SourceText
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cumulative allocation guards; reading the final flat output is deliberately outside the counter. */
class GrowingBlockAllocationScalingTest {
    @Test
    fun should_avoidQuadraticAllocation_when_appendingSmallChunksToOneSourceLine() =
        assertBoundedGrowth("single source line") { chunks, allocatedBytes ->
            val chunk = "0123456789abcdef"
            var source = SourceText.of("")
            val before = allocatedBytes()
            repeat(chunks) { source = SourceText.applyEditFast(source, source.length, 0, chunk) }
            val bytes = allocatedBytes() - before
            assertEquals(1, source.lineCount)
            assertEquals(chunk.repeat(chunks), source.content)
            bytes
        }

    @Test
    fun should_avoidQuadraticAllocation_when_publishingGrowingRootFenceWithoutReadingLiteral() =
        assertBoundedGrowth("open root fence") { chunks, allocatedBytes ->
            val line = "0123456789abcde\n"
            val parser = MarkdownParser(MarkdownParserConfig(flavour = CommonMarkFlavour, appendCoalesceThreshold = 16))
            parser.beginStream()
            parser.append("```text\n")
            val before = allocatedBytes()
            repeat(chunks) { parser.append(line) }
            val bytes = allocatedBytes() - before
            // Every line committed a publication, but no publication read the deferred body.
            val expected = line.repeat(chunks)
            assertEquals(expected, (parser.document.children.single() as FencedCodeBlock).literal)
            assertEquals(expected, (parser.endStream().children.single() as FencedCodeBlock).literal)
            assertEquals("```text\n$expected", parser.currentText())
            bytes
        }

    private fun assertBoundedGrowth(label: String, measure: (Int, () -> Long) -> Long) {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val allocatedBytes = { bean.getThreadAllocatedBytes(thread) }
        measure(512, allocatedBytes) // One small warmup; keep this regular-JVM guard inexpensive.
        val allocations = listOf(512, 4096).map { chunks ->
            List(2) { measure(chunks, allocatedBytes) }.min()
        }
        // Eight times the chunks may grow tree paths; repeated full-prefix copying grows ~64x.
        assertTrue(allocations.last() < allocations.first() * 14,
            "$label cumulative allocation grew too quickly (512/4096 chunks): $allocations")
    }
}
