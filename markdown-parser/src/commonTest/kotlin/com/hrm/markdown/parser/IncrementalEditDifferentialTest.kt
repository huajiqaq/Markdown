package com.hrm.markdown.parser

import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.GFMFlavour
import com.hrm.markdown.parser.flavour.MarkdownExtraFlavour
import com.hrm.markdown.parser.incremental.EditOperation
import kotlin.random.Random
import kotlin.test.Test

class IncrementalEditDifferentialTest {
    @Test
    fun should_matchFullParseAfterEveryEdit_when_replayingSeededMixedOperations() {
        val documents = listOf(
            "plain words\n\nsecond paragraph",
            "# Title\n\n- first\n- second",
            "| A | B |\n|---|---|\n| 1 | 2 |",
            "before\n\n```kotlin\nval x = 1\n```\n\nafter",
            "[read][ref]\n\n[ref]: /old \"Title\"\n",
            "Text[^n].\n\n[^n]: **note**\n",
            "中文 😀 **strong**\r\n\r\n> 引用",
            "Term\n: Definition\n\n==mark==",
        )
        val corpus = documents + incrementalSyntaxSamples
        var checked = 0
        val fragments = listOf("x", "中文😀", "\n", "\r\n\r\n", "**", "[ref]: /new\n", "- item\n", "```\n", "\u0000", "\n{.new #id}\n", "> ", "$$\n", ": definition\n", ":::columns\n")
        for (flavour in listOf(CommonMarkFlavour, GFMFlavour, ExtendedFlavour, MarkdownExtraFlavour)) {
            for ((documentIndex, original) in corpus.withIndex()) {
                for ((seed, includeAppends) in listOf(17, 83, 2026).flatMap { listOf(it to false, it to true) }) {
                    val random = Random(seed)
                    var source = normalizedTestInput(original)
                    val parser = MarkdownParser(MarkdownParserConfig(flavour = flavour, enableLinting = true))
                    parser.parse(source)
                    val history = mutableListOf<String>()
                    repeat(32) { step ->
                        val offset = random.nextInt(source.length + 1)
                        val fragment = fragments[random.nextInt(fragments.size)]
                        val start = offset.coerceAtMost((source.length - 1).coerceAtLeast(0))
                        val length = if (source.isEmpty()) 0 else random.nextInt(1, minOf(8, source.length - start) + 1)
                        val cycle = if (includeAppends) 4 else 3
                        val operation = when {
                            source.isEmpty() || step % cycle == 0 -> EditOperation.Insert(offset, fragment)
                            step % cycle == 1 -> EditOperation.Delete(start, length)
                            step % cycle == 2 -> EditOperation.Replace(start, length, fragment)
                            else -> EditOperation.Append(fragment)
                        }
                        source = when (operation) {
                            is EditOperation.Insert -> source.replaceRange(operation.offset, operation.offset, normalizedTestInput(operation.text))
                            is EditOperation.Delete -> source.removeRange(operation.offset, operation.offset + operation.length)
                            is EditOperation.Replace -> source.replaceRange(operation.offset, operation.offset + operation.length, normalizedTestInput(operation.newText))
                            is EditOperation.Append -> source + normalizedTestInput(operation.text)
                        }
                        history += operation.toString().replace("\n", "\\n").replace("\r", "\\r")
                        val context = "flavour=${flavour::class.simpleName}, document=$documentIndex, seed=$seed, appends=$includeAppends, step=$step\n" + history.joinToString("\n")
                        val actual = parser.applyEdit(operation)
                        assertEditedDocument(parser, source, actual, context)
                        checked++
                    }
                }
            }
        }
        println("seeded mixed edit steps=$checked")
    }
}
