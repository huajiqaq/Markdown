package com.hrm.markdown.renderer.internal.core.compile

import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.renderer.internal.core.model.InternalRenderDocumentModel
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * Opt-in main, not a JUnit test. Uses the actual render compiler, including its identity/revision
 * scans, inline materialization and immutable model construction. No layout or Compose frames run.
 * Initial parsing, optional initial display, input construction and result checks are not timed.
 */
fun main(args: Array<String>) {
    val options = RenderCompilerBenchmarkOptions.parse(args)
    val runtime = ManagementFactory.getRuntimeMXBean()
    val allocationBean = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported }
        ?.apply { isThreadAllocatedMemoryEnabled = true }
    val threadId = Thread.currentThread().threadId()
    val allocatedBytes = { allocationBean?.getThreadAllocatedBytes(threadId) ?: -1L }
    val environment = RenderCompileEnvironment()

    println("# benchmark=actual-render-compiler; revision=${options.revision.replace('\n', ' ')}")
    println("# java=${System.getProperty("java.runtime.version")}; vm=${runtime.vmName}; args=${runtime.inputArguments.joinToString(" ")}")
    println("# os=${System.getProperty("os.name")}; arch=${System.getProperty("os.arch")}; processors=${Runtime.getRuntime().availableProcessors()}")
    println("# warmups=${options.warmups}; iterations=${options.iterations}; updates=${options.updates}; append_chars=2; coalesce=0; allocation_counter=${allocationBean != null}")
    println("# displayed: initial compile excluded; cold: first post-update compile separately reports initial inline materialization")
    println("# timings exclude input/setup/initial display/checks/endStream; compiler includes all actual compilation work; no layout/Compose/frame measurement")
    println("flavour,prefix_paragraphs,compiler,initial_state,operation,samples,median_us,p95_us,mean_allocated_bytes,total_us,total_allocated_bytes,mean_visited_nodes,mean_compiled_root_blocks")

    var consumed = 0L
    for (paragraphs in options.paragraphs) {
        // One fixed stable prefix and one bounded active tail. Every two-character append
        // commits; the tail grows by only 2 * updates characters within each fresh trial.
        val input = buildString {
            repeat(paragraphs) { index ->
                append("paragraph ").append(index)
                append(" with **bold** and a [link](/target/").append(index).append(")\n\n")
            }
            append("tail **closed**")
        }
        for (compiler in options.compilers) for (initialState in options.initialStates) {
            val samples = RenderCompilerOperation.entries.associateWith {
                RenderCompilerSamples(options.iterations * options.updates)
            }
            repeat(options.warmups + options.iterations) { trial ->
                val parser = MarkdownParser(CommonMarkFlavour)
                val session = RenderCompileSession()
                fun compile(document: com.hrm.markdown.parser.ast.Document) =
                    if (compiler == "incremental") session.compile(document, environment)
                    else DefaultRenderModelCompiler.compile(document, environment)
                parser.beginStream()
                var previousDocument = parser.append(input)
                if (initialState == "displayed") {
                    consumed = consumed xor consumeRenderCompilerResult(
                        compile(previousDocument),
                    )
                }
                repeat(options.updates) { update ->
                    val parseBytesBefore = allocatedBytes()
                    val parseStart = System.nanoTime()
                    val document = parser.append(" x")
                    val parseNanos = System.nanoTime() - parseStart
                    val parseBytes = compilerAllocationDifference(parseBytesBefore, allocatedBytes())
                    check(document !== previousDocument) { "A buffered receive cannot stand in for a committed parser update" }
                    previousDocument = document

                    val compileBytesBefore = allocatedBytes()
                    val compileStart = System.nanoTime()
                    val model = compile(document)
                    val compileNanos = System.nanoTime() - compileStart
                    val compileBytes = compilerAllocationDifference(compileBytesBefore, allocatedBytes())

                    // Read only a constant amount of IR after timing. The compiler's full
                    // traversal remains measured; result consumption adds no whole-tree pass.
                    check(model.blocks.size == paragraphs + 1)
                    consumed = consumed xor consumeRenderCompilerResult(model)
                    if (trial >= options.warmups) {
                        samples.getValue(RenderCompilerOperation.Append).add(parseNanos, parseBytes)
                        val operation = if (initialState == "cold" && update == 0) {
                            RenderCompilerOperation.CompileFirstMaterialization
                        } else {
                            RenderCompilerOperation.CompileWarmInlines
                        }
                        samples.getValue(operation).add(compileNanos, compileBytes,
                            if (compiler == "incremental") session.lastUpdate else null)
                    }
                }
                // Finalization is a separate lifecycle cost, outside this compiler experiment.
                parser.endStream()
            }
            for (operation in RenderCompilerOperation.entries) {
                printRenderCompilerSamples(paragraphs, compiler, initialState, operation, samples.getValue(operation))
            }
        }
    }
    renderCompilerBenchmarkSink = consumed
    println("# consumed=$renderCompilerBenchmarkSink")
}

@Volatile
private var renderCompilerBenchmarkSink = 0L

@Volatile
private var renderCompilerModelSink: InternalRenderDocumentModel? = null

private fun consumeRenderCompilerResult(model: InternalRenderDocumentModel): Long {
    renderCompilerModelSink = model
    return model.identity.contentRevision xor model.blocks.size.toLong() xor
        (model.blocks.firstOrNull()?.identity?.contentRevision ?: 0L) xor
        (model.blocks.lastOrNull()?.identity?.contentRevision ?: 0L)
}

private enum class RenderCompilerOperation(val label: String) {
    Append("parser-commit"),
    CompileFirstMaterialization("compile-first-inline-materialization"),
    CompileWarmInlines("compile-warm-inlines"),
}

private class RenderCompilerSamples(capacity: Int) {
    val nanos = LongArray(capacity)
    val allocated = LongArray(capacity)
    var count = 0
        private set
    var statsCount = 0
    var visitedNodes = 0L
    var compiledBlocks = 0L

    fun add(elapsed: Long, bytes: Long, stats: RenderCompileStats? = null) {
        nanos[count] = elapsed
        allocated[count] = bytes
        count++
        if (stats != null) {
            statsCount++
            visitedNodes += stats.visitedNodes
            compiledBlocks += stats.compiledRootBlocks
        }
    }
}

private fun compilerAllocationDifference(before: Long, after: Long): Long =
    if (before < 0 || after < 0) -1L else after - before

private fun printRenderCompilerSamples(
    paragraphs: Int, compiler: String, initialState: String, operation: RenderCompilerOperation, samples: RenderCompilerSamples,
) {
    val prefix = "commonmark,$paragraphs,$compiler,$initialState,${operation.label},${samples.count}"
    if (samples.count == 0) {
        println("$prefix,NA,NA,NA,0,NA,NA,NA")
        return
    }
    val times = samples.nanos.copyOf(samples.count).sortedArray()
    val bytes = samples.allocated.copyOf(samples.count)
    fun micros(value: Long): String = String.format(Locale.ROOT, "%.3f", value / 1_000.0)
    val median = micros(times[times.size / 2])
    val p95 = micros(times[((times.size - 1) * 0.95).toInt()])
    val allocationAvailable = bytes.all { it >= 0 }
    val meanBytes = if (allocationAvailable) String.format(Locale.ROOT, "%.1f", bytes.average()) else "NA"
    val totalBytes = if (allocationAvailable) bytes.sum().toString() else "NA"
    val visited = if (samples.statsCount > 0) samples.visitedNodes.toDouble() / samples.statsCount else "NA"
    val compiled = if (samples.statsCount > 0) samples.compiledBlocks.toDouble() / samples.statsCount else "NA"
    println("$prefix,$median,$p95,$meanBytes,${micros(times.sum())},$totalBytes,$visited,$compiled")
}

private data class RenderCompilerBenchmarkOptions(
    val warmups: Int,
    val iterations: Int,
    val updates: Int,
    val paragraphs: List<Int>,
    val initialStates: List<String>,
    val compilers: List<String>,
    val revision: String,
) {
    companion object {
        fun parse(args: Array<String>): RenderCompilerBenchmarkOptions {
            require(args.size % 2 == 0) { "Every option requires a value" }
            val values = args.toList().chunked(2).associate { it[0] to it[1] }
            val supported = setOf("--warmups", "--iterations", "--updates", "--paragraphs", "--initial", "--compiler", "--revision")
            require(values.keys.all { it in supported }) { "Supported options: ${supported.joinToString()}" }
            fun integer(name: String, fallback: Int) = values[name]?.toInt() ?: fallback
            val paragraphs = (values["--paragraphs"] ?: "100,1000,10000").split(',').map(String::toInt)
            val initialStates = (values["--initial"] ?: "displayed").split(',')
            val compilers = (values["--compiler"] ?: "cold,incremental").split(',')
            require(paragraphs.isNotEmpty() && paragraphs.all { it > 0 }) { "Paragraph counts must be positive" }
            require(initialStates.isNotEmpty() && initialStates.all { it == "displayed" || it == "cold" }) {
                "--initial supports displayed,cold"
            }
            require(compilers.isNotEmpty() && compilers.all { it == "cold" || it == "incremental" }) {
                "--compiler supports cold,incremental"
            }
            return RenderCompilerBenchmarkOptions(
                warmups = integer("--warmups", 2).also { require(it >= 0) },
                iterations = integer("--iterations", 5).also { require(it > 0) },
                updates = integer("--updates", 8).also { require(it > 0) },
                paragraphs = paragraphs,
                initialStates = initialStates,
                compilers = compilers,
                revision = values["--revision"] ?: "unrecorded",
            )
        }
    }
}
