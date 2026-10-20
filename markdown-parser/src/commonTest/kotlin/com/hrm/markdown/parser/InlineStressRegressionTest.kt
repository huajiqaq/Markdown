package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import kotlin.test.*

class InlineStressRegressionTest {
    @Test
    fun should_findValidAutolinks_when_earlierPrefixesDoNotMatch() {
        val paragraph = MarkdownParser().parse("whw plain <broken then <https://example.com> and www.example.org").children.single() as Paragraph
        val links = paragraph.children
        assertEquals("https://example.com", links.filterIsInstance<Autolink>().single().destination)
        assertEquals("http://www.example.org", links.filterIsInstance<Link>().single().destination)
        assertTrue(links.filterIsInstance<Text>().joinToString("") { it.literal }.startsWith("whw plain <broken then "))
    }

    @Test
    fun should_parseLaterWikiLinks_when_aPreviousOpenerHasNoCloseOnItsLineOrHasAnEmptyTarget() {
        val paragraph = MarkdownParser().parse("[[unterminated\n[[]] [[target|Label]]").children.single() as Paragraph
        val link = paragraph.children.filterIsInstance<WikiLink>().single()
        assertEquals("target", link.target)
        assertEquals("Label", link.label)
        assertEquals("[[unterminated[[]] ", paragraph.children.filterIsInstance<Text>().joinToString("") { it.literal })
    }

    @Test
    fun should_preserveReferenceResolution_when_bracketsAreNestedAndDefinitionsExist() {
        val parser = MarkdownParser()
        val document = parser.parse("![[nested]][ref] and [text][ref] and [^n] and [@book]\n\n[ref]: /target\n\n[^n]: note")
        val paragraph = document.children.filterIsInstance<Paragraph>().first()
        assertEquals("/target", paragraph.children.filterIsInstance<Image>().single().destination)
        assertEquals("/target", paragraph.children.filterIsInstance<Link>().single().destination)
        assertEquals("n", paragraph.children.filterIsInstance<FootnoteReference>().single().label)
        assertEquals("book", paragraph.children.filterIsInstance<CitationReference>().single().key)
    }
}
