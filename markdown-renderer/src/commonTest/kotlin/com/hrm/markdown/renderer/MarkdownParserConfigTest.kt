package com.hrm.markdown.renderer

import com.hrm.markdown.parser.flavour.ExtendedFlavour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkdownParserConfigTest {
    @Test
    fun should_keepDocumentedParserDefaults_when_usingConstructorOrPresets() {
        for (config in listOf(MarkdownParserConfig(), MarkdownParserConfig.Default)) {
            assertSame(ExtendedFlavour, config.flavour)
            assertTrue(config.customEmojiMap.isEmpty())
            assertFalse(config.enableAsciiEmoticons)
            assertFalse(config.enableLinting)
            assertEquals(0, config.appendCoalesceThreshold)
        }
        assertEquals(16, MarkdownParserConfig.LlmStreaming.appendCoalesceThreshold)
    }

    @Test
    fun should_disableHeadingNumbering_when_usingRenderingDefaults() {
        assertFalse(MarkdownRenderOptions().enableHeadingNumbering)
        assertFalse(MarkdownRenderOptions.Default.enableHeadingNumbering)
    }

    @Test
    fun should_rejectNegativeCoalesceThreshold_when_configuringParser() {
        for (threshold in listOf(-1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { MarkdownParserConfig(appendCoalesceThreshold = threshold) }
        }
    }
}
