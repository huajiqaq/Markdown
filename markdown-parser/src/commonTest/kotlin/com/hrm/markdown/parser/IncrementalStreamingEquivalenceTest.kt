package com.hrm.markdown.parser

import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.MarkdownExtraFlavour
import com.hrm.markdown.parser.incremental.IncrementalEngine
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.*

class IncrementalStreamingEquivalenceTest {
    @Test
    fun should_matchFreshStreamingPrefixAndIndependentFinalPipeline_when_splittingAllSyntaxFamilies() {
        var checked = 0
        for (flavour in listOf(CommonMarkFlavour, ExtendedFlavour, MarkdownExtraFlavour)) {
            for ((sample, feature) in incrementalSyntaxSamples.withIndex()) {
                val raw = "prefix\n\n$feature\n\nsuffix"
                for (chunkSize in listOf(1, 7, 31)) for (threshold in listOf(0, 16)) {
                    val parser = MarkdownParser(MarkdownParserConfig(flavour = flavour, appendCoalesceThreshold = threshold))
                    parser.beginStream()
                    var received = ""
                    var normalized = ""
                    var committedLength = 0
                    for (chunk in raw.chunked(chunkSize)) {
                        parser.document.semanticSnapshot() // Observe the previous revision eagerly.
                        received += chunk
                        val newText = normalizedTestInput(received)
                        val delta = newText.substring(normalized.length)
                        normalized = newText
                        val actual = parser.append(chunk)
                        assertEquals(normalized, parser.currentText())
                        assertEquals(normalized, parser.sourceText.content)
                        if (delta.isNotEmpty() && (threshold == 0 || '\n' in delta || normalized.length - committedLength >= threshold)) {
                            val reference = MarkdownParser(flavour).apply { beginStream(); append(normalized) }
                            val context = "sample=$sample flavour=${flavour::class.simpleName} chunk=$chunkSize threshold=$threshold chars=${received.length}"
                            assertEquals(reference.document.semanticSnapshot(), actual.semanticSnapshot(), context)
                            assertNodeCoordinatesEqual(reference.document, actual, context)
                            committedLength = normalized.length
                            checked++
                        }
                    }
                    assertEditedDocument(parser, normalized, parser.endStream())
                }
            }
        }
        println("committed streaming prefix comparisons=$checked")
    }

    @Test
    fun should_keepPrefixListsAndSourcesPersistent_when_manyAppendsRotateTheIndexes() {
        val engine = IncrementalEngine(CommonMarkFlavour)
        engine.beginStream()
        val inputs = mutableListOf<String>()
        val versions = mutableListOf<Pair<List<com.hrm.markdown.parser.ast.Node>, Int>>()
        repeat(1500) { i ->
            val chunk = "paragraph $i\n\n"
            inputs += chunk
            val doc = engine.append(chunk)
            assertEquals(i + 1, doc.children.size)
            assertTrue(engine.lastUpdate.parsedLines <= 5, engine.lastUpdate.toString())
            if (i % 113 == 0) versions += doc.children to doc.children.size
        }
        versions.forEach { (children, count) -> assertEquals(count, children.size) }
        val text = inputs.joinToString("")
        assertEquals(text, engine.sourceText.content)
        assertEquals(referenceDocument(MarkdownParser(CommonMarkFlavour), text).semanticSnapshot(), engine.endStream().semanticSnapshot())
    }
}
