package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class ContainerChildrenViewContractTest {
    @Test
    fun should_keepAnObservedBuilderViewLive_when_childrenAreAddedLater() {
        val paragraph = Paragraph()
        val observed = paragraph.children
        val child = Text("child")
        paragraph.appendChild(child)
        assertSame(child, observed.single())
        assertSame(observed, paragraph.children)
        assertFails { (observed as MutableList<Node>).clear() }
    }

    @Test
    fun should_detachClearedStorage_when_anOlderChildrenViewIsRetained() {
        val paragraph = Paragraph()
        val old = Text("old")
        paragraph.appendChild(old)
        val previous = paragraph.children
        paragraph.clearChildren()
        val current = paragraph.children
        val replacement = Text("replacement")
        paragraph.appendChild(replacement)
        assertSame(old, previous.single())
        assertNull(old.parent)
        assertSame(replacement, current.single())
        assertSame(paragraph, replacement.parent)
        assertFails { (previous as MutableList<Node>).clear() }
        assertFails { (current as MutableList<Node>).clear() }
    }

    @Test
    fun should_useSequentialPersistentIteration_when_metadataAndPublicViewsTraverseTheRoot() {
        val roots: List<Node> = List(32) { Text("root $it") }
        var indexedReads = 0
        val persistent = object : AbstractList<Node>() {
            override val size: Int get() = roots.size
            override fun get(index: Int): Node { indexedReads++; return roots[index] }
            override fun iterator(): Iterator<Node> = roots.iterator()
        }
        val document = Document()
        document.installIndexedChildren(persistent)
        val internal = mutableListOf<Node>()
        document.forEachMaterializedChild { internal.add(it) }
        assertEquals(0, indexedReads)
        assertEquals(roots, internal)
        val public = mutableListOf<Node>()
        for (node in document.children) public.add(node)
        assertEquals(0, indexedReads)
        assertEquals(roots, public)
        assertFails { (document.children as MutableList<Node>).clear() }
    }

    @Test
    fun should_leaveLazyChildrenUnmaterialized_when_internalMetadataAndSealingInspectAnEmptyContainer() {
        var parsed = false
        val paragraph = Paragraph()
        paragraph.setTrustedLazyInlineContent("text") { content, parent ->
            parsed = true
            parent.appendChild(Text(content))
        }
        val document = Document()
        document.appendChild(paragraph)
        paragraph.forEachMaterializedChild { error("Unmaterialized inline node unexpectedly exists") }
        assertEquals(emptyList(), paragraph.materializedChildrenSnapshot())
        document.sealPublication(document.children)
        assertFalse(parsed)
        assertFalse(paragraph.isInlineParsed)
        assertEquals("text", (paragraph.children.single() as Text).literal)
        assertFails { paragraph.appendChild(Text("outside")) }
    }
}
