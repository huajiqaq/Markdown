package com.hrm.markdown.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkdownDirectivePipelineTest {
    @Test
    fun should_transformInPriorityOrderAndComposeMappings_when_pipelineHasMultipleStages() {
        val calls = mutableListOf<String>()
        val strip = transformerPlugin("strip", priority = 0) { input ->
            calls += "strip:$input"
            val output = input.removePrefix("raw:")
            MarkdownTransformResult(output, MarkdownSourceMap.Segmented(listOf(
                MarkdownSourceMap.Segmented.Segment(0, output.length, 4, input.length),
            )))
        }
        val trim = transformerPlugin("trim", priority = 10) { input ->
            calls += "trim:$input"
            val output = input.trim()
            MarkdownTransformResult(output, MarkdownSourceMap.Segmented(listOf(
                MarkdownSourceMap.Segmented.Segment(0, output.length, 2, input.length - 2),
            )))
        }
        val result = MarkdownDirectivePipeline(MarkdownDirectiveRegistry(listOf(trim, strip)))
            .transform("raw:  **go**  ")

        assertEquals(listOf("strip:raw:  **go**  ", "trim:  **go**  "), calls)
        assertEquals("**go**", result.markdown)
        for (offset in 0..result.markdown.length) {
            assertEquals(offset + 6, result.sourceMap.mapOutputOffset(offset), "output offset $offset")
        }
    }

    @Test
    fun later_plugin_should_override_same_tag_renderer() {
        val first = RecordingDirectivePlugin("first")
        val second = RecordingDirectivePlugin("second", priority = 1)
        val registry = MarkdownDirectiveRegistry(listOf(second, first))

        assertSame(second.renderer, registry.findBlockDirectiveRenderer("video"))
    }

    @Test
    fun later_plugin_should_override_same_inline_renderer() {
        val first = RecordingDirectivePlugin("first")
        val second = RecordingDirectivePlugin("second", priority = 1)
        val registry = MarkdownDirectiveRegistry(listOf(second, first))

        assertSame(second.inlineRenderer, registry.findInlineDirectiveRenderer("badge"))
    }

    @Test
    fun should_selectStrongestRestriction_when_transformerStreamingModesAreMixed() {
        val safe = MarkdownTransformerStreamingSupport.AppendSafe
        val restart = MarkdownTransformerStreamingSupport.RestartOnRewrite
        val unsupported = MarkdownTransformerStreamingSupport.Unsupported
        for (first in listOf(safe, restart, unsupported)) {
            for (second in listOf(safe, restart, unsupported)) {
                val registry = MarkdownDirectiveRegistry(listOf(
                    transformerPlugin("first", support = first),
                    transformerPlugin("second", support = second),
                ))
                val expected = when {
                    first == unsupported || second == unsupported -> unsupported
                    first == restart || second == restart -> restart
                    else -> safe
                }
                val context = "$first + $second"
                assertEquals(expected, registry.streamingSupport, context)
                assertEquals(expected != unsupported, registry.supportsStreaming, context)
                assertEquals(expected == safe, registry.supportsStreamingFastPath, context)
            }
        }
        assertEquals(safe, MarkdownDirectiveRegistry.Empty.streamingSupport)
        assertTrue(MarkdownDirectiveRegistry.Empty.supportsStreaming)
        assertTrue(MarkdownDirectiveRegistry.Empty.supportsStreamingFastPath)
    }

    @Test
    fun segmented_source_map_should_use_half_open_boundaries_and_map_eof() {
        val sourceMap = MarkdownSourceMap.Segmented(
            listOf(
                MarkdownSourceMap.Segmented.Segment(0, 5, 10, 15),
                MarkdownSourceMap.Segmented.Segment(5, 10, 20, 30),
            )
        )

        assertEquals(20, sourceMap.mapOutputOffset(5))
        assertEquals(30, sourceMap.mapOutputOffset(10))
    }

    @Test
    fun should_mapGapsAndCollapsedRanges_when_segmentsDoNotCoverEveryOffset() {
        val sourceMap = MarkdownSourceMap.Segmented(listOf(
            MarkdownSourceMap.Segmented.Segment(0, 2, 10, 14),
            MarkdownSourceMap.Segmented.Segment(5, 7, 20, 20),
        ))
        val expected = listOf(10, 12, 2, 3, 4, 20, 20, 20, 8)
        expected.forEachIndexed { offset, input -> assertEquals(input, sourceMap.mapOutputOffset(offset)) }
        assertFailsWith<IllegalArgumentException> { sourceMap.mapOutputOffset(-1) }
    }

    @Test
    fun should_rejectInvalidSegments_when_offsetsAreNegativeReversedOrOverlapping() {
        val valid = MarkdownSourceMap.Segmented.Segment(0, 5, 0, 5)
        val invalid = listOf(
            listOf(valid.copy(outputStart = -1)),
            listOf(valid.copy(inputStart = -1)),
            listOf(valid.copy(outputEnd = -1)),
            listOf(valid.copy(inputEnd = -1)),
            listOf(valid, valid.copy(outputStart = 4, outputEnd = 8)),
            listOf(valid.copy(outputStart = 5, outputEnd = 8), valid),
        )
        for (segments in invalid) {
            assertFailsWith<IllegalArgumentException>(segments.toString()) { MarkdownSourceMap.Segmented(segments) }
        }
    }

    @Test
    fun should_preserveMappingSnapshot_when_originalSegmentListIsChanged() {
        val segments = mutableListOf(MarkdownSourceMap.Segmented.Segment(0, 5, 10, 15))
        val sourceMap = MarkdownSourceMap.Segmented(segments)
        segments.clear()
        segments += MarkdownSourceMap.Segmented.Segment(0, 5, 100, 105)
        assertEquals(12, sourceMap.mapOutputOffset(2))
        assertEquals(15, sourceMap.mapOutputOffset(5))
    }

    private fun transformerPlugin(
        id: String,
        priority: Int = 0,
        support: MarkdownTransformerStreamingSupport = MarkdownTransformerStreamingSupport.RestartOnRewrite,
        transform: (String) -> MarkdownTransformResult = { MarkdownTransformResult(it) },
    ) = object : MarkdownDirectivePlugin {
        override val id = id
        override val priority = priority
        override val inputTransformers = listOf(object : MarkdownInputTransformer {
            override val id = "$id-transformer"
            override val streamingSupport = support
            override fun transform(input: String) = transform(input)
        })
    }

    private class RecordingDirectivePlugin(
        override val id: String,
        override val priority: Int = 0,
    ) : MarkdownDirectivePlugin {
        val renderer: MarkdownBlockDirectiveRenderer = { }
        val inlineRenderer: MarkdownInlineDirectiveRenderer = { }
        override val blockDirectiveRenderers: Map<String, MarkdownBlockDirectiveRenderer> = mapOf(
            "video" to renderer,
        )
        override val inlineDirectiveRenderers: Map<String, MarkdownInlineDirectiveRenderer> = mapOf(
            "badge" to inlineRenderer,
        )
    }
}
