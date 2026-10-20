package com.hrm.markdown.parser

import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class IncrementalCheckpointRegressionTest {
    @Test
    fun should_preserveClosedFence_when_editingFollowingParagraphWithoutBlankSeparator() {
        val source = "```\ncode\n```\nparagraph\n\nsuffix"
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.parse(source)
        val offset = source.indexOf("paragraph") + 4
        assertEditedDocument(parser, source.replaceRange(offset, offset, "X"), parser.insert(offset, "X"))
    }

    @Test
    fun should_preserveClosedMath_when_editingFollowingParagraphWithoutBlankSeparator() {
        val source = "$$\nx+y\n$$\nparagraph\n\nsuffix"
        val parser = MarkdownParser()
        parser.parse(source)
        val offset = source.indexOf("paragraph") + 4
        assertEditedDocument(parser, source.replaceRange(offset, offset, "X"), parser.insert(offset, "X"))
    }

    @Test
    fun should_preserveClosedFence_when_appendingAfterAdjacentParagraphBecomesStable() {
        val source = "```\ncode\n```\nparagraph\n\n"
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.beginStream()
        parser.append(source).semanticSnapshot()
        parser.append("tail")
        val reference = MarkdownParser(CommonMarkFlavour).apply { beginStream(); append(source + "tail") }
        assertEquals(reference.document.semanticSnapshot(), parser.document.semanticSnapshot())
    }

    @Test
    fun should_preserveFigure_when_editingBeforeAnUnchangedSuffix() =
        assertSuffixPreserved("![caption](/image.png)")

    @Test
    fun should_preserveAdmonition_when_editingBeforeAnUnchangedSuffix() =
        assertSuffixPreserved("> [!NOTE]\n> note")

    @Test
    fun should_preserveBibliography_when_editingBeforeAnUnchangedSuffix() =
        assertSuffixPreserved("[^bibliography]: key: Author, Book, 2026")

    @Test
    fun should_preserveDiagramAsControl_when_editingBeforeAnUnchangedSuffix() =
        assertSuffixPreserved("```mermaid\ngraph TD\nA-->B\n```")

    private fun assertSuffixPreserved(suffix: String) {
        val source = "one\n\ntwo\n\n" + suffix
        val parser = MarkdownParser()
        parser.parse(source)
        assertEditedDocument(parser, source.replaceRange(1,1,"X"), parser.insert(1,"X"))
    }

    @Test
    fun should_invalidateGlobalReference_when_listDefinitionBecomesIndentedCode() {
        val source = "[read][ref]\n\n- first\n\n    [ref]: /url\n\ntail"
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.parse(source)
        val offset = source.indexOf("- first")
        assertEditedDocument(parser, source.removeRange(offset,offset + 2), parser.delete(offset,2))
    }

}
