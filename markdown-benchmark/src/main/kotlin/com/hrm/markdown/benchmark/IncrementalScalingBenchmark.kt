package com.hrm.markdown.benchmark

import com.hrm.markdown.parser.MarkdownParserConfig

import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.incremental.EditOperation
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

/** Run the same harness on two revisions; setup, source reads and AST traversal are not timed. */
fun main(args: Array<String>) {
    fun argument(name: String, default: Int) = args.indexOf(name).takeIf { it >= 0 }
        ?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: default
    val warmups = argument("--warmups", 10).coerceAtLeast(0)
    val iterations = argument("--iterations", 20).coerceAtLeast(1)
    val coalesceThreshold = argument("--coalesce", 0).also { require(it >= 0) }
    // A buffered-only append does not measure parser work. Commit a full threshold's
    // worth of characters when comparing the LlmStreaming configuration.
    val appendedText = " tail".padEnd(coalesceThreshold, 'x')
    val bean = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported }?.apply { isThreadAllocatedMemoryEnabled = true }
    val thread = Thread.currentThread().threadId()
    println("scenario,flavour,paragraphs,median_us,p95_us,mean_allocated_bytes,coalesce_threshold")
    var consumed = 0L
    for (flavour in listOf(CommonMarkFlavour, ExtendedFlavour)) {
        for (count in listOf(100, 1000, 10000)) {
            val input = (0 until count).joinToString("\n\n") { "paragraph $it" }
            for (scenario in listOf("append", "replace")) {
                val costs = mutableListOf<Long>()
                val allocations = mutableListOf<Long>()
                repeat(warmups + iterations) { trial ->
                    val parser = MarkdownParser(MarkdownParserConfig(flavour = flavour, appendCoalesceThreshold = coalesceThreshold))
                    if (scenario == "append") {
                        parser.beginStream(); parser.append(input)
                    } else {
                        parser.parse(input); parser.applyEdit(EditOperation.Insert(0, ""))
                    }
                    val offset = input.indexOf("paragraph ${count / 2}") + 2
                    val before = bean?.getThreadAllocatedBytes(thread) ?: 0
                    val start = System.nanoTime()
                    val doc = if (scenario == "append") parser.append(appendedText) else parser.replace(offset, 1, "X")
                    val elapsed = System.nanoTime() - start
                    val bytes = bean?.getThreadAllocatedBytes(thread)?.minus(before) ?: -1
                    consumed += doc.children.size
                    if (trial >= warmups) { costs += elapsed; allocations += bytes }
                }
                costs.sort()
                println("$scenario,${flavour::class.simpleName},$count,${costs[costs.size / 2] / 1000.0},${costs[((costs.size - 1) * 0.95).toInt()] / 1000.0},${allocations.average().toLong()},$coalesceThreshold")
            }
        }
    }
    check(consumed > 0)
}
