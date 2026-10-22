package com.hrm.markdown.parser.flavour

import com.hrm.markdown.parser.ast.immutableListCopy
import com.hrm.markdown.parser.block.postprocessors.PostProcessorRegistry
import com.hrm.markdown.parser.block.starters.BlockStarterRegistry

/** 方言自行持有的不可变执行计划，由使用该方言的解析器共享。 */
internal class FlavourCache(flavour: MarkdownFlavour) {
    val blockStarters = flavour.blockStarters
    val postProcessors = flavour.postProcessors
    val sortedPostProcessors = immutableListCopy(postProcessors.sortedBy { it.priority })

    val blockStarterRegistry = newBlockStarterRegistry().apply { freeze() }

    fun newBlockStarterRegistry(): BlockStarterRegistry = BlockStarterRegistry().apply {
        blockStarters.forEach(::register)
    }

    fun newPostProcessorRegistry(): PostProcessorRegistry =
        PostProcessorRegistry(sortedPostProcessors, alreadySorted = true)

    companion object {
        fun of(flavour: MarkdownFlavour): FlavourCache = flavour.compiledPlan
    }
}
