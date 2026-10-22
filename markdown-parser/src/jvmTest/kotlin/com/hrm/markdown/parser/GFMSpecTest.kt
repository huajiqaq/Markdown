package com.hrm.markdown.parser

import com.hrm.markdown.parser.flavour.GFMFlavour
import com.hrm.markdown.parser.html.HtmlRenderer
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.random.Random
import kotlin.test.assertEquals
import org.junit.Test

class GFMSpecTest {

    data class SpecExample(
        val example: Int,
        val section: String,
        val markdown: String,
        val html: String,
    )

    private fun loadSpec(): List<SpecExample> {
        val lines = this::class.java.getResourceAsStream("/gfm-spec.txt")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
        return lines.map { line ->
            val parts = splitFields(line)
            check(parts.size >= 4) { "bad line: $line" }
            SpecExample(
                example = parts[0].toInt(),
                section = unescape(parts[1]),
                markdown = unescape(parts[2]),
                html = unescape(parts.drop(3).joinToString("|")),
            )
        }
    }

    /**
     * Split a line by unescaped `|` delimiters.
     * A `\|` sequence is NOT a delimiter (it represents a literal pipe in the field).
     * A `\\|` sequence IS a delimiter preceded by a literal backslash.
     */
    private fun splitFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            when {
                line[i] == '\\' && i + 1 < line.length -> {
                    // Escaped character: copy both chars to the current field verbatim
                    current.append(line[i])
                    current.append(line[i + 1])
                    i += 2
                }
                line[i] == '|' -> {
                    // Unescaped pipe: field separator
                    fields.add(current.toString())
                    current.clear()
                    i++
                }
                else -> {
                    current.append(line[i])
                    i++
                }
            }
        }
        fields.add(current.toString())
        return fields
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (i + 1 < s.length && s[i] == '\\') {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '|' -> { sb.append('|'); i += 2 }
                    else -> { sb.append(s[i]); i++ }
                }
            } else if (s[i] == '\u2192') {
                // GFM spec uses → (U+2192 RIGHT ARROW) to represent tab characters
                sb.append('\t')
                i++
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    @Test
    fun should_matchCompleteSemantics_when_specExamplesAreStreamed() {
        for (example in loadSpec()) {
            val expected = MarkdownParser(flavour = GFMFlavour).parse(example.markdown).semanticSnapshot()
            for (singleCharacter in listOf(true, false)) {
                val parser = MarkdownParser(MarkdownParserConfig(
                    flavour = GFMFlavour,
                    appendCoalesceThreshold = if (singleCharacter) 0 else 16,
                ))
                parser.beginStream()
                val random = Random(example.example)
                var offset = 0
                while (offset < example.markdown.length) {
                    val length = if (singleCharacter) 1 else random.nextInt(1, 33)
                    val end = (offset + length).coerceAtMost(example.markdown.length)
                    parser.append(example.markdown.substring(offset, end))
                    if (singleCharacter) {
                        // endStream reparses everything. Check live prefixes too, against a fresh
                        // session with no previously reused blocks or eagerly materialized inlines.
                        val reference = MarkdownParser(flavour = GFMFlavour).apply {
                            beginStream()
                            append(example.markdown.substring(0, end))
                        }
                        assertEquals(reference.document.semanticSnapshot(), parser.document.semanticSnapshot(),
                            "Spec #${example.example} [${example.section}], prefix=$end")
                    }
                    offset = end
                }
                assertEquals(expected, parser.endStream().semanticSnapshot(),
                    "Spec #${example.example} [${example.section}], singleCharacter=$singleCharacter")
            }
        }
    }

    @Test
    fun runGFMSpec() {
        val examples = loadSpec()
        check(examples.size >= 600) { "expected 600+ examples, got ${examples.size}" }

        var passed = 0
        var failed = 0
        val failedBySection = mutableMapOf<String, Int>()
        val failures = mutableListOf<String>()

        for (ex in examples) {
            val actual = HtmlRenderer.renderMarkdown(ex.markdown, flavour = GFMFlavour)
            if (actual == ex.html) {
                passed++
            } else {
                failed++
                failedBySection[ex.section] = (failedBySection[ex.section] ?: 0) + 1
                failures.add(
                    "FAIL #${ex.example} [${ex.section}]\n" +
                    "  md:       ${ex.markdown.take(80).replace("\n", "\\n")}\n" +
                    "  expected: ${ex.html.take(120).replace("\n", "\\n")}\n" +
                    "  actual:   ${actual.take(120).replace("\n", "\\n")}"
                )
            }
        }

        val report = buildString {
            appendLine("=== GFM Spec 0.29 Results ===")
            appendLine("Total: ${examples.size}")
            appendLine("Passed: $passed (${passed * 100 / examples.size}%)")
            appendLine("Failed: $failed (${failed * 100 / examples.size}%)")
            appendLine()
            if (failedBySection.isNotEmpty()) {
                appendLine("Failures by section:")
                failedBySection.entries.sortedByDescending { it.value }.forEach { (section, count) ->
                    appendLine("  $section: $count")
                }
                appendLine()
            }
            if (failures.isNotEmpty()) {
                appendLine("All ${failures.size} failures:")
                failures.forEach { appendLine(it); appendLine() }
            }
        }
        java.io.File("/tmp/gfm-results.txt").writeText(report)
        System.err.println(report)
        check(failed == 0) { report }
    }
}
