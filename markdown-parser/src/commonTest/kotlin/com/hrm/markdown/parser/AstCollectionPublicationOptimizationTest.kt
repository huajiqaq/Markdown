package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.core.Attributes
import kotlin.test.*

class AstCollectionPublicationOptimizationTest {
    @Test
    fun should_detachReadOnlyViewsButReuseOwnedSnapshots_when_copyingNodeCollections() {
        for (initial in listOf(emptyList(), listOf("one"), listOf("one", "two"))) {
            val backing = initial.toMutableList()
            val view = immutableListView(backing)
            val snapshot = immutableListCopy(view)
            backing.add("later")
            assertEquals(initial + "later", view)
            assertEquals(initial, snapshot)
            assertSame(snapshot, immutableListCopy(snapshot))
            assertFails { (snapshot as MutableList<String>).add("escape") }
        }
        val mutable = linkedMapOf("first" to "one", "second" to "two")
        val view = object : AbstractMap<String, String>() {
            override val entries: Set<Map.Entry<String, String>> get() = mutable.entries
        }
        val snapshot = immutableMapCopy(view)
        mutable["first"] = "later"
        mutable["third"] = "three"
        assertEquals(listOf("first", "second"), snapshot.keys.toList())
        assertEquals("one", snapshot["first"])
        assertSame(snapshot, immutableMapCopy(snapshot))
        assertFails { (snapshot.entries.first() as MutableMap.MutableEntry<String, String>).setValue("escape") }
    }

    @Test
    fun should_keepSingletonAndEmptyCopiesIsolated_when_mutableInputsChangeAfterConstruction() {
        val classes = mutableListOf("before")
        val pairs = linkedMapOf("id" to "before")
        val attributes = immutableAttributesCopy(Attributes(classes = classes, pairs = pairs))
        classes[0] = "after"
        pairs["id"] = "after"
        assertEquals(listOf("before"), attributes.classes)
        assertEquals(mapOf("id" to "before"), attributes.pairs)
        assertSame(attributes, immutableAttributesCopy(attributes))
        assertFails { (attributes.classes as MutableList<String>)[0] = "escape" }
        assertFails { (attributes.pairs as MutableMap<String, String>)["id"] = "escape" }
        assertFails { (attributes.pairs.entries.single() as MutableMap.MutableEntry<String, String>).setValue("escape") }
        assertEquals(attributes.pairs, mapOf("id" to "before"))
        assertEquals(attributes.pairs.hashCode(), mapOf("id" to "before").hashCode())
        assertFailsWith<IndexOutOfBoundsException> { attributes.classes[-1] }
        assertFailsWith<IndexOutOfBoundsException> { attributes.classes[1] }

        val initiallyEmpty = mutableListOf<String>()
        val emptyPairs = mutableMapOf<String, String>()
        val empty = immutableAttributesCopy(Attributes(classes = initiallyEmpty, pairs = emptyPairs))
        initiallyEmpty.add("late")
        emptyPairs["late"] = "value"
        assertTrue(empty.isEmpty)
        assertSame(empty, immutableAttributesCopy(empty))
        val nullable = immutableMapCopy(linkedMapOf<String?, String?>(null to null))
        assertTrue(nullable.containsKey(null))
        assertTrue(nullable.containsValue(null))
        assertEquals(mapOf<String?, String?>(null to null), nullable)
    }

    @Test
    fun should_keepEarlyCollectionViewsLiveAndGuarded_when_anEmptyDefinitionMapIsPublishedBeforeFirstInsertion() {
        val document = Document()
        val definitions = document.linkDefinitions
        val keys = definitions.keys
        val values = definitions.values
        val entries = definitions.entries
        assertTrue(keys.isEmpty())
        assertFalse(values.iterator().hasNext())
        assertFailsWith<NoSuchElementException> { entries.iterator().next() }
        document.sealPublication(emptyList())

        withDocumentMutation(document) {
            definitions["first"] = LinkReferenceDefinition("first", "/one")
            definitions["second"] = LinkReferenceDefinition("second", "/two")
        }
        assertSame(keys, definitions.keys)
        assertSame(values, definitions.values)
        assertSame(entries, definitions.entries)
        assertEquals(listOf("first", "second"), keys.toList())
        assertEquals(listOf("/one", "/two"), values.map { it.destination })
        val entry = entries.first()
        val iterator = entries.iterator().also { it.next() }
        assertFailsWith<IllegalStateException> { keys.remove("first") }
        assertFailsWith<IllegalStateException> { values.clear() }
        assertFailsWith<IllegalStateException> { entry.setValue(LinkReferenceDefinition()) }
        assertFailsWith<IllegalStateException> { iterator.remove() }
        assertFailsWith<IllegalStateException> { definitions.remove("missing") }

        withDocumentMutation(document) {
            entries.first().setValue(LinkReferenceDefinition("first", "/updated"))
            keys.remove("second")
        }
        assertEquals(listOf("first"), keys.toList())
        assertEquals("/updated", values.single().destination)
        assertFailsWith<IllegalStateException> { values.single().destination = "/escape" }
    }
}
