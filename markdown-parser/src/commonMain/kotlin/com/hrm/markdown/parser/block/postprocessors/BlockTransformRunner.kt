package com.hrm.markdown.parser.block.postprocessors

import com.hrm.markdown.parser.SourceRange
import com.hrm.markdown.parser.ast.*

/** 发布流程与独立完整解析对照共用的块转换约束。 */
internal object BlockTransformRunner {
    fun process(processor: BlockPostProcessor, document: Document, previousDocument: Document? = null) {
        val owner = if (previousDocument != null) {
            document.inheritMutationOwner(previousDocument)
            val publicationOwner = document.publicationMutationOwner
            // 先封存临时根，防止回调通过已处理输出的 parent 改写兄弟块。
            document.sealPublication(emptyList())
            // 输入可能被扩展提前捕获；根的惰性回调也不能修改既有输入。
            document.forEachMaterializedChild { it.seal(publicationOwner) }
            publicationOwner
        } else null
        // 先脱离父节点，防止回调通过输入块访问相邻块。
        val inputs = document.children.toList()
        document.clearChildren()
        for (input in inputs) {
            val output = if (previousDocument == null) {
                document.definitionContext.withReadOnlyDefinitions {
                    transform(processor, input, authorizePublishedInput = false)
                }
            } else {
                withoutDocumentMutation(previousDocument) {
                    document.definitionContext.withReadOnlyDefinitions {
                        transform(processor, input, authorizePublishedInput = true)
                    }
                }
            }
            if (output != null) {
                // 下一次回调开始前封存输出，防止捕获引用逃逸后继续写入。
                if (owner != null) output.seal(owner)
                document.appendChild(output)
            }
        }
    }

    private fun transform(processor: BlockPostProcessor, input: Node, authorizePublishedInput: Boolean): Node? {
        materializeUntrustedContent(input)
        if (hasDefinition(input)) return input
        return if (authorizePublishedInput) {
            withSubtreeMutation(input) { transformChecked(processor, input) }
        } else {
            transformChecked(processor, input)
        }
    }

    private fun transformChecked(processor: BlockPostProcessor, input: Node): Node? {
        val output = processor.processBlock(input) ?: return null
        // 在执行惰性回调、继承元数据或改写父节点前，拒绝捕获的已发布前缀。
        check(output.mutationOwner == null || output.hasLocalMutationPermit) {
            "BlockPostProcessor must return its input subtree or a new node, not another published block."
        }
        require(output !is Document) {
            "Document is only valid as an AST root; return a block or inline node from an extension."
        }
        materializeUntrustedContent(output)
        require(!hasDefinition(output)) {
            "BlockPostProcessor cannot add or replace reference definitions; use PostProcessor instead."
        }
        if (output !== input) {
            if (output.sourceRange == SourceRange.EMPTY) output.sourceRange = input.sourceRange
            if (output.lineRange.startLine == 0 && output.lineRange.endLine == 0) output.lineRange = input.lineRange
            if (output.contentHash == 0L) output.contentHash = input.contentHash
        }
        return output
    }

    private fun hasDefinition(node: Node): Boolean {
        if (node is LinkReferenceDefinition || node is FootnoteDefinition ||
            node is AbbreviationDefinition || node is BibliographyDefinition) return true
        if (node is ContainerNode) node.forEachMaterializedChild { if (hasDefinition(it)) return true }
        return false
    }
}
