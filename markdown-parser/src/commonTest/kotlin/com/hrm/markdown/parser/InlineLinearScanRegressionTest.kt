package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.html.HtmlRenderer
import com.hrm.markdown.parser.inline.InlineParser
import com.hrm.markdown.parser.inline.InlineScanMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InlineLinearScanRegressionTest {
    private fun parse(input: String, document: Document = Document()): Pair<Paragraph, InlineScanMetrics> {
        val paragraph = Paragraph()
        val metrics = InlineScanMetrics()
        document.appendChild(paragraph)
        InlineParser(document).apply { scanMetrics = metrics }.parseInlines(input, paragraph)
        return paragraph to metrics
    }

    private fun html(input: String): String {
        val (paragraph) = parse(input)
        return HtmlRenderer.render(paragraph.parent as Document).trim()
    }

    private fun literal(paragraph: Paragraph): String = paragraph.children.joinToString("") {
        assertIs<Text>(it).literal
    }

    @Test
    fun should_boundFailedOpenerSearch_when_delimitersCanOnlyClose() {
        for (delimiter in listOf("*", "_", "~", "~~", "==", "++", "^")) {
            var previousChecks = 0L
            for (count in listOf(128, 256, 512)) {
                val input = "a$delimiter ".repeat(count)
                val (paragraph, metrics) = parse(input)
                assertEquals(input, literal(paragraph), delimiter)
                assertTrue(metrics.emphasisOpenerChecks <= count, "$delimiter: $metrics")
                if (previousChecks > 0) {
                    assertTrue(metrics.emphasisOpenerChecks <= previousChecks * 2 + 2, delimiter)
                }
                previousChecks = metrics.emphasisOpenerChecks
            }
        }
        val mixedCycle = "a* b_ c~ d~~ e== f++ g^ "
        val kinds = 7L
        var previousMixedChecks = 0L
        for (count in listOf(128, 256, 512)) {
            val input = mixedCycle.repeat(count)
            val (paragraph, metrics) = parse(input)
            assertEquals(input, literal(paragraph))
            // Each of the seven failure-bound categories visits each earlier
            // delimiter at most once: at most kinds * (kinds * count) checks.
            assertTrue(metrics.emphasisOpenerChecks <= kinds * kinds * count)
            if (previousMixedChecks > 0) {
                // A complete cycle contributes kinds² checks. Only the first
                // cycle is short by kinds * (kinds + 1) / 2 preceding entries.
                val startupDeficit = kinds * (kinds + 1) / 2
                assertTrue(metrics.emphasisOpenerChecks <= previousMixedChecks * 2 + startupDeficit)
            }
            previousMixedChecks = metrics.emphasisOpenerChecks
        }
    }

    @Test
    fun should_preserveEmphasisRules_when_failureBoundsAndLinkScopesDiffer() {
        val examples = mapOf(
            "*a* **b** ***c***" to "<p><em>a</em> <strong>b</strong> <em><strong>c</strong></em></p>",
            "*foo**bar**baz*" to "<p><em>foo<strong>bar</strong>baz</em></p>",
            "*a _b_ c*" to "<p><em>a <em>b</em> c</em></p>",
            "[a *b*](/target) *c*" to "<p><a href=\"/target\">a <em>b</em></a> <em>c</em></p>",
            "a* a** *valid*" to "<p>a* a** <em>valid</em></p>",
        )
        examples.forEach { (input, expected) -> assertEquals(expected, html(input), input) }
        val (paragraph) = parse("~~a~~ ~b~ ==c== ++d++ ^e^")
        assertEquals(1, paragraph.children.filterIsInstance<Strikethrough>().size)
        assertEquals(1, paragraph.children.filterIsInstance<Subscript>().size)
        assertEquals(1, paragraph.children.filterIsInstance<Highlight>().size)
        assertEquals(1, paragraph.children.filterIsInstance<InsertedText>().size)
        assertEquals(1, paragraph.children.filterIsInstance<Superscript>().size)
    }

    @Test
    fun should_trimAutolinkOnce_when_punctuationOrUnbalancedParenthesesAreLong() {
        for (tail in listOf(".".repeat(2048), ")".repeat(2048), ").,;!?".repeat(512))) {
            val input = "https://example.com/a(b)" + tail
            val (paragraph, metrics) = parse(input)
            val link = paragraph.children.filterIsInstance<Link>().single()
            assertEquals("https://example.com/a(b)", link.destination)
            assertEquals(tail, paragraph.children.filterIsInstance<Text>().single().literal)
            assertTrue(metrics.autolinkTailCharacters <= input.length * 2L)
        }
        assertEquals(
            "<p><a href=\"https://example.com/a(b)\">https://example.com/a(b)</a>). next</p>",
            html("https://example.com/a(b)). next"),
        )
    }

    @Test
    fun should_reuseDirectiveTerminatorSearch_when_openersAreUnclosedOrRejected() {
        for (input in listOf("x " + "{%".repeat(2048), "x " + "{%end ".repeat(1024) + " %}")) {
            val (paragraph, metrics) = parse(input)
            assertEquals(input, literal(paragraph))
            assertTrue(metrics.directiveSearchCharacters <= input.length.toLong())
        }
        val (paragraph) = parse("{%end {% icon name=star %} {% %} {% next %}")
        val directives = paragraph.children.filterIsInstance<DirectiveInline>()
        assertEquals(listOf("icon", "next"), directives.map { it.tagName })
        assertEquals("star", directives.first().args["name"])
        assertEquals("{%end  {% %} ", paragraph.children.filterIsInstance<Text>().joinToString("") { it.literal })
    }

    @Test
    fun should_resumeSpoilerSearchOnNextLine_when_previousLineHasNoClose() {
        val firstLine = "x " + ">!".repeat(2048)
        val (unclosed, metrics) = parse(firstLine)
        assertEquals(firstLine, literal(unclosed))
        assertTrue(metrics.spoilerSearchCharacters <= firstLine.length.toLong())
        val (paragraph) = parse("$firstLine\n>!valid!< and >!later!<")
        val spoilers = paragraph.children.filterIsInstance<Spoiler>()
        assertEquals(listOf("valid", "later"), spoilers.map { (it.children.single() as Text).literal })
    }

    @Test
    fun should_indexBacktickRunsOnce_when_runLengthsAreDistinct() {
        val input = (1..128).joinToString(" ") { "`".repeat(it) }
        val (paragraph, metrics) = parse(input)
        assertEquals(input, literal(paragraph))
        assertTrue(metrics.backtickSearchCharacters <= input.length.toLong())
        assertEquals("<p><code>code with ` tick</code> and <code>line break</code></p>", html("``code with ` tick`` and `line\nbreak`"))
        // The failed length-one scan indexes later runs. Successful length-two
        // searches must keep the last known length-three position, not overwrite it.
        val (cached) = parse("` ``a ``` b`` ```c```")
        assertEquals(listOf("a ``` b", "c"), cached.children.filterIsInstance<InlineCode>().map { it.literal })
        assertEquals("`  ", cached.children.filterIsInstance<Text>().joinToString("") { it.literal })
    }

    @Test
    fun should_countFootnoteDefinitionsOnce_when_manyReferencesShareOneInlineParse() {
        for (count in listOf(128, 256, 512)) {
            val document = Document()
            for (i in 1..count) document.footnoteDefinitions["$i"] = FootnoteDefinition("$i")
            val references = (1..count).joinToString(" ") { "[^$it]" }
            val (paragraph, metrics) = parse("$references $references", document)
            assertEquals((1..count).toList() + (1..count).toList(), paragraph.children.filterIsInstance<FootnoteReference>().map { it.index })
            assertEquals(count.toLong(), metrics.footnoteDefinitionChecks)
        }
    }

    @Test
    fun should_refreshFootnoteCounts_when_publicDefinitionsOrIndicesChangeBetweenCalls() {
        val document = Document()
        val a = FootnoteDefinition("a")
        document.footnoteDefinitions["a"] = a
        document.footnoteDefinitions["b"] = FootnoteDefinition("b")
        val metrics = InlineScanMetrics()
        val parser = InlineParser(document).apply { scanMetrics = metrics }
        fun parseReference(): Int {
            val paragraph = Paragraph()
            parser.parseInlines("[^a]", paragraph)
            return paragraph.children.filterIsInstance<FootnoteReference>().single().index
        }
        assertEquals(1, parseReference())
        // Replacing a map entry keeps its size. Preassigned numbers are counted,
        // rather than treating the largest existing number as the next index.
        a.index = 0
        document.footnoteDefinitions["b"] = FootnoteDefinition("b", index = 11)
        assertEquals(2, parseReference())
        a.index = 0
        document.footnoteDefinitions.remove("b")
        assertEquals(1, parseReference())
        a.index = 0
        document.footnoteDefinitions["c"] = FootnoteDefinition("c", index = 7)
        assertEquals(2, parseReference())
        assertEquals(7L, metrics.footnoteDefinitionChecks)
    }

    @Test
    fun should_countAliasedDefinitionEntries_when_publicLabelsShareOneFootnote() {
        val document = Document()
        val aliased = FootnoteDefinition("a")
        document.footnoteDefinitions["a"] = aliased
        document.footnoteDefinitions["b"] = aliased
        document.footnoteDefinitions["c"] = FootnoteDefinition("c")
        val (paragraph, metrics) = parse("[^a] [^c] [^b]", document)
        assertEquals(listOf(1, 3, 1), paragraph.children.filterIsInstance<FootnoteReference>().map { it.index })
        assertEquals(3L, metrics.footnoteDefinitionChecks)
    }
}
