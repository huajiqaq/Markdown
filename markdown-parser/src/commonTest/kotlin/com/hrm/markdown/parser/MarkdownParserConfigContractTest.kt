package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.Emoji
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkdownParserConfigContractTest {
    @Test
    fun should_snapshotEmojiAliases_when_originalMapChangesBeforeLazyParsing() {
        val aliases = linkedMapOf("custom" to "🦄", "another" to "🌟")
        val config = MarkdownParserConfig(customEmojiMap = aliases)
        val hash = config.hashCode()
        val configurations = hashSetOf(config)
        val document = config.newParser().parse(":custom:")

        aliases["custom"] = "changed"
        aliases.remove("another")

        assertEquals(mapOf("custom" to "🦄", "another" to "🌟"), config.customEmojiMap)
        assertEquals(hash, config.hashCode())
        assertTrue(config in configurations)
        val paragraph = assertIs<Paragraph>(document.children.single())
        assertEquals("🦄", assertIs<Emoji>(paragraph.children.single()).literal)
    }

    @Test
    fun should_rejectAliasMutationThroughCollectionViews_when_castToMutableTypes() {
        for (aliases in listOf(mapOf("custom" to "🦄"), mapOf("custom" to "🦄", "another" to "🌟"))) {
            val config = MarkdownParserConfig(customEmojiMap = aliases)
            val frozen = config.customEmojiMap
            assertFails { (frozen as MutableMap<String, String>)["custom"] = "changed" }
            assertFails { (frozen.entries.first() as MutableMap.MutableEntry<String, String>).setValue("changed") }
            assertFails { (frozen.entries as MutableSet<Map.Entry<String, String>>).clear() }
            assertFails { (frozen.keys as MutableSet<String>).clear() }
            assertFails { (frozen.values as MutableCollection<String>).clear() }
            val iterator = frozen.entries.iterator()
            iterator.next()
            assertFails { (iterator as MutableIterator<Map.Entry<String, String>>).remove() }
            assertEquals(aliases, frozen)
        }
    }

    @Test
    fun should_reuseFrozenAliases_when_copyChangesOtherSettings() {
        val original = MarkdownParserConfig(customEmojiMap = mapOf("custom" to "🦄"))
        val copy = original.copy(enableLinting = true)
        assertSame(original, original.copy())
        assertSame(original.customEmojiMap, copy.customEmojiMap)
        assertNotEquals(original, copy)

        val replacement = mutableMapOf("replacement" to "🌟")
        val replaced = copy.copy(customEmojiMap = replacement)
        replacement.clear()
        assertEquals(mapOf("replacement" to "🌟"), replaced.customEmojiMap)
        assertEquals(mapOf("custom" to "🦄"), original.customEmojiMap)
    }

    @Test
    fun should_compareImmutableSettings_when_usedAsParserIdentity() {
        val config = MarkdownParserConfig(customEmojiMap = mapOf("custom" to "🦄"))
        val equivalent = MarkdownParserConfig(customEmojiMap = mutableMapOf("custom" to "🦄"))
        assertEquals(config, equivalent)
        assertEquals(config.hashCode(), equivalent.hashCode())
        assertNotEquals(config, config.copy(flavour = CommonMarkFlavour))
        assertNotEquals(config, config.copy(customEmojiMap = emptyMap()))
        assertNotEquals(config, config.copy(enableAsciiEmoticons = true))
        assertNotEquals(config, config.copy(enableLinting = true))
        assertNotEquals(config, config.copy(appendCoalesceThreshold = 16))
    }

    @Test
    fun should_createIndependentSessions_when_reusingConfiguration() {
        val config = MarkdownParserConfig(enableAsciiEmoticons = true, enableLinting = true)
        val first = config.newParser()
        val second = MarkdownParser(config)
        assertSame(config, first.config)
        assertSame(config, second.config)
        assertNotSame(first, second)

        first.parse("# Title\n\n### Skipped level\n\nhello :)")
        second.parse("other")
        assertTrue(first.diagnostics.diagnostics.isNotEmpty())
        assertSame(first.document.diagnostics, first.diagnostics)
        assertSame(first.diagnostics, first.diagnostics)
        val paragraph = assertIs<Paragraph>(first.document.children.last())
        assertEquals("😊", assertIs<Emoji>(paragraph.children.last()).literal)
        assertEquals("other", second.currentText())
        assertTrue(first.currentText().startsWith("# Title"))
        assertSame(CommonMarkFlavour, MarkdownParser(CommonMarkFlavour).config.flavour)
    }

    @Test
    fun should_applyCorePresets_when_streamingAndParsingThroughFacade() {
        assertSame(ExtendedFlavour, MarkdownParserConfig.Default.flavour)
        assertFalse(MarkdownParserConfig.Default.enableLinting)
        assertEquals(0, MarkdownParserConfig.Default.appendCoalesceThreshold)
        assertEquals(16, MarkdownParserConfig.LlmStreaming.appendCoalesceThreshold)
        val parser = MarkdownParserConfig.LlmStreaming.newParser()
        parser.beginStream()
        val empty = parser.document
        assertSame(empty, parser.append("tail"))
        assertEquals("tail", parser.currentText())
        assertNotSame(empty, parser.append("\n"))
        parser.endStream()
        assertFalse(parser.isStreaming)

        val config = MarkdownParserConfig(customEmojiMap = mapOf("custom" to "🦄"))
        val paragraph = assertIs<Paragraph>(MarkdownParser.parseToDocument(":custom:", config).children.single())
        assertEquals("🦄", assertIs<Emoji>(paragraph.children.single()).literal)
        for (threshold in listOf(-1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { config.copy(appendCoalesceThreshold = threshold) }
        }
    }
}
