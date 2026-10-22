package com.hrm.markdown.renderer.internal.core.compile

import com.hrm.markdown.parser.MarkdownParser
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RenderCompileAllocationScalingTest {
    @Test
    fun should_keepCommittedTailCompilationAllocationBounded_when_stablePrefixGrowsOneHundredTimes() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().threadId()
        val environment = RenderCompileEnvironment()
        var expectedVisits: Int? = null
        val allocations = listOf(100, 1000, 10000).map { paragraphs ->
            val parser = MarkdownParser()
            parser.beginStream()
            val initial = parser.append(buildString {
                repeat(paragraphs) { append("stable **bold** with [link](/target)\n\n") }
                append("tail")
            })
            val session = RenderCompileSession()
            val original = session.compile(initial, environment)
            val firstBlock = original.blocks.first()
            val samples = LongArray(20)
            repeat(40) { update ->
                // A real parse commit happens first. Input construction and parsing are excluded.
                val document = parser.append(" x")
                val before = bean.getThreadAllocatedBytes(threadId)
                val model = session.compile(document, environment)
                val allocated = bean.getThreadAllocatedBytes(threadId) - before
                if (update >= 20) samples[update - 20] = allocated

                // Verification and complete cold compilation stay outside the allocation sample.
                assertFalse(session.lastUpdate.coldRebuild, "paragraphs=$paragraphs update=$update")
                assertTrue(session.lastUpdate.compiledRootBlocks <= 4)
                assertTrue(session.lastUpdate.visitedNodes <= 16)
                expectedVisits?.let { assertEquals(it, session.lastUpdate.visitedNodes) }
                expectedVisits = session.lastUpdate.visitedNodes
                assertSame(firstBlock, model.blocks.first())
                assertEquals(document.children.size, session.lastUpdate.retainedSlots)
                assertEquals(paragraphs + 1, model.blocks.size)
                if (update == 39) {
                    assertEquals(DefaultRenderModelCompiler.compile(document, environment), model)
                }
            }
            samples.sortedArray()[samples.size / 2].also {
                println("render committed-tail prefixParagraphs=$paragraphs medianAllocatedBytes=$it visitedNodes=$expectedVisits")
            }
        }
        assertTrue(allocations.first() > 0, "The allocation counter must observe actual compiler work")
        // Persistent root paths may grow logarithmically; a 100x stable prefix must not allocate
        // proportionally to that prefix. Timing noise and parser allocations cannot affect this gate.
        assertTrue(allocations.last() < allocations.first() * 4,
            "Tail compilation allocation grows with the unchanged prefix: $allocations")
    }

    @Test
    fun should_retainOnlyCurrentRootSlots_when_manyPublishedBlocksAreRepeatedlyDeleted() {
        val block = "stable **bold** with [link](/target)\n\n"
        val parser = MarkdownParser()
        val session = RenderCompileSession()
        val environment = RenderCompileEnvironment()
        session.compile(parser.parse(block.repeat(2048) + "tail"), environment)
        repeat(8) { update ->
            val document = parser.delete(0, block.length * 256)
            val model = session.compile(document, environment)
            val remaining = 2048 - (update + 1) * 256
            assertFalse(session.lastUpdate.coldRebuild)
            assertEquals(document.children.size, session.lastUpdate.retainedSlots)
            assertEquals(remaining + 1, model.blocks.size)
            assertEquals(DefaultRenderModelCompiler.compile(document, environment), model)
        }
        repeat(3) {
            val grown = parser.insert(0, block.repeat(1024))
            session.compile(grown, environment)
            assertEquals(grown.children.size, session.lastUpdate.retainedSlots)
            val shrunk = parser.delete(0, block.length * 1024)
            val model = session.compile(shrunk, environment)
            assertEquals(shrunk.children.size, session.lastUpdate.retainedSlots)
            assertEquals(1, model.blocks.size)
            assertEquals(DefaultRenderModelCompiler.compile(shrunk, environment), model)
        }
    }
}
