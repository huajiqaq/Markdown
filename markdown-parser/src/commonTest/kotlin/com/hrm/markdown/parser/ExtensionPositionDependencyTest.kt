package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.MathBlock
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.ast.Text
import com.hrm.markdown.parser.block.OpenBlock
import com.hrm.markdown.parser.block.postprocessors.BlockPostProcessor
import com.hrm.markdown.parser.block.starters.BlockStarter
import com.hrm.markdown.parser.core.LineCursor
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.hrm.markdown.parser.incremental.EditOperation
import com.hrm.markdown.parser.incremental.IncrementalEngine
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExtensionPositionDependencyTest {
    @Test
    fun should_refreshPositionDerivedBlockTextAutomatically_when_prependingANewline() {
        val processor = BlockPostProcessor { block ->
            if (block is Paragraph) {
                block.clearChildren()
                block.appendChild(Text("${block.lineRange.startLine}:${block.rawContent}"))
            }
            block
        }
        val flavour = CommonMarkFlavour.copy(
            postProcessors = listOf(processor)
        )
        val engine = IncrementalEngine(flavour)
        var source = "prefix\n\nmiddle\n\nsuffix"
        engine.fullParse(source)
        source = "\n$source"
        val shifted = engine.applyEdit(EditOperation.Insert(0, "\n"))
        assertEquals("extension source positions changed", engine.lastUpdate.fullParseReason)
        assertEquals(listOf("1:prefix", "3:middle", "5:suffix"),
            shifted.children.filterIsInstance<Paragraph>().map { (it.children.single() as Text).literal })
        assertEquals(referenceDocument(MarkdownParser(flavour), source).semanticSnapshot(), shifted.semanticSnapshot())

        val suffix = shifted.children.last()
        val middle = source.indexOf("middle")
        source = source.replaceRange(middle, middle + "middle".length, "MIDDLE")
        val unchangedPositions = engine.applyEdit(EditOperation.Replace(middle, "middle".length, "MIDDLE"))
        assertNull(engine.lastUpdate.fullParseReason)
        assertTrue(engine.lastUpdate.parsedLines <= 4)
        assertSame(suffix, unchangedPositions.children.last())
        assertEquals(referenceDocument(MarkdownParser(flavour), source).semanticSnapshot(), unchangedPositions.semanticSnapshot())
    }

    @Test
    fun should_refreshCustomStarterTextAutomatically_when_aRetainedSuffixMovesToNewLineNumbers() {
        val starter = object : BlockStarter {
            override val priority = 0
            override val canInterruptParagraph = true
            override fun tryStart(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
                if (!cursor.rest().startsWith("@line ")) return null
                val text = cursor.rest().removePrefix("@line ")
                cursor.advance(cursor.remaining)
                // A nonempty MathBlock is an ordinary self-contained AST leaf here;
                // its text deliberately depends on the public starter's absolute line.
                return OpenBlock(MathBlock("$lineIdx:$text"), contentStartLine = lineIdx, lastLineIndex = lineIdx)
            }
        }
        val flavour = CommonMarkFlavour.copy(
            blockStarters = listOf(starter) + CommonMarkFlavour.blockStarters
        )
        val engine = IncrementalEngine(flavour)
        var source = "prefix\n\n@line middle\n\n@line suffix"
        engine.fullParse(source)
        source = "\n$source"
        val shifted = engine.applyEdit(EditOperation.Insert(0, "\n"))
        assertEquals("extension source positions changed", engine.lastUpdate.fullParseReason)
        assertEquals(listOf("3:middle", "5:suffix"), shifted.children.filterIsInstance<MathBlock>().map { it.literal })
        assertEquals(referenceDocument(MarkdownParser(flavour), source).semanticSnapshot(), shifted.semanticSnapshot())

        val suffix = shifted.children.last()
        val middle = source.indexOf("middle")
        source = source.replaceRange(middle, middle + "middle".length, "MIDDLE")
        val unchangedPositions = engine.applyEdit(EditOperation.Replace(middle, "middle".length, "MIDDLE"))
        assertNull(engine.lastUpdate.fullParseReason)
        assertTrue(engine.lastUpdate.parsedLines <= 4)
        assertSame(suffix, unchangedPositions.children.last())
        assertEquals(referenceDocument(MarkdownParser(flavour), source).semanticSnapshot(), unchangedPositions.semanticSnapshot())
    }
}
