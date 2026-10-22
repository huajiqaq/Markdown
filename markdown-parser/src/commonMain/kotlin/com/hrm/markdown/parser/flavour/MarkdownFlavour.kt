package com.hrm.markdown.parser.flavour

import com.hrm.markdown.parser.ast.immutableListCopy
import com.hrm.markdown.parser.block.postprocessors.AstPostProcessor
import com.hrm.markdown.parser.block.postprocessors.requireSingleScope
import com.hrm.markdown.parser.block.starters.BlockStarter

/**
 * 不可变的 Markdown 语法与 AST 转换配置。
 *
 * 使用 [copy] 派生方言，或使用 [with] 追加扩展，两者均保留原方言的全部行内语法设置。
 * 构造时对输入集合建立快照；扩展实例的优先级与解析行为必须保持稳定。
 *
 * ```kotlin
 * val flavour = CommonMarkFlavour.with(BlockPostProcessor { block ->
 *     if (block is Heading) block.level = minOf(block.level, 3)
 *     block
 * })
 * val document = MarkdownParser(flavour).parse(input)
 * ```
 */
class MarkdownFlavour(
    blockStarters: List<BlockStarter> = emptyList(),
    postProcessors: List<AstPostProcessor> = emptyList(),
    val enableGfmAutolinks: Boolean = true,
    val enableExtendedInline: Boolean = true,
    val enableStrikethrough: Boolean = true,
    val enableEmphasisCoalescing: Boolean = false,
) {
    val blockStarters: List<BlockStarter> = immutableListCopy(blockStarters)
    val postProcessors: List<AstPostProcessor> = immutableListCopy(postProcessors)

    init { this.postProcessors.forEach { it.requireSingleScope() } }

    // 配置自行持有解析计划，复用时无需全局注册或手动使缓存失效。
    internal val compiledPlan: FlavourCache by lazy { FlavourCache(this) }

    fun copy(
        blockStarters: List<BlockStarter> = this.blockStarters,
        postProcessors: List<AstPostProcessor> = this.postProcessors,
        enableGfmAutolinks: Boolean = this.enableGfmAutolinks,
        enableExtendedInline: Boolean = this.enableExtendedInline,
        enableStrikethrough: Boolean = this.enableStrikethrough,
        enableEmphasisCoalescing: Boolean = this.enableEmphasisCoalescing,
    ): MarkdownFlavour {
        if (blockStarters === this.blockStarters && postProcessors === this.postProcessors &&
            enableGfmAutolinks == this.enableGfmAutolinks && enableExtendedInline == this.enableExtendedInline &&
            enableStrikethrough == this.enableStrikethrough && enableEmphasisCoalescing == this.enableEmphasisCoalescing) return this
        return MarkdownFlavour(
            blockStarters, postProcessors, enableGfmAutolinks, enableExtendedInline,
            enableStrikethrough, enableEmphasisCoalescing,
        )
    }

    fun with(processor: AstPostProcessor, vararg additional: AstPostProcessor): MarkdownFlavour =
        copy(postProcessors = postProcessors + processor + additional)

    fun with(starter: BlockStarter, vararg additional: BlockStarter): MarkdownFlavour =
        copy(blockStarters = blockStarters + starter + additional)
}
