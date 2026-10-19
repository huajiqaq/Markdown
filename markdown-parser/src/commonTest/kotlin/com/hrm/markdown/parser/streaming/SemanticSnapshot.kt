package com.hrm.markdown.parser.streaming

import com.hrm.markdown.parser.ast.*

/** Value-only AST oracle: excludes coordinates, hashes, parent pointers and lazy-parser state. */
internal data class SemanticSnapshot(
    val kind: String,
    val literal: String?,
    val properties: List<Any?>,
    val children: List<SemanticSnapshot>,
)

internal fun Node.semanticSnapshot(): SemanticSnapshot {
    // Materialize before reading document maps: inline parsing can resolve references and indices.
    val children = if (this is ContainerNode) children.map { it.semanticSnapshot() } else emptyList()
    val properties: List<Any?> = when (this) {
        is Document -> listOf(
            linkDefinitions.mapValues { it.value.semanticSnapshot() },
            footnoteDefinitions.mapValues { it.value.semanticSnapshot() },
            abbreviationDefinitions.mapValues { it.value.semanticSnapshot() },
        )
        is Heading -> listOf(level, customId, autoId, blockAttributes.toMap())
        is SetextHeading -> listOf(level, autoId, blockAttributes.toMap())
        is Paragraph -> listOf(blockAttributes.toMap())
        is ThematicBreak -> listOf(char)
        is FencedCodeBlock -> listOf(info, language, fenceChar, fenceLength, fenceIndent,
            attributes.toMap(), highlightLines.toList(), showLineNumbers, startLineNumber)
        is BlockQuote -> listOf(blockAttributes.toMap())
        is ListBlock -> listOf(ordered, startNumber, bulletChar, delimiter, tight, blockAttributes.toMap())
        is ListItem -> listOf(taskListItem, checked)
        is HtmlBlock -> listOf(htmlType)
        is LinkReferenceDefinition -> listOf(label, destination, title)
        is Table -> listOf(columnAlignments.toList(), blockAttributes.toMap())
        is TableCell -> listOf(alignment, isHeader)
        is FootnoteDefinition -> listOf(label, index)
        is Admonition -> listOf(type, title)
        is FrontMatter -> listOf(format)
        is TocPlaceholder -> listOf(minDepth, maxDepth, excludeIds.toList(), order)
        is AbbreviationDefinition -> listOf(abbreviation, fullText)
        is CustomContainer -> listOf(type, title, cssClasses.toList(), cssId)
        is DiagramBlock -> listOf(diagramType)
        is ColumnItem -> listOf(width)
        is DirectiveBlock -> listOf(tagName, args.toMap())
        is TabItem -> listOf(title)
        is BibliographyDefinition -> listOf(entries.mapValues { (_, entry) -> listOf(entry.key, entry.content) })
        is Figure -> listOf(imageUrl, caption, imageWidth, imageHeight, attributes.toMap())
        is Emphasis -> listOf(delimiter)
        is StrongEmphasis -> listOf(delimiter)
        is Link -> listOf(destination, title, attributes.toMap())
        is Image -> listOf(destination, title, imageWidth, imageHeight, attributes.toMap())
        is Autolink -> listOf(destination, isEmail, rawText)
        is HtmlEntity -> listOf(resolved)
        is FootnoteReference -> listOf(label, index)
        is Emoji -> listOf(shortcode, unicode)
        is StyledText -> listOf(attributes.toMap())
        is Abbreviation -> listOf(abbreviation, fullText)
        is DirectiveInline -> listOf(tagName, args.toMap())
        is CitationReference -> listOf(key)
        is WikiLink -> listOf(target, label)
        is RubyText -> listOf(base, annotation)
        is IndentedCodeBlock, is MathBlock, is BlankLine, is TableHead, is TableBody,
        is TableRow, is DefinitionList, is DefinitionTerm, is DefinitionDescription,
        is ColumnsLayout, is PageBreak, is TabBlock, is Text, is SoftLineBreak,
        is HardLineBreak, is Strikethrough, is InlineCode, is InlineHtml, is EscapedChar,
        is InlineMath, is Highlight, is Superscript, is Subscript, is InsertedText,
        is KeyboardInput, is Spoiler -> emptyList()
    }
    return SemanticSnapshot(this::class.simpleName.orEmpty(), (this as? LeafNode)?.literal, properties, children)
}
