package com.hrm.markdown.benchmark

import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.incremental.EditOperation
import kotlin.math.roundToLong

/**
 * 增量编辑基准。
 *
 * 模拟 IDE 实时编辑场景：在一份较大的文档中段反复执行
 * Insert / Delete / Replace 编辑，测量每次 [MarkdownParser.applyEdit] 的耗时。
 *
 * 用于回归比较混合编辑、后缀坐标平移及全局后处理的整体成本。
 * 固定影响区的规模对照另见 IncrementalScalingBenchmark。
 *
 * 使用：
 *   ./gradlew :markdown-benchmark:incrementalEditBenchmark \
 *       --args="--lines 5000 --edits 500 --warmups 5 --iterations 10"
 */
private const val DEFAULT_LINES = 5000
private const val DEFAULT_EDITS = 500
private const val DEFAULT_WARMUPS = 5
private const val DEFAULT_ITERATIONS = 10

fun main(args: Array<String>) {
    val cli = EditCli.parse(args)
    val baseText = buildLargeDocument(cli.lines)
    println("Incremental Edit Benchmark")
    println("lines=${cli.lines}, chars=${baseText.length}, editsPerIteration=${cli.edits}, warmups=${cli.warmups}, iterations=${cli.iterations}")
    println()

    repeat(cli.warmups) {
        runEditIteration(baseText, cli.edits)
    }

    val totals = LongArray(cli.iterations)
    val perEditMaxes = LongArray(cli.iterations)
    val perEditAvgs = DoubleArray(cli.iterations)

    for (i in 0 until cli.iterations) {
        val r = runEditIteration(baseText, cli.edits)
        totals[i] = r.totalNs
        perEditMaxes[i] = r.maxNs
        perEditAvgs[i] = r.totalNs.toDouble() / cli.edits
    }

    val sortedTotals = totals.sortedArray()
    val totalAvg = totals.average()
    val totalP95 = sortedTotals[((sortedTotals.size - 1) * 0.95).toInt()]
    val perEditAvg = perEditAvgs.average()
    val maxP95 = perEditMaxes.sortedArray().let { it[((it.size - 1) * 0.95).toInt()] }

    println("Summary")
    println("totalPerIter avg=${(totalAvg / 1_000_000.0).fmt()} ms  p95=${(totalP95 / 1_000_000.0).fmt()} ms")
    println("perEdit avg=${(perEditAvg / 1_000.0).fmt()} us")
    println("perEdit max p95=${(maxP95 / 1_000.0).fmt()} us")
}

private data class EditIterationResult(val totalNs: Long, val maxNs: Long)

private fun runEditIteration(baseText: String, editCount: Int): EditIterationResult {
    val parser = MarkdownParser()
    parser.parse(baseText) // 进入解析模式
    var total = 0L
    var max = 0L

    var currentLength = baseText.length
    var rng = 0xC0FFEEL

    for (i in 0 until editCount) {
        rng = rng * 6364136223846793005L + 1442695040888963407L
        val pick = ((rng ushr 33) and 0x3).toInt()
        val offset = (((rng ushr 16) and 0x7FFFFFFF).toInt() % (currentLength.coerceAtLeast(1))).coerceAtLeast(0)

        val edit: EditOperation = when (pick) {
            0 -> EditOperation.Insert(offset, "x")
            1 -> if (offset < currentLength) EditOperation.Delete(offset, 1) else EditOperation.Insert(offset, "y")
            2 -> if (offset < currentLength) EditOperation.Replace(offset, 1, "z") else EditOperation.Insert(offset, "z")
            else -> EditOperation.Append(" ")
        }

        val start = System.nanoTime()
        parser.applyEdit(edit)
        val cost = System.nanoTime() - start
        total += cost
        if (cost > max) max = cost

        currentLength = when (edit) {
            is EditOperation.Insert -> currentLength + edit.text.length
            is EditOperation.Delete -> currentLength - edit.length
            is EditOperation.Replace -> currentLength - edit.length + edit.newText.length
            is EditOperation.Append -> currentLength + edit.text.length
        }
    }

    return EditIterationResult(total, max)
}

private data class EditCli(
    val lines: Int,
    val edits: Int,
    val warmups: Int,
    val iterations: Int,
) {
    companion object {
        fun parse(args: Array<String>): EditCli {
            var lines = DEFAULT_LINES
            var edits = DEFAULT_EDITS
            var warmups = DEFAULT_WARMUPS
            var iterations = DEFAULT_ITERATIONS
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--lines" -> lines = args.getOrNull(i + 1)?.toIntOrNull() ?: lines
                    "--edits" -> edits = args.getOrNull(i + 1)?.toIntOrNull() ?: edits
                    "--warmups" -> warmups = args.getOrNull(i + 1)?.toIntOrNull() ?: warmups
                    "--iterations" -> iterations = args.getOrNull(i + 1)?.toIntOrNull() ?: iterations
                }
                i += 2
            }
            return EditCli(
                lines.coerceAtLeast(10),
                edits.coerceAtLeast(1),
                warmups.coerceAtLeast(0),
                iterations.coerceAtLeast(1),
            )
        }
    }
}

private fun Double.fmt(): String = "%.3f".format(this)

/**
 * 构造一篇 N 行的混合 Markdown 文档（段落、标题、列表、代码块）。
 */
private fun buildLargeDocument(lineCount: Int): String = buildString {
    var line = 0
    while (line < lineCount) {
        when (line % 12) {
            0 -> { append("# Section ${line / 12}\n"); line++ }
            1, 2, 3 -> { append("This is paragraph line $line with some **bold** and _italic_ words.\n"); line++ }
            4 -> { append("\n"); line++ }
            5 -> { append("- list item $line\n"); line++ }
            6 -> { append("- another item with [link](https://example.com/$line)\n"); line++ }
            7 -> { append("\n"); line++ }
            8 -> { append("```kotlin\n"); line++ }
            9 -> { append("fun foo$line() = $line\n"); line++ }
            10 -> { append("```\n"); line++ }
            else -> { append("> blockquote line $line\n"); line++ }
        }
    }
}
