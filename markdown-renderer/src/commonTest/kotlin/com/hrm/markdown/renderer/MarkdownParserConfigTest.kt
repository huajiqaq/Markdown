package com.hrm.markdown.renderer

import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.GFMFlavour
import com.hrm.markdown.parser.flavour.MarkdownExtraFlavour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkdownParserConfigTest {

    @Test
    fun should_use_extended_flavour_by_default() {
        val config = MarkdownParserConfig()
        assertSame(ExtendedFlavour, config.flavour)
    }

    @Test
    fun should_have_empty_emoji_map_by_default() {
        val config = MarkdownParserConfig()
        assertTrue(config.customEmojiMap.isEmpty())
    }

    @Test
    fun should_disable_ascii_emoticons_by_default() {
        val config = MarkdownParserConfig()
        assertFalse(config.enableAsciiEmoticons)
    }

    @Test
    fun should_disable_linting_by_default() {
        val config = MarkdownParserConfig()
        assertFalse(config.enableLinting)
    }

    @Test
    fun should_accept_gfm_flavour() {
        val config = MarkdownParserConfig(flavour = GFMFlavour)
        assertSame(GFMFlavour, config.flavour)
    }

    @Test
    fun should_accept_commonmark_flavour() {
        val config = MarkdownParserConfig(flavour = CommonMarkFlavour)
        assertSame(CommonMarkFlavour, config.flavour)
    }

    @Test
    fun should_accept_markdown_extra_flavour() {
        val config = MarkdownParserConfig(flavour = MarkdownExtraFlavour)
        assertSame(MarkdownExtraFlavour, config.flavour)
    }

    @Test
    fun should_accept_custom_emoji_map() {
        val emojiMap = mapOf("rocket" to "\uD83D\uDE80", "star" to "\u2B50")
        val config = MarkdownParserConfig(customEmojiMap = emojiMap)
        assertEquals(2, config.customEmojiMap.size)
        assertEquals("\uD83D\uDE80", config.customEmojiMap["rocket"])
    }

    @Test
    fun should_enable_ascii_emoticons() {
        val config = MarkdownParserConfig(enableAsciiEmoticons = true)
        assertTrue(config.enableAsciiEmoticons)
    }

    @Test
    fun should_enable_linting() {
        val config = MarkdownParserConfig(enableLinting = true)
        assertTrue(config.enableLinting)
    }

    @Test
    fun should_have_default_singleton() {
        val config = MarkdownParserConfig.Default
        assertSame(ExtendedFlavour, config.flavour)
        assertTrue(config.customEmojiMap.isEmpty())
        assertFalse(config.enableAsciiEmoticons)
        assertFalse(config.enableLinting)
    }

    @Test
    fun should_support_data_class_equality() {
        val config1 = MarkdownParserConfig(flavour = GFMFlavour)
        val config2 = MarkdownParserConfig(flavour = GFMFlavour)
        assertEquals(config1, config2)
    }

    @Test
    fun should_distinguish_different_flavours() {
        val config1 = MarkdownParserConfig(flavour = GFMFlavour)
        val config2 = MarkdownParserConfig(flavour = CommonMarkFlavour)
        assertNotEquals(config1, config2)
    }

    @Test
    fun should_support_data_class_copy() {
        val base = MarkdownParserConfig.Default
        val modified = base.copy(flavour = GFMFlavour, enableLinting = true)
        assertSame(GFMFlavour, modified.flavour)
        assertTrue(modified.enableLinting)
        assertFalse(modified.enableAsciiEmoticons)
    }

    @Test
    fun should_disable_heading_numbering_by_default() {
        val config = MarkdownRenderOptions()
        assertFalse(config.enableHeadingNumbering)
    }

    @Test
    fun should_enable_heading_numbering() {
        val config = MarkdownRenderOptions(enableHeadingNumbering = true)
        assertTrue(config.enableHeadingNumbering)
    }

    @Test
    fun should_copy_with_heading_numbering() {
        val base = MarkdownRenderOptions.Default
        val modified = base.copy(enableHeadingNumbering = true)
        assertTrue(modified.enableHeadingNumbering)
    }

    @Test
    fun should_include_heading_numbering_in_equality() {
        val config1 = MarkdownRenderOptions(enableHeadingNumbering = true)
        val config2 = MarkdownRenderOptions(enableHeadingNumbering = false)
        assertNotEquals(config1, config2)
    }

    @Test
    fun should_default_singleton_not_have_heading_numbering() {
        assertFalse(MarkdownRenderOptions.Default.enableHeadingNumbering)
    }
}
