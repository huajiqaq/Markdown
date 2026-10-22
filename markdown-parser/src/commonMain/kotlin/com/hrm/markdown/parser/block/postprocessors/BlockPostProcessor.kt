package com.hrm.markdown.parser.block.postprocessors

import com.hrm.markdown.parser.LineRange
import com.hrm.markdown.parser.SourceRange
import com.hrm.markdown.parser.ast.*

/**
 * Transforms independent Markdown blocks, including their complete child trees.
 *
 * Return the supplied block after editing it, a replacement node, or null to remove it.
 * The parser automatically revisits changed blocks. A callback must produce its result
 * from the supplied block; document-wide transformations belong in [PostProcessor].
 * Published nodes remain read-only. Replacement nodes inherit missing source coordinates
 * from the supplied block, so callers do not need to maintain parser metadata.
 * Blocks containing reference definitions are left unchanged. To transform definitions,
 * use [PostProcessor] instead.
 */
fun interface BlockPostProcessor : PostProcessor {
    override val priority: Int get() = 100

    fun processBlock(block: Node): Node?

    override fun process(document: Document) {
        transformDocumentBlocks(document, ::processBlock)
    }
}

/** Detach inputs so a block callback cannot reach other blocks through its parent. */
internal fun transformDocumentBlocks(document: Document, transform: (Node) -> Node?) {
    val inputs = document.children.toList()
    document.clearChildren()
    inputs.forEach { original ->
        materializeUntrustedContent(original)
        val definitions = definitionNodes(original)
        if (definitions.isNotEmpty()) {
            document.appendChild(original)
            return@forEach
        }
        val output = transform(original)
        require(output != null || definitions.isEmpty()) {
            "BlockPostProcessor cannot remove reference definitions; use PostProcessor instead."
        }
        if (output == null) return@forEach
        materializeUntrustedContent(output)
        require(definitionNodes(output) == definitions) {
            "BlockPostProcessor cannot add or replace reference definitions; use PostProcessor instead."
        }
        if (output !== original) {
            if (output.sourceRange == SourceRange.EMPTY) output.sourceRange = original.sourceRange
            if (output.lineRange == LineRange(0, 0)) output.lineRange = original.lineRange
            if (output.contentHash == 0L) output.contentHash = original.contentHash
        }
        document.appendChild(output)
    }
}

private fun definitionNodes(root: Node): Set<Node> = buildSet {
    fun visit(node: Node) {
        if (node is LinkReferenceDefinition || node is FootnoteDefinition ||
            node is AbbreviationDefinition || node is BibliographyDefinition) add(node)
        if (node is ContainerNode) node.forEachMaterializedChild(::visit)
    }
    visit(root)
}
