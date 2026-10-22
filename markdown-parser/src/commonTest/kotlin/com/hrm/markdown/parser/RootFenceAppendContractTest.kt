package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.postprocessors.PostProcessor
import com.hrm.markdown.parser.core.Attributes
import com.hrm.markdown.parser.flavour.*
import com.hrm.markdown.parser.incremental.EditOperation
import com.hrm.markdown.parser.incremental.IncrementalEngine
import com.hrm.markdown.parser.lint.LintingPostProcessor
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RootFenceAppendContractTest {
    @Test
    fun should_continueOnlyNewFenceLines_when_unclosedBodyAndStablePrefixGrow() {
        for (flavour in listOf(CommonMarkFlavour, GFMFlavour, ExtendedFlavour, MarkdownExtraFlavour)) {
            val engine = IncrementalEngine(flavour)
            engine.beginStream()
            var source = "prefix\n\n```kotlin\n" + "old body\n".repeat(128)
            val firstDocument = engine.append(source)
            val prefix = firstDocument.children.first()
            val code = firstDocument.children.filterIsInstance<FencedCodeBlock>().single()
            repeat(8) { index ->
                val priorSource = engine.sourceText
                val previousText = source
                val chunk = "new body $index\n"
                source += chunk
                val document = engine.append(chunk)
                assertTrue(engine.lastUpdate.parsedLines <= 2, engine.lastUpdate.toString())
                assertNull(engine.lastUpdate.fullParseReason)
                assertSame(prefix, document.children.first())
                assertSame(code, document.children.filterIsInstance<FencedCodeBlock>().single())
                assertSame(document, code.parent)
                assertEquals(previousText, priorSource.content)
                assertEquals(source.substring(source.indexOf("```kotlin\n") + 10), code.literal)
            }
            assertEquals(referenceDocument(MarkdownParser(flavour), source).semanticSnapshot(), engine.endStream().semanticSnapshot())
        }
    }

    @Test
    fun should_reparseOpeningAndPartialClosers_when_tokensCanChangeFenceSyntax() {
        val engine = IncrementalEngine(CommonMarkFlavour)
        engine.beginStream()
        engine.append("```")
        engine.append("kotlin")
        assertEquals("kotlin", engine.document.children.filterIsInstance<FencedCodeBlock>().single().language)
        engine.append("\nbody\n")
        engine.append("`")
        assertEquals("body\n`\n", engine.document.children.filterIsInstance<FencedCodeBlock>().single().literal)
        engine.append("`")
        assertEquals("body\n``\n", engine.document.children.filterIsInstance<FencedCodeBlock>().single().literal)
        engine.append("`")
        assertEquals("body\n", engine.document.children.filterIsInstance<FencedCodeBlock>().single().literal)
        engine.append("\n# after\n\n")
        assertTrue(engine.document.children.any { it is Heading })
        val source = "```kotlin\nbody\n```\n# after\n\n"
        assertEquals(referenceDocument(MarkdownParser(CommonMarkFlavour), source).semanticSnapshot(), engine.endStream().semanticSnapshot())
    }

    @Test
    fun should_matchClosingFenceRules_when_appendedLinesHaveIndentationOrDifferentMarkers() {
        for (marker in listOf("```", "   ``` \t", "    ```", "\t```", "``` x", "~~~", "``")) {
            val source = "```\nbody\n$marker"
            val engine = IncrementalEngine(CommonMarkFlavour)
            engine.beginStream()
            engine.append("```\nbody\n")
            engine.append(marker)
            val fresh = MarkdownParser(CommonMarkFlavour).apply { beginStream(); append(source) }
            assertEquals(fresh.document.semanticSnapshot(), engine.document.semanticSnapshot(), "marker=$marker")
            assertEquals(referenceDocument(MarkdownParser(CommonMarkFlavour), source).semanticSnapshot(), engine.endStream().semanticSnapshot(), "marker=$marker")
        }
    }

    @Test
    fun should_preserveCoalescingAndCrLfPairs_when_continuingAnOpenFence() {
        val engine = IncrementalEngine(CommonMarkFlavour, appendCoalesceThreshold = 16)
        engine.beginStream()
        val initial = engine.append("```kotlin\r")
        assertSame(initial, engine.append("\nshort"))
        assertEquals("", initial.children.filterIsInstance<FencedCodeBlock>().single().literal)
        engine.append(" added tail")
        assertEquals("short added tail\n", engine.document.children.filterIsInstance<FencedCodeBlock>().single().literal)
        engine.append("\r")
        val committed = engine.document
        assertSame(committed, engine.append("\n"))
        assertSame(committed, engine.append("next"))
        val source = "```kotlin\nshort added tail\nnext"
        assertEquals(source, engine.currentText())
        assertEquals(referenceDocument(MarkdownParser(CommonMarkFlavour), source).semanticSnapshot(), engine.endStream().semanticSnapshot())
    }

    @Test
    fun should_runHeadingAndAttributeProcessing_when_aContinuedFenceCloses() {
        val engine = IncrementalEngine()
        engine.beginStream()
        val opening = "# Title\n\n````kotlin {#sample .code linenums=false startline=7}\n"
        engine.append(opening)
        val body = "# Title\n{.inside}\n[^note]: raw code\n"
        engine.append(body)
        assertTrue(engine.lastUpdate.parsedLines <= 4, engine.lastUpdate.toString())
        val code = engine.document.children.filterIsInstance<FencedCodeBlock>().single()
        assertEquals("sample", code.attributes.id)
        assertFalse(code.showLineNumbers)
        assertEquals(7, code.startLineNumber)
        assertTrue(engine.document.footnoteDefinitions.isEmpty())

        val closing = "````\n\n{.after}\n\n# Title"
        engine.append(closing)
        val closed = engine.document.children.filterIsInstance<FencedCodeBlock>().single()
        assertTrue("after" in closed.attributes.classes)
        assertEquals(listOf("title", "title-1"), engine.document.children.filterIsInstance<Heading>().map { it.autoId })
        val source = opening + body + closing
        assertEquals(referenceDocument(MarkdownParser(), source).semanticSnapshot(), engine.endStream().semanticSnapshot())
    }

    @Test
    fun should_keepGlobalFallbacks_when_lintFootnotesOrCustomProcessorsRequireThem() {
        val linted = IncrementalEngine(lintingProcessor = LintingPostProcessor())
        linted.beginStream()
        linted.append("```\n" + "body\n".repeat(32))
        linted.append("tail\n")
        assertTrue(linted.lastUpdate.parsedLines > 2, linted.lastUpdate.toString())

        val footnotes = IncrementalEngine()
        footnotes.beginStream()
        footnotes.append("[^n]: note\n\n```\nbody\n")
        footnotes.append("[")
        footnotes.append("^n]\n")
        assertEquals("footnote order changed", footnotes.lastUpdate.fullParseReason)

        var calls = 0
        val processor = object : PostProcessor {
            override val priority = 100
            override fun process(document: Document) {
                calls++
                document.children.filterIsInstance<FencedCodeBlock>().forEach { it.literal += "processed" }
            }
        }
        val flavour = CommonMarkFlavour.copy(
            postProcessors = listOf(processor)
        )
        val custom = IncrementalEngine(flavour)
        custom.beginStream()
        custom.append("```\n")
        custom.append("body")
        assertEquals(2, calls)
        assertEquals("document postprocessor", custom.lastUpdate.fullParseReason)
        assertEquals("body\nprocessed", custom.document.children.filterIsInstance<FencedCodeBlock>().single().literal)
    }

    @Test
    fun should_keepNormalUpdates_when_fencesAreIndentedNestedOrConvertedToDiagrams() {
        for ((opening, bodyLine, flavour) in listOf(
            Triple("  ```kotlin\n", "  body\n", CommonMarkFlavour),
            Triple("> ```kotlin\n", "> body\n", CommonMarkFlavour),
            Triple("```mermaid\n", "A-->B\n", ExtendedFlavour),
        )) {
            val engine = IncrementalEngine(flavour)
            engine.beginStream()
            val initial = opening + bodyLine.repeat(16)
            engine.append(initial)
            engine.append(bodyLine)
            assertTrue(engine.lastUpdate.parsedLines > 2, engine.lastUpdate.toString())
            val source = initial + bodyLine
            val fresh = MarkdownParser(flavour).apply { beginStream(); append(source) }
            assertEquals(fresh.document.semanticSnapshot(), engine.document.semanticSnapshot())
            assertEquals(referenceDocument(MarkdownParser(flavour), source).semanticSnapshot(), engine.endStream().semanticSnapshot())
        }
    }

    @Test
    fun should_reparseContinuedSource_when_streamEndsOrAbortsBeforeAnEdit() {
        for (abort in listOf(false, true)) {
            val engine = IncrementalEngine(CommonMarkFlavour)
            engine.beginStream()
            engine.append("prefix\n\n```kotlin\n")
            engine.append("body\n")
            val snapshot = engine.sourceText
            val original = "prefix\n\n```kotlin\nbody\n"
            if (abort) engine.abort() else engine.endStream()
            val source = original.replace("body", "edited")
            val document = engine.applyEdit(EditOperation.Replace(original.indexOf("body"), 4, "edited"))
            assertEquals(original, snapshot.content)
            assertEquals(referenceDocument(MarkdownParser(CommonMarkFlavour), source).semanticSnapshot(), document.semanticSnapshot())
        }
    }

    @Test
    fun should_rejectUntrackedMutations_when_aFenceHasBeenPublished() {
        val mutations: List<Pair<String, (FencedCodeBlock) -> Unit>> = listOf(
            "fence character" to { it.fenceChar = '~' },
            "fence length" to { it.fenceLength = 100 },
            "fence indentation" to { it.fenceIndent = 2 },
            "info" to { it.info = "external" },
            "language" to { it.language = "mermaid" },
            "literal" to { it.literal = "external body" },
            "line range" to { it.lineRange = LineRange(0, 1) },
            "source range" to { it.sourceRange = SourceRange(SourcePosition.ZERO, SourcePosition(0, 1, 1)) },
            "content hash" to { it.contentHash = it.contentHash xor 1L },
            "parent" to { it.parent = Document() },
            "attributes" to { it.attributes = Attributes(id = "external") },
            "attribute classes" to { (it.attributes.classes as MutableList<String>).add("external") },
            "attribute pairs" to { (it.attributes.pairs as MutableMap<String, String>)["external"] = "value" },
            "highlight lines" to { it.highlightLines = listOf(9..11) },
            "mutable highlight lines" to { (it.highlightLines as MutableList<IntRange>).add(9..11) },
            "line numbers" to { it.showLineNumbers = true },
            "start line" to { it.startLineNumber = 99 },
        )
        val initial = "prefix\n\n```kotlin {.code .other #sample hl_lines=\"1,3\" linenums=false startline=7}\n" +
            "body\n".repeat(16)
        for ((label, mutate) in mutations) for (chunk in listOf("tail\n", "```\nafter")) {
            val engine = IncrementalEngine()
            engine.beginStream()
            engine.append(initial)
            engine.append("continued\n") // Check the stamp retained after a fast commit too.
            val block = engine.document.children.filterIsInstance<FencedCodeBlock>().single()
            assertFails(label) { mutate(block) }
            engine.append(chunk)
            val context = "$label chunk=$chunk"
            val source = initial + "continued\n" + chunk
            val fresh = MarkdownParser().apply { beginStream(); append(source) }
            assertEquals(fresh.document.semanticSnapshot(), engine.document.semanticSnapshot(), context)
            assertNodeCoordinatesEqual(fresh.document, engine.document, context)
            assertEquals(referenceDocument(MarkdownParser(), source).semanticSnapshot(), engine.endStream().semanticSnapshot(), context)
        }
    }
}
