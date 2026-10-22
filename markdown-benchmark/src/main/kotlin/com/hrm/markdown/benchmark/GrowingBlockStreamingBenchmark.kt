package com.hrm.markdown.benchmark

import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.FencedCodeBlock
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * Streams one continuously growing block instead of many small closed blocks. Construction of the
 * source, chunks, parser and sample buffers is excluded from the batch measurement. Individual parser
 * calls, optional consumption after every publication, and endStream are measured separately. The
 * batch measurement also includes harness bookkeeping; it is not the sum of just parser call times.
 */
fun main(args: Array<String>) {
    val options = GrowingBlockOptions.parse(args)
    val bean = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported }
        ?.apply { isThreadAllocatedMemoryEnabled = true }
    val thread = Thread.currentThread().threadId()
    val allocations = { bean?.getThreadAllocatedBytes(thread) ?: -1L }
    println("# revision=${options.revision.replace('\n', ' ')}")
    println("# java=${System.getProperty("java.runtime.version")}; vm=${System.getProperty("java.vm.name")}")
    println("# os=${System.getProperty("os.name")}; arch=${System.getProperty("os.arch")}; processors=${Runtime.getRuntime().availableProcessors()}")
    println("# warmups=${options.warmups}; iterations=${options.iterations}; allocationSupported=${bean != null}")
    println("# units=ASCII/UTF-16 characters; call percentiles are empirical individual-call samples, not JMH iteration means")
    println("shape,flavour,chars,chunk_chars,coalesce_chars,materialize,operation,samples,p50_us,p95_us,p99_us,max_us,mean_allocated_bytes,total_us,total_allocated_bytes")

    var consumed = 0L
    for (shape in options.shapes) {
        for (chars in options.sizes) {
            val source = buildGrowingBlock(shape, chars)
            val chunks = source.chunked(options.chunkChars)
            for ((flavourName, flavour) in options.flavours) {
                for (materialize in options.materialize) {
                    repeat(options.warmups) {
                        consumed += runGrowingBlockTrial(source, chunks, flavour, options.coalesceChars, materialize, allocations).consumed
                    }
                    val trials = List(options.iterations) {
                        runGrowingBlockTrial(source, chunks, flavour, options.coalesceChars, materialize, allocations)
                    }
                    consumed += trials.sumOf { it.consumed }
                    val prefix = "$shape,$flavourName,$chars,${options.chunkChars},${options.coalesceChars},$materialize"
                    for (operation in GrowingOperation.entries) {
                        val samples = trials.map { it.samples.getValue(operation) }
                        printGrowingSamples(prefix, operation.label, samples)
                    }
                    printGrowingSamples(prefix, "batch", trials.map { it.batch })
                }
            }
        }
    }
    check(consumed > 0) { "No stream result was consumed" }
    println("# consumed=$consumed")
}

private enum class GrowingOperation(val label: String) {
    Buffered("buffer-only"), Commit("commit"), ConsumeCommit("consume-commit"),
    EndStream("end-stream"), ConsumeEnd("consume-end"),
}

private class GrowingSamples(capacity: Int) {
    val times = LongArray(capacity)
    val allocations = LongArray(capacity)
    var size = 0
        private set

    fun add(nanos: Long, bytes: Long) {
        times[size] = nanos
        allocations[size] = bytes
        size++
    }
}

private data class GrowingTrial(
    val samples: Map<GrowingOperation, GrowingSamples>,
    val batch: GrowingSamples,
    val consumed: Long,
)

private fun runGrowingBlockTrial(
    source: String,
    chunks: List<String>,
    flavour: MarkdownFlavour,
    coalesce: Int,
    materialize: String,
    allocatedBytes: () -> Long,
): GrowingTrial {
    val parser = MarkdownParser(flavour, appendCoalesceThreshold = coalesce)
    parser.beginStream()
    val samples = GrowingOperation.entries.associateWith { operation ->
        GrowingSamples(if (operation == GrowingOperation.EndStream || operation == GrowingOperation.ConsumeEnd) 1 else chunks.size)
    }
    val batch = GrowingSamples(1)
    var consumed = 0L
    val batchBytesBefore = allocatedBytes()
    val batchStart = System.nanoTime()
    for (chunk in chunks) {
        val previous = parser.document
        val bytesBefore = allocatedBytes()
        val start = System.nanoTime()
        val document = parser.append(chunk)
        val nanos = System.nanoTime() - start
        val bytes = allocationDifference(bytesBefore, allocatedBytes())
        // These nonempty ASCII chunks contain no CR normalization no-ops. A buffered append returns
        // the previously published root; a committed update publishes a new document root.
        val operation = if (document === previous) GrowingOperation.Buffered else GrowingOperation.Commit
        samples.getValue(operation).add(nanos, bytes)
        if (operation == GrowingOperation.Commit && materialize == "commit") {
            val consumeBytesBefore = allocatedBytes()
            val consumeStart = System.nanoTime()
            consumed += materializeBenchmarkTree(document, readLiterals = true)
            val consumeNanos = System.nanoTime() - consumeStart
            samples.getValue(GrowingOperation.ConsumeCommit)
                .add(consumeNanos, allocationDifference(consumeBytesBefore, allocatedBytes()))
        }
    }
    val endBytesBefore = allocatedBytes()
    val endStart = System.nanoTime()
    val finalDocument = parser.endStream()
    val endNanos = System.nanoTime() - endStart
    samples.getValue(GrowingOperation.EndStream).add(endNanos, allocationDifference(endBytesBefore, allocatedBytes()))
    if (materialize == "commit") {
        val consumeBytesBefore = allocatedBytes()
        val consumeStart = System.nanoTime()
        consumed += materializeBenchmarkTree(finalDocument, readLiterals = true)
        val consumeNanos = System.nanoTime() - consumeStart
        samples.getValue(GrowingOperation.ConsumeEnd)
            .add(consumeNanos, allocationDifference(consumeBytesBefore, allocatedBytes()))
    }
    val batchNanos = System.nanoTime() - batchStart
    batch.add(batchNanos, allocationDifference(batchBytesBefore, allocatedBytes()))
    // Result consumption and correctness checks are outside all measured envelopes.
    check(parser.currentText() == source)
    (finalDocument.children.singleOrNull() as? FencedCodeBlock)?.let { code ->
        val body = source.substringAfter('\n')
        check(code.literal == if (body.endsWith('\n')) body else "$body\n")
    }
    consumed += finalDocument.children.size
    return GrowingTrial(samples, batch, consumed)
}

private fun allocationDifference(before: Long, after: Long) = if (before < 0 || after < 0) -1L else after - before

private fun printGrowingSamples(prefix: String, operation: String, samples: List<GrowingSamples>) {
    val times = samples.flatMap { it.times.take(it.size) }.sorted()
    val bytes = samples.flatMap { it.allocations.take(it.size) }
    if (times.isEmpty()) {
        println("$prefix,$operation,0,NA,NA,NA,NA,NA,0,NA")
        return
    }
    fun percentile(p: Double) = growingNumber(times[((times.size - 1) * p).toInt()] / 1000.0)
    val allocationAvailable = bytes.all { it >= 0 }
    val meanBytes = if (allocationAvailable) growingNumber(bytes.average()) else "NA"
    val totalBytes = if (allocationAvailable) bytes.sum().toString() else "NA"
    println("$prefix,$operation,${times.size},${percentile(0.50)},${percentile(0.95)},${percentile(0.99)}," +
        "${growingNumber(times.last() / 1000.0)},$meanBytes,${growingNumber(times.sum() / 1000.0)},$totalBytes")
}

private fun growingNumber(value: Double) = String.format(Locale.ROOT, "%.3f", value)

private fun buildGrowingBlock(shape: String, chars: Int): String {
    val prefix = if (shape == "open-fence") "```text\n" else ""
    val fragment = when (shape) {
        "single-line" -> "ordinary text "
        "multiline-paragraph" -> "ordinary paragraph line\n"
        "open-fence" -> "value 0123456789\n"
        else -> error("Unknown growing block shape: $shape")
    }
    return buildString(chars) {
        append(prefix)
        while (length + fragment.length <= chars) append(fragment)
        append(fragment.take(chars - length))
    }.also { check(it.length == chars) }
}

private data class GrowingBlockOptions(
    val warmups: Int,
    val iterations: Int,
    val sizes: List<Int>,
    val chunkChars: Int,
    val coalesceChars: Int,
    val shapes: List<String>,
    val flavours: List<Pair<String, MarkdownFlavour>>,
    val materialize: List<String>,
    val revision: String,
) {
    companion object {
        fun parse(args: Array<String>): GrowingBlockOptions {
            require(args.size % 2 == 0) { "Every option requires a value" }
            val values = args.toList().chunked(2).associate { it[0] to it[1] }
            val supported = setOf("--warmups", "--iterations", "--sizes", "--chunk", "--coalesce", "--shapes", "--flavours", "--materialize", "--revision")
            require(values.keys.all { it in supported }) { "Supported options: ${supported.joinToString()}" }
            fun int(name: String, fallback: Int) = values[name]?.toInt() ?: fallback
            fun strings(name: String, fallback: String) = (values[name] ?: fallback).split(',').also {
                require(it.isNotEmpty() && it.none(String::isBlank)) { "$name requires a nonempty comma-separated list" }
            }
            return GrowingBlockOptions(
                warmups = int("--warmups", 3).also { require(it >= 0) },
                iterations = int("--iterations", 5).also { require(it > 0) },
                sizes = strings("--sizes", "1024,4096,16384").map { it.toInt().also { size -> require(size >= 32) } },
                chunkChars = int("--chunk", 8).also { require(it > 0) },
                coalesceChars = int("--coalesce", 16).also { require(it >= 0) },
                shapes = strings("--shapes", "single-line,multiline-paragraph,open-fence").also {
                    require(it.all { shape -> shape in setOf("single-line", "multiline-paragraph", "open-fence") })
                },
                flavours = strings("--flavours", "commonmark,extended").map {
                    it to when (it) {
                        "commonmark" -> CommonMarkFlavour
                        "extended" -> ExtendedFlavour
                        else -> error("Unknown flavour: $it")
                    }
                },
                materialize = strings("--materialize", "none,commit").also { require(it.all { mode -> mode == "none" || mode == "commit" }) },
                revision = values["--revision"] ?: "unrecorded",
            )
        }
    }
}
