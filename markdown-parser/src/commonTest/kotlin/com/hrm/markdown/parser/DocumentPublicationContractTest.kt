package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.ContainerNode
import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.ast.FencedCodeBlock
import com.hrm.markdown.parser.ast.Heading
import com.hrm.markdown.parser.ast.LeafNode
import com.hrm.markdown.parser.ast.Node
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.ast.Text
import com.hrm.markdown.parser.block.postprocessors.PostProcessor
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentPublicationContractTest {
    @Test
    fun should_retainSingleBlockIdentityOnlyForMatchingSyntax_when_editsChangeOneRootBlock() {
        val parser = MarkdownParser(CommonMarkFlavour)
        val initial = parser.parse("text")
        val documentId = initial.persistentId
        val paragraphId = initial.children.single().persistentId
        assertNotEquals(documentId, paragraphId)

        val edited = parser.replace(0, 4, "next")
        assertEquals(documentId, edited.persistentId)
        assertEquals(paragraphId, edited.children.single().persistentId)

        val heading = parser.replace(0, 4, "# next")
        assertTrue(heading.children.single() is Heading)
        assertNotEquals(paragraphId, heading.children.single().persistentId)
        assertNotEquals(documentId, heading.children.single().persistentId)
        assertFailsWith<IllegalStateException> { (heading.children.single() as Heading).level = 6 }
    }

    @Test
    fun should_describeAnExactSpliceAndRetainBlockIdentities_when_replacingAnEqualLengthMiddleParagraph() {
        val parser = MarkdownParser(CommonMarkFlavour)
        val source = "first\n\nmiddle\n\nlast"
        val initial = parser.parse(source)
        val before = initial.children.map(::blockValue)
        val beforeIds = initial.children.map { it.persistentId }
        val previous = assertNotNull(initial.publication)
        assertTrue(previous.isCurrent)
        val middleIndex = initial.children.indexOfFirst { (it as? Paragraph)?.rawContent == "middle" }
        assertTrue(middleIndex >= 0)

        val updated = parser.replace(source.indexOf("middle"), "middle".length, "change")
        val publication = assertNotNull(updated.publication)
        assertSame(previous.lineageId, publication.lineageId)
        assertFalse(previous.isCurrent)
        assertTrue(publication.isCurrent)
        assertEquals(previous.revision, publication.baseRevision)
        assertEquals(previous.revision + 1, publication.revision)
        assertFalse(publication.globalDependenciesChanged)
        val spliced = before.take(publication.startIndex) +
            updated.children.subList(publication.startIndex, publication.startIndex + publication.insertedCount)
                .map(::blockValue) +
            before.drop(publication.startIndex + publication.removedCount)
        assertEquals(updated.children.map(::blockValue), spliced)
        assertEquals(beforeIds[middleIndex], updated.children[middleIndex].persistentId)
        assertEquals(beforeIds.first(), updated.children.first().persistentId)
        assertEquals(beforeIds.last(), updated.children.last().persistentId)
        assertEquals(updated.children.size, updated.children.map { it.persistentId }.toSet().size)
        assertTrue(updated.children.all { it.persistentId != 0L })
    }

    @Test
    fun should_preservePublicationUntilCommit_when_streamingChunksAreBuffered() {
        val parser = MarkdownParser(CommonMarkFlavour, appendCoalesceThreshold = 16)
        parser.beginStream()
        val initial = parser.append("prefix\n\ntail")
        val previous = assertNotNull(initial.publication)
        val firstId = initial.children.first().persistentId
        val tailId = initial.children.last().persistentId
        assertSame(initial, parser.append("x"))
        assertSame(previous, parser.document.publication)

        val updated = parser.append("abcdefghijklmnop")
        val publication = assertNotNull(updated.publication)
        assertEquals(previous.revision, publication.baseRevision)
        assertSame(previous.lineageId, publication.lineageId)
        assertFalse(publication.globalDependenciesChanged)
        assertEquals(firstId, updated.children.first().persistentId)
        assertEquals(tailId, updated.children.last().persistentId)
        assertEquals("tailxabcdefghijklmnop", (updated.children.last() as Paragraph).rawContent)
    }

    @Test
    fun should_publishChangedFenceWithStableIdentity_when_usingTheOpenFenceAppendPath() {
        val parser = MarkdownParser(CommonMarkFlavour)
        parser.beginStream()
        val initial = parser.append("prefix\n\n```kotlin\nfirst\n")
        val previous = assertNotNull(initial.publication)
        val fence = initial.children.filterIsInstance<FencedCodeBlock>().single()
        val fenceIndex = initial.children.indexOf(fence)
        val updated = parser.append("next\n")
        val publication = assertNotNull(updated.publication)
        assertSame(previous.lineageId, publication.lineageId)
        assertEquals(previous.revision, publication.baseRevision)
        assertEquals(fenceIndex, publication.startIndex)
        assertEquals(1, publication.removedCount)
        assertEquals(1, publication.insertedCount)
        assertFalse(publication.globalDependenciesChanged)
        assertEquals(fence.persistentId, updated.children[publication.startIndex].persistentId)
        assertEquals("first\nnext\n", (updated.children[publication.startIndex] as FencedCodeBlock).literal)
    }

    @Test
    fun should_invalidateGlobalOutputOnlyForSemantics_when_headingReservationsOrSuffixPositionsChange() {
        val parser = MarkdownParser(ExtendedFlavour)
        val source = "# First\n\nplain\n\n# First"
        val initial = parser.parse(source)
        val previous = assertNotNull(initial.publication)
        val updated = parser.replace(source.indexOf("First"), "First".length, "Other")
        val publication = assertNotNull(updated.publication)
        assertSame(previous.lineageId, publication.lineageId)
        assertEquals(previous.revision, publication.baseRevision)
        assertTrue(publication.globalDependenciesChanged)
        assertEquals("first", updated.children.filterIsInstance<Heading>().last().id)

        val beforeShift = parser.parse("first\n\nmiddle\n\nlast")
        val suffixId = beforeShift.children.last().persistentId
        val shifted = parser.insert(1, "longer")
        assertFalse(assertNotNull(shifted.publication).globalDependenciesChanged)
        assertEquals(suffixId, shifted.children.last().persistentId)
    }

    @Test
    fun should_startAFreshLineage_when_fullInputOrGlobalDefinitionsAreRebuilt() {
        val parser = MarkdownParser(CommonMarkFlavour)
        val source = "[read][ref]\n\n[ref]: /first\n\ntail"
        val previous = assertNotNull(parser.parse(source).publication)
        val updated = parser.replace(source.indexOf("/first"), "/first".length, "/other")
        val publication = assertNotNull(updated.publication)
        assertFalse(previous.isCurrent)
        assertTrue(publication.isCurrent)
        assertNotSame(previous.lineageId, publication.lineageId)
        assertEquals(null, publication.baseRevision)
        assertTrue(publication.globalDependenciesChanged)
        assertEquals(0, publication.startIndex)
        assertEquals(updated.children.size, publication.insertedCount)
        val replaced = assertNotNull(parser.parse("replacement").publication)
        assertNotSame(publication.lineageId, replaced.lineageId)
        assertEquals(null, replaced.baseRevision)
    }

    @Test
    fun should_keepExternalFlavourTransformationsWritableBeforePublication_when_parsingAndAppendingRepeatedly() {
        var transformations = 0
        val processor = object : PostProcessor {
            override val priority = 100
            override fun process(document: Document) {
                transformations++
                val paragraphs = document.children.filterIsInstance<Paragraph>()
                paragraphs.forEach { paragraph ->
                    paragraph.clearChildren()
                    paragraph.appendChild(Text("processed:${paragraph.rawContent}"))
                }
                paragraphs.firstOrNull()?.blockAttributes = mapOf("blocks" to paragraphs.size.toString())
            }
        }
        val flavour = object : MarkdownFlavour by CommonMarkFlavour {
            override val postProcessors = listOf(processor)
        }
        val parser = MarkdownParser(flavour)
        val initial = parser.parse("first")
        assertEquals("processed:first", ((initial.children.single() as ContainerNode).children.single() as Text).literal)
        assertFailsWith<IllegalStateException> {
            ((initial.children.single() as ContainerNode).children.single() as Text).literal = "outside"
        }
        parser.beginStream()
        val streamed = parser.append("first\n\nsecond")
        val previous = assertNotNull(streamed.publication)
        val updated = parser.append("\n\nthird")
        val publication = assertNotNull(updated.publication)
        assertNotSame(previous.lineageId, publication.lineageId)
        assertTrue(publication.globalDependenciesChanged)
        assertEquals("3", (updated.children.first() as Paragraph).blockAttributes["blocks"])
        assertEquals(listOf("processed:first", "processed:second", "processed:third"),
            updated.children.filterIsInstance<Paragraph>().map { (it.children.single() as Text).literal })
        assertTrue(transformations >= 3)
        assertFailsWith<IllegalStateException> { updated.appendChild(Paragraph()) }
    }

    private fun blockValue(node: Node): String = when (node) {
        is Paragraph -> "paragraph:${node.rawContent}"
        is LeafNode -> "${node::class.simpleName}:${node.literal}"
        else -> node::class.simpleName.orEmpty()
    }
}
