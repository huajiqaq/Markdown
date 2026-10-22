package com.hrm.markdown.runtime

/**
 * 按执行顺序保存输入转换器的不可变快照，与指令渲染器相互独立。
 *
 * 替换渲染回调不会改变转换链身份；替换、重排转换器或修改流式声明会改变身份。
 * 转换器自行持有其行为；行为改变时，调用方需要提供新的转换器实例。
 */
class MarkdownDirectivePipeline(transformers: List<MarkdownInputTransformer>) {
    private class Stage(val transformer: MarkdownInputTransformer) {
        val id = transformer.id
        val streamingSupport = transformer.streamingSupport
    }

    private val stages = transformers.map(::Stage)
    private val identityHashCode = stages.fold(1) { hash, stage ->
        31 * (31 * hash + stage.id.hashCode()) + stage.streamingSupport.hashCode()
    }

    val streamingSupport: MarkdownTransformerStreamingSupport = when {
        stages.any { it.streamingSupport == MarkdownTransformerStreamingSupport.Unsupported } ->
            MarkdownTransformerStreamingSupport.Unsupported
        stages.all { it.streamingSupport == MarkdownTransformerStreamingSupport.AppendSafe } ->
            MarkdownTransformerStreamingSupport.AppendSafe
        else -> MarkdownTransformerStreamingSupport.RestartOnRewrite
    }
    val supportsStreaming: Boolean get() = streamingSupport != MarkdownTransformerStreamingSupport.Unsupported
    val supportsStreamingFastPath: Boolean get() = streamingSupport == MarkdownTransformerStreamingSupport.AppendSafe
    val hasTransformers: Boolean get() = stages.isNotEmpty()

    fun transform(input: String): MarkdownTransformResult {
        var markdown = input
        var sourceMap: MarkdownSourceMap = MarkdownSourceMap.Identity
        for (stage in stages) {
            val result = stage.transformer.transform(markdown)
            markdown = result.markdown
            sourceMap = composeSourceMap(sourceMap, result.sourceMap)
        }
        return MarkdownTransformResult(markdown, sourceMap)
    }

    override fun equals(other: Any?): Boolean = this === other ||
        other is MarkdownDirectivePipeline && stages.size == other.stages.size &&
        stages.indices.all { index ->
            val left = stages[index]
            val right = other.stages[index]
            left.transformer === right.transformer && left.id == right.id && left.streamingSupport == right.streamingSupport
        }

    // equals 检查实例引用；哈希仅依赖声明快照，避免受转换器自定义或可变的 equals/hashCode 影响。
    override fun hashCode(): Int = identityHashCode

    companion object {
        val Empty = MarkdownDirectivePipeline(emptyList())
    }
}
