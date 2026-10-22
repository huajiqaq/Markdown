package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fixed-work allocation contracts complement the existing asymptotic scaling guards. */
class ParserPublicationAllocationBudgetTest {
    @Test
    fun should_stayWithinAllocationBudget_when_publishingOneThousandLazyParagraphs() {
        val meter = allocationMeter() ?: return
        val input = paragraphPrefix()
        assertBudget("full-lazy-1000", FULL_PARSE_BUDGET) {
            val before = meter.bytes()
            val parser = MarkdownParser(CommonMarkFlavour)
            val document = parser.parse(input)
            retainedDocument = document
            val bytes = meter.bytes() - before

            // Input construction and all consumption/verification are outside the allocation window.
            assertEquals(PREFIX_PARAGRAPHS, document.children.size)
            document.children.forEach { node ->
                assertTrue(node is Paragraph)
                assertFalse(node.isInlineParsed, "Full parsing must leave ordinary paragraph inlines lazy")
            }
            assertEquals(input, parser.currentText())
            bytes
        }
    }

    @Test
    fun should_stayWithinAllocationBudget_when_committingTailAgainstLazyPrefix() =
        assertTailBudget(displayedPrefix = false)

    @Test
    fun should_stayWithinAllocationBudget_when_committingTailAgainstDisplayedPrefix() =
        assertTailBudget(displayedPrefix = true)

    private fun assertTailBudget(displayedPrefix: Boolean) {
        val meter = allocationMeter() ?: return
        val prefix = paragraphPrefix()
        val label = if (displayedPrefix) "tail-displayed-128" else "tail-lazy-128"
        assertBudget(label, TAIL_COMMIT_BUDGET) {
            val input = "$prefix\n\ntail"
            val parser = MarkdownParser(CommonMarkFlavour, appendCoalesceThreshold = CHUNK.length)
            parser.beginStream()
            val initial = parser.append(input)
            if (displayedPrefix) initial.children.forEach { (it as Paragraph).children }
            assertEquals(PREFIX_PARAGRAPHS + 1, initial.children.size)
            val initialRevision = requireNotNull(initial.publication).revision
            // Recording primitive publication facts allocates nothing in the measured loop.
            val revisions = LongArray(COMMITS)
            val sourceLengths = IntArray(COMMITS)

            val before = meter.bytes()
            repeat(COMMITS) { index ->
                val document = parser.append(CHUNK)
                retainedDocument = document
                revisions[index] = requireNotNull(document.publication).revision
                sourceLengths[index] = parser.sourceText.length
            }
            val bytes = meter.bytes() - before

            // Every 16-character append must publish, rather than merely receive a buffered chunk.
            repeat(COMMITS) { index ->
                assertEquals(initialRevision + index + 1, revisions[index], "Uncommitted append $index")
                assertEquals(input.length + (index + 1) * CHUNK.length, sourceLengths[index])
            }
            val document = parser.document
            assertEquals(PREFIX_PARAGRAPHS + 1, document.children.size)
            repeat(PREFIX_PARAGRAPHS) { index ->
                assertEquals(displayedPrefix, (document.children[index] as Paragraph).isInlineParsed)
            }
            val tail = document.children.last() as Paragraph
            assertEquals("tail" + CHUNK.repeat(COMMITS), tail.rawContent)
            tail.children // Changed-block inline consumption is deliberately outside the counter.
            assertEquals(input + CHUNK.repeat(COMMITS), parser.currentText())
            parser.endStream() // Finalization is a separate operation, also outside the counter.
            bytes
        }
    }

    private fun assertBudget(label: String, budget: Long, measure: () -> Long) {
        repeat(WARMUPS) { measure() }
        val samples = LongArray(MEASUREMENTS) { measure() }
        val median = samples.sorted()[MEASUREMENTS / 2]
        println("publication allocation scenario=$label bytes=${samples.contentToString()} median=$median budget=$budget")
        assertTrue(median <= budget, "$label allocation exceeds the fixed-work budget: $median > $budget bytes")
    }

    private fun paragraphPrefix(): String =
        (0 until PREFIX_PARAGRAPHS).joinToString("\n\n") { "paragraph $it plain text" }

    private fun allocationMeter(): AllocationMeter? {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return null
        if (!bean.isThreadAllocatedMemorySupported) return null
        bean.isThreadAllocatedMemoryEnabled = true
        return AllocationMeter(bean, Thread.currentThread().threadId())
    }

    private class AllocationMeter(private val bean: ThreadMXBean, private val threadId: Long) {
        fun bytes(): Long = bean.getThreadAllocatedBytes(threadId)
    }

    private companion object {
        const val PREFIX_PARAGRAPHS = 1_000
        const val COMMITS = 128
        const val CHUNK = "abcdefghijklmnop"
        const val WARMUPS = 4
        const val MEASUREMENTS = 5

        // JDK 21, three fresh processes, identical fixtures and 4 warmups / 5 measured trials:
        // 4125a99 medians: full 2,915,760–2,915,904 B; tail 2,368,640–2,371,712 B.
        // Candidate medians: full 2,219,464–2,505,624 B; tail 2,004,088–2,007,160 B.
        // Full parse has distinct JIT allocation states; these are resource ceilings, not latency
        // claims or proof that every possible old-code JIT state must exceed the ceiling.
        const val FULL_PARSE_BUDGET = 2_750_000L
        const val TAIL_COMMIT_BUDGET = 2_200_000L

        @Volatile
        var retainedDocument: Document? = null
    }
}
