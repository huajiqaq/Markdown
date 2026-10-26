package com.hrm.markdown.renderer

import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.renderer.internal.MarkdownInteractionController
import com.hrm.markdown.renderer.internal.core.compile.DefaultRenderModelCompiler
import com.hrm.markdown.renderer.internal.core.compile.RenderCompileEnvironment
import com.hrm.markdown.renderer.internal.imageOccurrences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class MarkdownInteractionsTest {
    @Test
    fun should_dispatch_to_latest_handlers_without_replacing_document() {
        val document = compile("Citation [@source].")
        val controller = MarkdownInteractionController()
        val calls = mutableListOf<String>()
        controller.update(document, MarkdownInteractions(onCitationClick = { calls += "old:${it.key}" }))
        controller.citation("source", 42, null)
        controller.update(document, MarkdownInteractions(onCitationClick = { calls += "new:${it.key}" }))
        controller.citation("source", 42, null)
        controller.update(document, MarkdownInteractions.Default)
        controller.citation("source", 42, null)
        assertEquals(listOf("old:source", "new:source"), calls)
        assertEquals(false, controller.citationEnabled)
    }

    @Test
    fun inline_element_dispatcher_tracks_latest_handler_and_disabled_state() {
        val document = compile("Highlight ==marked== run.")
        val controller = MarkdownInteractionController()
        val calls = mutableListOf<String>()
        controller.update(document, MarkdownInteractions(onInlineElementClick = { calls += "old:${it.kind}:${it.text}" }))
        controller.inlineElement("highlight", "marked", emptyMap(), 7, null)
        controller.update(document, MarkdownInteractions(onInlineElementClick = { calls += "new:${it.kind}:${it.text}" }))
        controller.inlineElement("highlight", "marked", mapOf("k" to "v"), 7, null)
        controller.update(document, MarkdownInteractions.Default)
        controller.inlineElement("highlight", "marked", emptyMap(), 7, null)
        assertEquals(listOf("old:highlight:marked", "new:highlight:marked"), calls)
        assertEquals(false, controller.inlineElementEnabled)
    }

    @Test
    fun should_keep_duplicate_urls_and_nested_images_in_document_order() {
        val document = compile("""
            prefix ![first](same.png) suffix

            > ![second](same.png)

            | image |
            | --- |
            | ![third](last.png) |
        """.trimIndent())
        val images = document.blocks.imageOccurrences()
        assertEquals(listOf("same.png", "same.png", "last.png"), images.map { it.image.url })
        assertNotEquals(images[0].occurrenceId, images[1].occurrenceId)
        assertEquals(3, images.map { it.occurrenceId }.toSet().size)
    }

    @Test
    fun should_keep_click_gallery_bound_to_clicked_snapshot_after_stream_update() {
        val oldDocument = compile("prefix ![first](same.png) and ![second](same.png)")
        val controller = MarkdownInteractionController()
        var event: MarkdownImageClickEvent? = null
        val handlers = MarkdownInteractions(onImageClick = { event = it })
        controller.update(oldDocument, handlers)
        val second = oldDocument.blocks.imageOccurrences()[1]
        controller.image(second.image, second.occurrenceId, null)
        controller.update(compile("![replacement](new.png)"), handlers)
        val click = assertNotNull(event)
        assertEquals(1, click.gallery.initialIndex)
        assertEquals(listOf("same.png", "same.png"), click.gallery.images.map { it.image.url })
        assertSame(click.gallery, click.gallery)
    }

    @Test
    fun image_click_falls_back_to_inline_element_dispatch_without_dedicated_handler() {
        val document = compile("![alt](pic.png)")
        val controller = MarkdownInteractionController()
        var dedicated: MarkdownImageClickEvent? = null
        var unified: MarkdownInlineElementClickEvent? = null
        controller.update(document, MarkdownInteractions(onImageClick = { dedicated = it }))
        val occurrence = document.blocks.imageOccurrences().single()
        controller.image(occurrence.image, occurrence.occurrenceId, null)
        controller.update(document, MarkdownInteractions(onInlineElementClick = { unified = it }))
        controller.image(occurrence.image, occurrence.occurrenceId, null)
        controller.update(document, MarkdownInteractions(onImageClick = { dedicated = it }, onInlineElementClick = { unified = it }))
        controller.image(occurrence.image, occurrence.occurrenceId, null)
        assertNotNull(dedicated)
        assertNotNull(unified)
        assertEquals(InlineElementKinds.Image, assertNotNull(unified).kind)
        assertEquals("alt", assertNotNull(unified).text)
        assertEquals("pic.png", assertNotNull(unified).payload["url"])
        assertNotNull(assertNotNull(unified).image)
    }

    private fun compile(markdown: String) = DefaultRenderModelCompiler.compile(
        MarkdownParser().parse(markdown), RenderCompileEnvironment(),
    )
}
