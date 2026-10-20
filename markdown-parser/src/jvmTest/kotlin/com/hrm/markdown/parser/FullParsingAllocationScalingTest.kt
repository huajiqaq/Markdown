package com.hrm.markdown.parser

import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.hrm.markdown.parser.ast.Paragraph
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/** Detect repeated prefix/suffix copying without asserting machine-dependent timings. */
class FullParsingAllocationScalingTest {
    @Test
    fun should_allocateLinearly_when_fullParsingLongFencedCode() = assertLinearAllocation(CommonMarkFlavour) { lines ->
        "```text\n" + "value 0123456789\n".repeat(lines) + "```\n"
    }

    @Test
    fun should_allocateLinearly_when_fullParsingConsecutiveLinkDefinitions() = assertLinearAllocation(CommonMarkFlavour) { lines ->
        (0 until lines).joinToString("\n") { "[r$it]: /target$it" }
    }

    @Test
    fun should_allocateLinearly_when_fullParsingConsecutiveAbbreviationDefinitions() = assertLinearAllocation(ExtendedFlavour) { lines ->
        (0 until lines).joinToString("\n") { "*[T$it]: expansion $it" }
    }

    @Test
    fun should_allocateLinearly_when_tableDetectionInspectsALongParagraph() = assertLinearAllocation(ExtendedFlavour) { lines ->
        "ordinary paragraph line\n".repeat(lines)
    }

    @Test
    fun should_allocateLinearly_when_materializingUnmatchedBrackets() =
        assertLinearAllocation(ExtendedFlavour, materializeInlines = true) { size -> "[".repeat(size * 16) + "sentinel" }

    private fun assertLinearAllocation(flavour: MarkdownFlavour, materializeInlines: Boolean = false, input: (Int) -> String) {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val allocations = listOf(100, 1000).map { lines ->
            val text = input(lines)
            val samples = mutableListOf<Long>()
            repeat(12) { trial ->
                val parser = MarkdownParser(flavour)
                val before = bean.getThreadAllocatedBytes(thread)
                val document = parser.parse(text)
                if (materializeInlines) (document.children.single() as Paragraph).children
                val bytes = bean.getThreadAllocatedBytes(thread) - before
                if (trial >= 4) samples += bytes
            }
            samples.sorted()[samples.size / 2]
        }
        // Ten times the input should allocate about ten times the memory. The former
        // per-line joins and definition-suffix copies grew by roughly 80–100 times.
        assertTrue(allocations.last() < allocations.first() * 20,
            "Full parsing allocation grows superlinearly: $allocations")
    }
}
