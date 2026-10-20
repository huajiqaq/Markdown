package com.hrm.markdown.parser

import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/** Whole-update allocation guard: counting BlockStarter calls cannot detect a full-source copy. */
class IncrementalAllocationScalingTest {
    @Test
    fun should_avoidLinearPrefixAllocation_when_appendingToGrowingDocuments() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().threadId()
        for (flavour in listOf(CommonMarkFlavour, ExtendedFlavour)) {
            val allocations = listOf(100, 1000, 10000).map { count ->
                val input = (0 until count).joinToString("\n\n") { "paragraph $it" }
                val samples = mutableListOf<Long>()
                repeat(16) { trial ->
                    val parser = MarkdownParser(flavour)
                    parser.beginStream()
                    parser.append(input)
                    val before = bean.getThreadAllocatedBytes(threadId)
                    parser.append(" tail")
                    val allocated = bean.getThreadAllocatedBytes(threadId) - before
                    if (trial >= 6) samples += allocated
                }
                samples.sorted()[samples.size / 2].also {
                    println("append allocation flavour=${flavour::class.simpleName} paragraphs=$count bytes=$it")
                }
            }
            // A 100x prefix may grow balanced-tree paths, but must not allocate 100x text/AST.
            assertTrue(allocations.last() < allocations.first() * 4,
                "Whole append allocation scales with unchanged prefix: $allocations")
        }
    }
}
