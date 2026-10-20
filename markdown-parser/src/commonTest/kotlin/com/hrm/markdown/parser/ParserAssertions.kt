package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.ContainerNode
import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.ast.Node
import com.hrm.markdown.parser.block.BlockParser
import com.hrm.markdown.parser.block.postprocessors.PostProcessorRegistry
import com.hrm.markdown.parser.block.starters.BlockStarterRegistry
import com.hrm.markdown.parser.core.SourceText
import com.hrm.markdown.parser.inline.InlineParser
import com.hrm.markdown.parser.lint.LintingPostProcessor
import com.hrm.markdown.parser.streaming.semanticSnapshot
import kotlin.test.assertEquals

// Keep the text model independent from the production SourceText normalizer.
internal fun normalizedTestInput(input: String): String =
    input.replace("\r\n", "\n").replace('\r', '\n').replace('\u0000', '\uFFFD')

/** Compare source, semantics, diagnostics and every materialized node's coordinates. */
internal fun assertEditedDocument(
    parser: MarkdownParser,
    expectedSource: String,
    actual: Document = parser.document,
    message: String = "",
) {
    assertEquals(expectedSource, parser.currentText(), "$message currentText")
    assertEquals(expectedSource, parser.sourceText.content, "$message sourceText")
    val expected = referenceDocument(parser, expectedSource)
    assertEquals(expected.semanticSnapshot(), actual.semanticSnapshot(), "$message AST")
    assertEquals(expected.diagnostics.diagnostics, actual.diagnostics.diagnostics, "$message diagnostics")
    assertNodeCoordinatesEqual(expected, actual, message)
}

internal fun assertNodeCoordinatesEqual(expected: Node, actual: Node, message: String = "") {
    val context = "$message ${expected::class.simpleName}"
    assertEquals(expected::class, actual::class, context)
    assertEquals(expected.lineRange, actual.lineRange, "$context line range")
    assertEquals(expected.sourceRange, actual.sourceRange, "$context source range")
    if (expected is ContainerNode && actual is ContainerNode) {
        assertEquals(expected.children.size, actual.children.size, "$context child count")
        expected.children.zip(actual.children).forEach { (expectedChild, actualChild) ->
            assertNodeCoordinatesEqual(expectedChild, actualChild, message)
        }
    }
}

/** Independent full pipeline: no IncrementalEngine, checkpoints, region grouping or reuse. */
internal fun referenceDocument(options: MarkdownParser, text: String): Document {
    val flavour = options.flavour
    val registry = BlockStarterRegistry().apply { flavour.blockStarters.forEach(::register) }
    val doc = BlockParser(SourceText.of(text), registry) { root ->
        InlineParser(root, options.customEmojiMap, options.enableAsciiEmoticons,
            flavour.enableGfmAutolinks, flavour.enableExtendedInline,
            flavour.enableEmphasisCoalescing, flavour.enableStrikethrough)
    }.parse()
    val lint = if (options.enableLinting) LintingPostProcessor() else null
    PostProcessorRegistry().apply {
        flavour.postProcessors.forEach(::register)
        lint?.let(::register)
    }.processAll(doc)
    lint?.let { doc.diagnostics = it.result }
    return doc
}
