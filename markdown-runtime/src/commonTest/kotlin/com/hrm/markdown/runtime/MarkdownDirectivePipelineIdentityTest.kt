package com.hrm.markdown.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class MarkdownDirectivePipelineIdentityTest {
    @Test
    fun should_share_empty_pipeline_when_plugins_only_customize_rendering() {
        val registry = MarkdownDirectiveRegistry(listOf(plugin("first"), plugin("second")))
        assertSame(MarkdownDirectivePipeline.Empty, registry.inputPipeline)
        assertSame(MarkdownDirectivePipeline.Empty, MarkdownDirectiveRegistry.Empty.inputPipeline)
        assertEquals("source", registry.inputPipeline.transform("source").markdown)
    }

    @Test
    fun should_preserve_source_processing_identity_when_only_renderer_callbacks_change() {
        val transformer = Transformer("source")
        val before = MarkdownDirectiveRegistry(listOf(plugin("plugin", transformer)))
        val after = MarkdownDirectiveRegistry(listOf(plugin("replacement", transformer)))

        assertNotSame(before.findBlockDirectiveRenderer("custom"), after.findBlockDirectiveRenderer("custom"))
        assertNotSame(before.findHtmlDirectiveFallback("custom"), after.findHtmlDirectiveFallback("custom"))
        assertEquals(before.inputPipeline, after.inputPipeline)
        assertEquals(before.inputPipeline.hashCode(), after.inputPipeline.hashCode())
    }

    @Test
    fun should_invalidate_source_processing_when_equal_transformer_instances_are_replaced_or_reordered() {
        val first = Transformer("same-id")
        val replacement = Transformer("same-id")
        // 用户定义的值相等，不代表持有状态的转换回调可以相互替换。
        assertEquals(first, replacement)
        assertNotEquals(MarkdownDirectivePipeline(listOf(first)), MarkdownDirectivePipeline(listOf(replacement)))
        assertNotEquals(
            MarkdownDirectivePipeline(listOf(first, replacement)),
            MarkdownDirectivePipeline(listOf(replacement, first)),
        )
    }

    @Test
    fun should_snapshot_chain_and_streaming_contract_when_caller_lists_or_declarations_change() {
        val transformer = Transformer("first")
        val transformers = mutableListOf<MarkdownInputTransformer>(transformer)
        val original = MarkdownDirectivePipeline(transformers)
        val originalHash = original.hashCode()
        transformers.clear()
        transformers += Transformer("replacement")
        transformer.support = MarkdownTransformerStreamingSupport.Unsupported

        assertEquals("first:source", original.transform("source").markdown)
        assertEquals(MarkdownTransformerStreamingSupport.AppendSafe, original.streamingSupport)
        assertEquals(originalHash, original.hashCode())
        val updatedContract = MarkdownDirectivePipeline(listOf(transformer))
        assertFalse(updatedContract.supportsStreaming)
        assertNotEquals(original, updatedContract)
    }

    @Test
    fun should_order_stages_by_plugin_priority_then_id_and_preserve_each_plugin_order() {
        val late = plugin("late", Transformer("last"), priority = 1)
        val z = plugin("z", Transformer("z"))
        val a = object : MarkdownDirectivePlugin {
            override val id = "a"
            override val inputTransformers = listOf(Transformer("a1"), Transformer("a2"))
        }
        val pipeline = MarkdownDirectiveRegistry(listOf(late, z, a)).inputPipeline
        assertEquals("last:z:a2:a1:source", pipeline.transform("source").markdown)
    }

    private fun plugin(
        id: String,
        transformer: MarkdownInputTransformer? = null,
        priority: Int = 0,
    ): MarkdownDirectivePlugin = object : MarkdownDirectivePlugin {
        override val id = id
        override val priority = priority
        override val inputTransformers = listOfNotNull(transformer)
        override val blockDirectiveRenderers: Map<String, MarkdownBlockDirectiveRenderer> = mapOf("custom" to { check(id.isNotEmpty()) })
        override val htmlDirectiveFallbacks = mapOf("custom" to object : HtmlDirectiveFallback {
            override fun render(snapshot: DirectiveBlockSnapshot): String = id
        })
    }

    private class Transformer(override val id: String) : MarkdownInputTransformer {
        var support = MarkdownTransformerStreamingSupport.AppendSafe
        override val streamingSupport get() = support
        override fun transform(input: String) = MarkdownTransformResult("$id:$input")
        override fun equals(other: Any?): Boolean = other is Transformer && id == other.id
        override fun hashCode(): Int = id.hashCode()
    }
}
