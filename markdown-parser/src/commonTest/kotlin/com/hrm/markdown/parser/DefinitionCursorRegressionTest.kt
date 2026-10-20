package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.ast.SetextHeading
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefinitionCursorRegressionTest {
    @Test
    fun should_preserveTitlesCoordinatesAndFirstDefinition_when_advancingPastDuplicateDefinitions() {
        val input = """
            [a]: /one
              [b]: /two "second"
            [a]: /ignored
            [c]: /three
              "third"

            [a] [b] [c]
        """.trimIndent()
        val document = MarkdownParser(CommonMarkFlavour).parse(input)
        assertEquals(setOf("a", "b", "c"), document.linkDefinitions.keys)
        val first = document.linkDefinitions.getValue("a")
        val second = document.linkDefinitions.getValue("b")
        val third = document.linkDefinitions.getValue("c")
        assertEquals("/one", first.destination)
        assertEquals("second", second.title)
        assertEquals("third", third.title)
        assertEquals(LineRange(0, 1), first.lineRange)
        assertEquals(LineRange(1, 2), second.lineRange)
        assertEquals(LineRange(3, 5), third.lineRange)
        assertEquals("[a] [b] [c]", document.children.filterIsInstance<Paragraph>().single().rawContent)
    }

    @Test
    fun should_extractOnlyLeadingDefinitions_when_aSetextHeadingConsumesTheParagraph() {
        val document = MarkdownParser(CommonMarkFlavour).parse("[a]: /one\n  [b]: /two\nHeading\n===")
        assertEquals(setOf("a", "b"), document.linkDefinitions.keys)
        assertEquals(LineRange(1, 2), document.linkDefinitions.getValue("b").lineRange)
        assertEquals("Heading", document.children.filterIsInstance<SetextHeading>().single().rawContent)
    }

    @Test
    fun should_preserveAbbreviationCoordinatesAndFirstDefinition_when_advancingPastLinkAndDuplicateDefinitions() {
        val document = MarkdownParser().parse("[a]: /one\n*[A]: Alpha\n*[B]: Beta\n*[A]: ignored\nbody")
        assertEquals("/one", document.linkDefinitions.getValue("a").destination)
        val first = document.abbreviationDefinitions.getValue("A")
        val second = document.abbreviationDefinitions.getValue("B")
        assertEquals("Alpha", first.fullText)
        assertEquals("Beta", second.fullText)
        assertEquals(LineRange(1, 2), first.lineRange)
        assertEquals(LineRange(2, 3), second.lineRange)
        assertEquals("body", document.children.filterIsInstance<Paragraph>().single().rawContent)
    }

    @Test
    fun should_keepLaterDefinitionLookingLinesAsText_when_theParagraphStartsWithOrdinaryText() {
        val input = "ordinary\n[later]: /target\n*[ABC]: expanded"
        val document = MarkdownParser().parse(input)
        assertTrue(document.linkDefinitions.isEmpty())
        assertTrue(document.abbreviationDefinitions.isEmpty())
        assertEquals(input, document.children.filterIsInstance<Paragraph>().single().rawContent)
    }
}
