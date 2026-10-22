package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.incremental.EditOperation
import com.hrm.markdown.parser.incremental.IncrementalEngine
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IncrementalPositionedEditScalingTest {
    @Test
    fun should_avoidSuffixSizedAllocation_when_equalLengthMiddleEditsHaveNoCoordinateDelta() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().threadId()
        val allocations = listOf(32, 512, 8192).map { count ->
            val source = List(count) { "paragraph $it **bold**\ncontinued [link](/$it)" }.joinToString("\n\n")
            val positions = PositionedParagraphInput(source)
            val engine = IncrementalEngine(positions.flavour)
            engine.fullParse(source)
            for (node in engine.document.children) {
                if (node is Paragraph) materializePositionedDescendants(node)
            }
            val first = engine.document.children.first()
            val suffix = engine.document.children.last() as Paragraph
            val suffixChild = suffix.children.first()
            val suffixRange = suffix.sourceRange
            val childRange = suffixChild.sourceRange
            val offset = source.indexOf("paragraph ${count / 2} ") + "paragraph ".length
            val samples = mutableListOf<Long>()
            repeat(20) { sample ->
                val before = bean.getThreadAllocatedBytes(threadId)
                engine.applyEdit(EditOperation.Replace(offset, 1, if (sample % 2 == 0) "X" else "Y"))
                val allocated = bean.getThreadAllocatedBytes(threadId) - before
                if (sample >= 8) samples += allocated
                assertNull(engine.lastUpdate.fullParseReason)
                assertTrue(engine.lastUpdate.parsedLines <= 5, "count=$count ${engine.lastUpdate}")
            }
            assertSame(first, engine.document.children.first())
            assertSame(suffix, engine.document.children.last())
            assertSame(suffixChild, suffix.children.first())
            assertEquals(suffixRange, suffix.sourceRange)
            assertEquals(childRange, suffixChild.sourceRange)
            samples.sorted()[samples.size / 2].also {
                println("positioned equal-length middle edit paragraphs=$count allocatedBytes=$it")
            }
        }
        // A 256x document may lengthen persistent-tree paths, but unchanged positioned
        // suffixes need no coordinate walk. Non-equal edits legitimately pay O(S) instead.
        assertTrue(allocations.last() < allocations.first() * 4,
            "Equal-length editing allocates with the unchanged positioned suffix: $allocations")
    }
}
