package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.postprocessors.BlockPostProcessor
import com.hrm.markdown.parser.core.Attributes
import com.hrm.markdown.parser.lint.Diagnostic
import com.hrm.markdown.parser.lint.DiagnosticCode
import com.hrm.markdown.parser.lint.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PublishedAstMutationContractTest {
    @Test
    fun should_rejectSemanticAndMetadataChanges_when_astIsPublished() {
        val document = MarkdownParser().parse("# heading\n\nparagraph **bold**\n\n```kotlin\nvalue\n```\n")
        val heading = document.children.filterIsInstance<Heading>().single()
        val paragraph = document.children.filterIsInstance<Paragraph>().single()
        val text = paragraph.children.first() as Text
        val fence = document.children.filterIsInstance<FencedCodeBlock>().single()
        assertFailsWith<IllegalStateException> { heading.level = 4 }
        assertFailsWith<IllegalStateException> { heading.autoId = "changed" }
        assertFailsWith<IllegalStateException> { paragraph.rawContent = "changed" }
        assertFailsWith<IllegalStateException> { text.literal = "changed" }
        assertFailsWith<IllegalStateException> { fence.literal = "changed" }
        assertFailsWith<IllegalStateException> { fence.language = "other" }
        assertFailsWith<IllegalStateException> { fence.attributes = Attributes(id = "other") }
        assertFailsWith<IllegalStateException> { text.contentHash = 1 }
        assertFailsWith<IllegalStateException> { text.lineRange = LineRange(10, 11) }
        assertFailsWith<IllegalStateException> { text.sourceRange = SourceRange.EMPTY }
        assertFailsWith<IllegalStateException> { text.parent = null }
        assertEquals("paragraph ", text.literal)
    }

    @Test
    fun should_rejectEveryStructuralEntryPoint_when_containerIsPublished() {
        val document = MarkdownParser().parse("text")
        val paragraph = document.children.single() as Paragraph
        val text = paragraph.children.single()
        assertFailsWith<IllegalStateException> { paragraph.appendChild(Text("other")) }
        assertFailsWith<IllegalStateException> { paragraph.insertChild(0, Text("other")) }
        assertFailsWith<IllegalStateException> { paragraph.removeChild(text) }
        assertFailsWith<IllegalStateException> { paragraph.removeChildAt(0) }
        assertFailsWith<IllegalStateException> { paragraph.replaceChild(text, Text("other")) }
        assertFailsWith<IllegalStateException> { paragraph.replaceChildren(0, 1, listOf(Text("other"))) }
        assertFailsWith<IllegalStateException> { paragraph.clearChildren() }
        assertFailsWith<IllegalStateException> { paragraph.setLazyInlineContent("other") { _, _ -> } }
        assertFails { (paragraph.children as MutableList<Node>).clear() }
        assertFails { (document.children as MutableList<Node>).clear() }
        assertEquals(1, paragraph.childCount())
    }

    @Test
    fun should_detachMutableCollectionInputs_when_nodeFieldsAreAssignedOrConstructed() {
        val attributes = mutableMapOf("id" to "before")
        val classes = mutableListOf("before")
        val lines = mutableListOf(1..2)
        val link = Link(attributes = attributes)
        val container = CustomContainer(cssClasses = classes)
        val fence = FencedCodeBlock(attributes = Attributes(classes = classes, pairs = attributes))
        fence.highlightLines = lines
        attributes["id"] = "after"
        classes[0] = "after"
        lines[0] = 8..9
        assertEquals("before", link.attributes["id"])
        assertEquals(listOf("before"), container.cssClasses)
        assertEquals(listOf("before"), fence.attributes.classes)
        assertEquals("before", fence.attributes.pairs["id"])
        assertEquals(listOf(1..2), fence.highlightLines)
        assertFails { (link.attributes as MutableMap<String, String>)["id"] = "escape" }
        assertFails { (container.cssClasses as MutableList<String>).clear() }
        assertFails { (fence.highlightLines as MutableList<IntRange>).clear() }
        assertFails { (fence.attributes.classes as MutableList<String>).clear() }
        assertFails { (fence.attributes.pairs as MutableMap<String, String>).clear() }
        assertFails { (link.attributes.entries.first() as MutableMap.MutableEntry<String, String>).setValue("escape") }
    }

    @Test
    fun should_guardDefinitionAndBibliographyCollectionViews_when_documentIsPublished() {
        val document = Document()
        val definition = LinkReferenceDefinition("key", "target")
        val bibliography = BibliographyDefinition()
        bibliography.entries["book"] = BibEntry("book", "content")
        document.linkDefinitions["key"] = definition
        document.appendChild(bibliography)
        document.sealPublication(document.children)
        assertFailsWith<IllegalStateException> { document.linkDefinitions["other"] = definition }
        assertFailsWith<IllegalStateException> { document.linkDefinitions.remove("key") }
        assertFailsWith<IllegalStateException> { document.linkDefinitions.clear() }
        assertFailsWith<IllegalStateException> { document.linkDefinitions.entries.first().setValue(LinkReferenceDefinition()) }
        assertFailsWith<IllegalStateException> { document.linkDefinitions.entries.iterator().also { it.next() }.remove() }
        assertFailsWith<IllegalStateException> { document.linkDefinitions.keys.remove("key") }
        assertFailsWith<IllegalStateException> { document.linkDefinitions.values.remove(definition) }
        assertFailsWith<IllegalStateException> { bibliography.entries.entries.first().setValue(BibEntry("book", "changed")) }
        assertFailsWith<IllegalStateException> { bibliography.entries.clear() }
        assertFailsWith<IllegalStateException> { definition.destination = "changed" }
        assertEquals("target", definition.destination)
    }

    @Test
    fun should_onlyAuthorizeNewInlineSubtree_when_lazyContentIsMaterialized() {
        val document = Document()
        val sibling = Text("stable sibling")
        val paragraph = Paragraph()
        var parsed = false
        paragraph.setLazyInlineContent("lazy") { content, parent ->
            val text = Text(content)
            parent.appendChild(text)
            text.literal += " parsed"
            assertFailsWith<IllegalStateException> { sibling.literal = "escaped" }
            assertFailsWith<IllegalStateException> { document.linkDefinitions.clear() }
            parsed = true
        }
        document.appendChild(paragraph)
        document.appendChild(sibling)
        document.sealPublication(document.children)
        assertFalse(parsed)
        val result = paragraph.children.single() as Text
        assertTrue(parsed)
        assertEquals("lazy parsed", result.literal)
        assertEquals("stable sibling", sibling.literal)
        assertFailsWith<IllegalStateException> { result.literal = "changed later" }
    }

    @Test
    fun should_keepMutationAuthorityLocal_when_anotherDocumentIsBeingUpdated() {
        val first = Document().also { it.appendChild(Text("first")) }
        val second = Document().also { it.appendChild(Text("second")) }
        first.sealPublication(first.children)
        second.sealPublication(second.children)
        withDocumentMutation(first) {
            (first.children.single() as Text).literal = "updated"
            assertFailsWith<IllegalStateException> { (second.children.single() as Text).literal = "escape" }
        }
        assertEquals("updated", (first.children.single() as Text).literal)
        assertFailsWith<IllegalStateException> { first.appendChild(Text("after scope")) }
    }

    @Test
    fun should_guardDiagnosticObjectAndList_when_documentIsPublished() {
        val document = MarkdownParser().parse("text")
        val diagnostic = Diagnostic(DiagnosticSeverity.WARNING, DiagnosticCode.EMPTY_LINK_DESTINATION, "example", 0)
        assertFailsWith<IllegalStateException> { document.diagnostics.add(diagnostic) }
        assertFailsWith<IllegalStateException> { document.diagnostics.addAll(listOf(diagnostic)) }
        assertFailsWith<IllegalStateException> { document.diagnostics.sort() }
        assertFails { (document.diagnostics.diagnostics as MutableList<Diagnostic>).add(diagnostic) }
    }

    @Test
    fun should_preserveSequentialIteration_when_readOnlyViewWrapsPersistentChildren() {
        var indexedReads = 0
        val backing = object : AbstractList<Int>() {
            override val size: Int = 128
            override fun get(index: Int): Int { indexedReads++; return index }
            override fun iterator(): Iterator<Int> = (0 until size).iterator()
        }
        val view = immutableListView(backing)
        assertEquals((0 until 128).sum(), view.sum())
        assertEquals(0, indexedReads)
        assertFails { (view.iterator() as MutableIterator<Int>).remove() }
    }

    @Test
    fun should_authorizeOnlyInputBlockAndNewDescendants_when_blockProcessorsAreChained() {
        val document = Document()
        val block = Paragraph()
        val sibling = Paragraph().also { it.appendChild(Text("stable")) }
        block.setLazyInlineContent("before") { content, parent -> parent.appendChild(Text(content)) }
        document.appendChild(block)
        document.appendChild(sibling)
        document.sealPublication(document.children)
        repeat(2) { pass ->
            withSubtreeMutation(block) {
                (block.children.first() as Text).literal = "pass $pass"
                block.setLazyInlineContent("late") { content, parent -> parent.appendChild(Text(content)) }
                (block.children.last() as Text).literal += " transformed"
                assertFailsWith<IllegalStateException> { sibling.rawContent = "escaped" }
            }
            assertFailsWith<IllegalStateException> { (block.children.last() as Text).literal = "escaped later" }
        }
        assertEquals("pass 1", (block.children.first() as Text).literal)
        assertEquals("late transformed", (block.children.last() as Text).literal)
        assertEquals("stable", (sibling.children.single() as Text).literal)
    }

    @Test
    fun should_suspendOuterDocumentAuthority_when_customLazyCallbackRuns() {
        val document = Document()
        val paragraph = Paragraph()
        val sibling = Text("stable")
        paragraph.setLazyInlineContent("lazy") { content, parent ->
            assertFailsWith<IllegalStateException> { sibling.literal = "escaped" }
            assertFailsWith<IllegalStateException> { document.linkDefinitions.clear() }
            parent.appendChild(Text(content))
        }
        document.appendChild(paragraph)
        document.appendChild(sibling)
        document.sealPublication(document.children)
        withDocumentMutation(document) {
            sibling.literal = "internal before"
            assertEquals("lazy", (paragraph.children.single() as Text).literal)
            assertEquals("internal before", sibling.literal)
            // The enclosing engine scope must be restored, including after callback completion.
            sibling.literal = "internal after"
        }
        assertEquals("internal after", sibling.literal)
        assertFailsWith<IllegalStateException> { sibling.literal = "outside scope" }
    }

    @Test
    fun should_materializeExtensionHiddenDependencies_when_builtInInlineContentRemainsLazy() {
        val document = Document()
        val extension = CustomContainer()
        val builtin = Paragraph()
        var trustedParsed = false
        var extensionParsed = false
        builtin.setTrustedLazyInlineContent("builtin **inline**") { content, parent ->
            trustedParsed = true
            parent.appendChild(Text(content))
        }
        extension.setLazyInlineContent("generated") { content, parent ->
            extensionParsed = true
            parent.appendChild(Heading(1).apply {
                customId = "generated"
                appendChild(Text(content))
            })
        }
        document.appendChild(extension)
        document.appendChild(builtin)
        assertFalse(extension.isInlineParsed)
        materializeUntrustedContent(document)
        assertTrue(extensionParsed)
        assertFalse(trustedParsed)
        assertFalse(builtin.isInlineParsed)
        val materialized = mutableListOf<Node>()
        extension.forEachMaterializedChild(materialized::add)
        assertEquals("generated", (materialized.single() as Heading).customId)
        document.sealPublication(document.children)
        assertFailsWith<IllegalStateException> { (materialized.single() as Heading).level = 2 }
    }

    @Test
    fun should_rejectNestedDocumentsBeforeChangingChildren_when_buildingAnAst() {
        for (parent in listOf(Document(), Paragraph(), CustomContainer())) {
            val existing = Text("existing")
            parent.appendChild(existing)
            val invalid = Document()
            assertFailsWith<IllegalArgumentException> { parent.appendChild(invalid) }
            assertFailsWith<IllegalArgumentException> { parent.insertChild(0, invalid) }
            assertFailsWith<IllegalArgumentException> { parent.replaceChild(existing, invalid) }
            val valid = Text("valid")
            assertFailsWith<IllegalArgumentException> { parent.replaceChildren(0, 1, listOf(valid, invalid)) }
            assertSame(existing, parent.children.single())
            assertSame(parent, existing.parent)
            assertNull(invalid.parent)
            assertNull(valid.parent)
        }
    }

    @Test
    fun should_rejectDocumentReplacements_when_blockExtensionReturnsARoot() {
        val document = Document().also { it.appendChild(Paragraph().also { paragraph -> paragraph.appendChild(Text("text")) }) }
        val processor = BlockPostProcessor { Document() }
        val error = assertFailsWith<IllegalArgumentException> { processor.process(document) }
        assertTrue(error.message.orEmpty().contains("AST root"))
    }

    @Test
    fun should_rejectRescheduledSiblingLazyContent_when_dependenciesAreBeingMaterialized() {
        val document = Document()
        val first = CustomContainer()
        val second = CustomContainer()
        var hiddenHeadingWasParsed = false
        first.setLazyInlineContent("first") { content, parent -> parent.appendChild(Text(content)) }
        second.setLazyInlineContent("second") { content, parent ->
            first.setLazyInlineContent("hidden") { _, target ->
                hiddenHeadingWasParsed = true
                target.appendChild(Heading(1).apply { appendChild(Text("hidden heading")) })
            }
            parent.appendChild(Text(content))
        }
        document.appendChild(first)
        document.appendChild(second)
        val error = assertFailsWith<IllegalStateException> { materializeUntrustedContent(document) }
        assertTrue(error.message.orEmpty().contains("rescheduled lazy content"))
        assertFalse(hiddenHeadingWasParsed)
        assertNull(document.publication)
    }
}
