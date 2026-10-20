package com.hrm.markdown.parser.incremental

import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.BlockParseResult

/** Mutable only while assembling the newly parsed interval, before it enters BlockIndex. */
internal class ParseRegion(
    val start: Int,
    var end: Int,
    val dependsOnFirstLine: Boolean,
    val nodes: MutableList<Node>,
    var hasDefinitions: Boolean,
    var hasNumberedReferences: Boolean,
) {
    val startsWithAttribute: Boolean get() = isAttributeParagraph(nodes.firstOrNull())
    var bibliographyEnd: Int? = null

    fun acceptBibliographyNodes(additions: List<Node>) {
        for (node in additions) {
            bibliographyEnd = when {
                node is FootnoteDefinition && node.label.equals("bibliography", true) -> node.lineRange.endLine
                node is Paragraph && bibliographyEnd != null && node.lineRange.startLine <= bibliographyEnd!! + 1 -> node.lineRange.endLine
                node is BlankLine -> bibliographyEnd
                else -> null
            }
        }
    }
}

internal fun parseRegions(
    result: BlockParseResult,
    mergeAttributes: Boolean,
    mergeBibliography: Boolean,
): MutableList<ParseRegion> {
    val children = result.document.children
    val firstLine = result.checkpoints.first().line
    val definitions = BooleanArray(result.endLine - firstLine)
    result.definitionLines.forEach { if (it >= firstLine && it < result.endLine) definitions[it - firstLine] = true }
    var definitionIndex = 0
    var referenceIndex = 0
    val regions = mutableListOf<ParseRegion>()
    for ((i, checkpoint) in result.checkpoints.withIndex()) {
        val next = result.checkpoints.getOrNull(i + 1)
        val end = next?.line ?: result.endLine
        if (checkpoint.line >= end) continue
        val nodes = children.subList(checkpoint.childIndex, next?.childIndex ?: children.size).toMutableList()
        var hasDefinition = false
        while (definitionIndex < end - firstLine) {
            hasDefinition = hasDefinition || definitions[definitionIndex]
            definitionIndex++
        }
        while (referenceIndex < result.numberedReferenceLines.size && result.numberedReferenceLines[referenceIndex] < checkpoint.line) referenceIndex++
        val hasReference = referenceIndex < result.numberedReferenceLines.size && result.numberedReferenceLines[referenceIndex] < end
        val region = ParseRegion(
            checkpoint.line, end, checkpoint.dependsOnLine, nodes,
            hasDefinition || nodes.any(::containsDefinition), hasReference,
        )
        val previous = regions.lastOrNull()
        val first = nodes.firstOrNull()
        val bibliographyContinues = mergeBibliography && previous?.bibliographyEnd != null &&
            first is Paragraph && first.lineRange.startLine <= previous.bibliographyEnd!! + 1
        if (previous != null && ((mergeAttributes && region.startsWithAttribute) || bibliographyContinues)) {
            previous.nodes.addAll(nodes)
            previous.end = end
            previous.hasDefinitions = previous.hasDefinitions || region.hasDefinitions
            previous.hasNumberedReferences = previous.hasNumberedReferences || region.hasNumberedReferences
            previous.acceptBibliographyNodes(nodes)
        } else {
            if (mergeBibliography) region.acceptBibliographyNodes(nodes)
            regions.add(region)
        }
    }
    return regions
}

internal fun containsDefinition(node: Node): Boolean = when (node) {
    is LinkReferenceDefinition, is FootnoteDefinition, is AbbreviationDefinition, is BibliographyDefinition -> true
    is ContainerNode -> node.materializedChildrenSnapshot().any(::containsDefinition)
    else -> false
}

internal fun isAttributeParagraph(node: Node?): Boolean =
    node is Paragraph && ATTRIBUTE_PARAGRAPH.matches(node.rawContent.orEmpty())

private val ATTRIBUTE_PARAGRAPH = Regex("^\\s*\\{[^}]+\\}\\s*$")
