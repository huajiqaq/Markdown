package com.hrm.markdown.parser.incremental

import com.hrm.markdown.parser.LineRange
import com.hrm.markdown.parser.SourcePosition
import com.hrm.markdown.parser.SourceRange
import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.BlockParser
import com.hrm.markdown.parser.block.postprocessors.*
import com.hrm.markdown.parser.core.SourceText
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.FlavourCache
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.hrm.markdown.parser.inline.InlineParser
import com.hrm.markdown.parser.lint.LintingPostProcessor
import com.hrm.markdown.parser.streaming.InlineAutoCloser

/**
 * One checkpoint-based update algorithm for edits and streaming appends.
 *
 * A reusable suffix is an entire sequence of source regions whose first line is unchanged
 * and whose old AND new parser states are at the root. The edit itself proves exact suffix
 * equality; node hashes, transformed AST ranges and delimiter guesses are not correctness
 * evidence. Definitions invalidate global semantics; unknown postprocessors use full parsing.
 *
 * Text/index updates share persistent trees. Maintenance costs O(I + A + R + log L + log B),
 * plus S materialized suffix nodes needing coordinate shifts and G
 * invalidated global work (heading IDs/lint/plugins). R may extend to EOF for open containers.
 */
class IncrementalEngine(
    private val flavour: MarkdownFlavour = ExtendedFlavour,
    private val customEmojiMap: Map<String, String> = emptyMap(),
    private val enableAsciiEmoticons: Boolean = false,
    postProcessors: PostProcessorRegistry? = null,
    private val lintingProcessor: LintingPostProcessor? = null,
    private val appendCoalesceThreshold: Int = 0,
) {
    private val flavourCache = FlavourCache.of(flavour)
    private val postProcessors = postProcessors ?: flavourCache.newPostProcessorRegistry().apply {
        lintingProcessor?.let(::register)
    }
    private var _document = Document()
    private var owner = DocumentOwner(_document)
    private var source = SourceText.of("")
    private var parsedSource = source
    private var blocks = BlockIndex.empty()
    private var _isStreaming = false
    private var hasDisplayRepairs = false
    private var previousRawChunkEndedWithCarriageReturn = false
    private var processedWith: List<PostProcessor>? = null

    internal data class UpdateStats(
        val parsedLines: Int,
        val reusedPrefixRegions: Int = 0,
        val reusedSuffixRegions: Int = 0,
        val fullParseReason: String? = null,
    )
    internal var lastUpdate = UpdateStats(0)
        private set

    val document: Document get() = _document
    val sourceText: SourceText get() = source
    val isStreaming: Boolean get() = _isStreaming
    fun currentText(): String = source.content

    fun fullParse(input: String): Document {
        source = SourceText.of(input)
        _isStreaming = false
        previousRawChunkEndedWithCarriageReturn = false
        return rebuild("full input")
    }

    fun beginStream() {
        source = SourceText.of("")
        parsedSource = source
        blocks = BlockIndex.empty()
        _document = Document()
        owner = DocumentOwner(_document)
        processedWith = null
        _isStreaming = true
        hasDisplayRepairs = false
        previousRawChunkEndedWithCarriageReturn = false
        lastUpdate = UpdateStats(0)
    }

    fun append(chunk: String): Document {
        if (chunk.isEmpty()) return _document
        val normalized = normalizeStreamingChunk(chunk)
        if (normalized.isEmpty()) return _document
        source = SourceText.applyEditFast(source, source.length, 0, normalized)
        if (appendCoalesceThreshold > 0 && '\n' !in normalized &&
            source.length - parsedSource.length < appendCoalesceThreshold) return _document
        return update(parsedSource.length, 0, source.length - parsedSource.length)
    }

    fun endStream(): Document {
        _isStreaming = false
        previousRawChunkEndedWithCarriageReturn = false
        // Remove display-only repairs by reparsing the same unstable interval, not the prefix.
        return update(parsedSource.length, 0, source.length - parsedSource.length, forceTail = true)
    }

    fun abort(): Document {
        if (source !== parsedSource) update(parsedSource.length, 0, source.length - parsedSource.length)
        _isStreaming = false
        previousRawChunkEndedWithCarriageReturn = false
        return _document
    }

    fun applyEdit(edit: EditOperation): Document {
        // Validate against live source before any flush or display-state transition.
        val offset: Int
        val deleted: Int
        val inserted: String
        when (edit) {
            is EditOperation.Insert -> { offset = edit.offset; deleted = 0; inserted = SourceText.normalize(edit.text) }
            is EditOperation.Delete -> { offset = edit.offset; deleted = edit.length; inserted = "" }
            is EditOperation.Replace -> { offset = edit.offset; deleted = edit.length; inserted = SourceText.normalize(edit.newText) }
            is EditOperation.Append -> { offset = source.length; deleted = 0; inserted = SourceText.normalize(edit.text) }
        }
        val edited = SourceText.applyEditFast(source, offset, deleted, inserted)
        if (!_isStreaming && hasDisplayRepairs) update(parsedSource.length, 0, 0, forceTail = true)
        if (source !== parsedSource) update(parsedSource.length, 0, source.length - parsedSource.length)
        if (edited === source) { lastUpdate = UpdateStats(0); return _document }
        source = edited
        if (!_isStreaming) previousRawChunkEndedWithCarriageReturn = false
        return update(offset, deleted, inserted.length)
    }

    private fun normalizeStreamingChunk(chunk: String): String {
        val skipLf = previousRawChunkEndedWithCarriageReturn && chunk.first() == '\n'
        previousRawChunkEndedWithCarriageReturn = chunk.last() == '\r'
        val normalized = SourceText.normalize(chunk)
        return if (skipLf) normalized.drop(1) else normalized
    }

    private fun newBlockParser() = BlockParser(
        source, flavourCache.blockStarterRegistry,
        // Lazy inline closures retain only definitions, never an obsolete root's full child list.
        inlineParserFactory = { doc -> inlineParser(Document(doc.definitionContext)) },
    )

    private fun inlineParser(doc: Document) = InlineParser(
        doc, customEmojiMap, enableAsciiEmoticons, flavour.enableGfmAutolinks,
        flavour.enableExtendedInline, flavour.enableEmphasisCoalescing, flavour.enableStrikethrough,
    )

    private data class Pipeline(val processors: List<PostProcessor>) {
        val hasAttributes = processors.any { it is BlockAttributeProcessor }
        val hasBibliography = processors.any { it is BibliographyProcessor }
        val hasHeadings = processors.any { it is HeadingIdProcessor }
        val supportsIncremental = processors.all {
            it is HeadingIdProcessor || it is BlockAttributeProcessor || it is BibliographyProcessor ||
                it is AbbreviationProcessor || it is DiagramProcessor || it is ColumnsLayoutProcessor ||
                it is HtmlFilterProcessor || it is FigureProcessor || it is LintingPostProcessor
        }
        fun processLocal(document: Document) {
            processors.forEach {
                if (it !is HeadingIdProcessor && it !is LintingPostProcessor) it.process(document)
            }
        }
    }

    private fun update(offset: Int, deleted: Int, inserted: Int, forceTail: Boolean = false): Document {
        val pipeline = Pipeline(postProcessors.sortedSnapshot())
        if (blocks.size == 0) return rebuild("initial parse", pipeline)
        if (processedWith !== pipeline.processors) return rebuild("pipeline changed", pipeline)
        if (!pipeline.supportsIncremental) return rebuild("document postprocessor", pipeline)
        val old = parsedSource
        val editLine = old.lineAtOffset(offset)
        var first = blocks.indexAtLine(editLine)
        if (first > 0 && editLine == blocks.startLine(first) && blocks[first].dependsOnFirstLine) first--
        // Bibliography continuation paragraphs can attach backwards across a root checkpoint.
        if (pipeline.hasBibliography && first > 0 && blocks[first - 1].bibliographyTailGap?.let { it <= 1 } == true) first--
        val lineDelta = source.lineCount - old.lineCount
        val oldEnd = offset + deleted
        val newEnd = offset + inserted
        val oldEndLine = old.lineAtOffset(oldEnd)
        val newEndLine = source.lineAtOffset(newEnd)
        val suffixLine = if (oldEnd == old.lineStart(oldEndLine) && newEnd == source.lineStart(newEndLine))
            oldEndLine else oldEndLine + 1

        var suffixIndex = blocks.size
        fun parseReplacement() = run {
            val checkpoints = if (forceTail) emptyList<BlockIndex.Checkpoint>().iterator()
                else blocks.checkpointsFrom(suffixLine, first + 1)
            var candidate = if (checkpoints.hasNext()) checkpoints.next() else null
            newBlockParser().parseWithCheckpoints(blocks.startLine(first), source.lineCount) { line ->
                val oldLine = line - lineDelta
                while (candidate != null && candidate!!.line < oldLine) {
                    candidate = if (checkpoints.hasNext()) checkpoints.next() else null
                }
                if (candidate?.line == oldLine && !candidate!!.region.startsWithAttribute) {
                    suffixIndex = candidate!!.index
                    true
                } else false
            }
        }
        var parsed = parseReplacement()
        var regions = parseRegions(parsed, pipeline.hasAttributes, pipeline.hasBibliography)
        // A paragraph changed into an attribute block can newly depend on the previous region.
        if (pipeline.hasAttributes && first > 0 && regions.firstOrNull()?.startsWithAttribute == true) {
            first--
            suffixIndex = blocks.size
            parsed = parseReplacement()
            regions = parseRegions(parsed, pipeline.hasAttributes, pipeline.hasBibliography)
        }
        val parsedStart = blocks.startLine(first)
        val (prefix, fromFirst) = blocks.splitAt(first)
        val (replaced, suffix) = fromFirst.splitAt(suffixIndex - first)
        if (replaced.hasDefinitions || regions.any { it.hasDefinitions }) return rebuild("definitions changed", pipeline)
        if (_document.footnoteDefinitions.isNotEmpty() &&
            (replaced.hasNumberedReferences || regions.any { it.hasNumberedReferences })) return rebuild("footnote order changed", pipeline)

        val context = parsed.document
        context.definitionContext.inherit(_document.definitionContext)
        if (_isStreaming && suffix.size == 0) repairTail(regions, context)
        val oldTail = if (_isStreaming) replaced.asNodeList() else emptyList()
        val prepared = prepareRegions(regions, context, pipeline, oldTail)
        // The entire suffix is retained. There is no second filtering pass that can drop nodes.
        val offsetDelta = source.length - old.length
        if (lineDelta != 0 || (offsetDelta != 0 && suffix.hasSourceRanges)) suffix.forEachRegion(onlyPositioned = lineDelta == 0) { region ->
            region.nodes.forEach { it.shiftSubtreeCoordinates(lineDelta, offsetDelta) }
        }
        val replacement = BlockIndex.of(prepared.map { it.first })
        val combined = prefix + replacement + suffix
        if (pipeline.hasHeadings && (replaced.headingCount != 0 || replacement.headingCount != 0)) reindexHeadings(combined)
        prepared.forEach { (_, doc) -> pipeline.processLocal(doc) }
        // Local processors can replace/remove nodes; update only newly parsed leaves.
        val processed = BlockIndex.of(prepared.map { (region, doc) -> region.copy(nodes = doc.children.toList(), hasSourceRanges = doc.children.any(::hasSourceRanges)) })
        blocks = prefix + processed + suffix
        // Removed nodes belong to the old root, not to the owner's next published root.
        // Reused nodes in the replacement are rebound by publish below.
        replaced.forEachRegion { it.nodes.forEach { node -> node.parent = _document } }
        publish(context, pipeline, processed)
        lastUpdate = UpdateStats(parsed.endLine - parsedStart, prefix.size, suffix.size)
        return _document
    }

    private fun rebuild(reason: String, pipeline: Pipeline = Pipeline(postProcessors.sortedSnapshot())): Document {
        val result = newBlockParser().parseWithCheckpoints(0, source.lineCount)
        val context = result.document
        val regions = parseRegions(result, pipeline.hasAttributes, pipeline.hasBibliography)
        if (_isStreaming) repairTail(regions, context)
        // Full parsing runs the original whole-document pipeline once. Region dependencies
        // were grouped on raw syntax first; built-in transformations preserve group order.
        val doc = Document(context.definitionContext)
        regions.forEach { region -> region.nodes.forEach(doc::appendChild) }
        val metadata = regions.map { regionMetadata(it, pipeline) }
        setDocumentRanges(doc, source)
        pipeline.processors.forEach { it.process(doc) }
        if (!pipeline.supportsIncremental) {
            blocks = BlockIndex.of(listOf(ParsedRegion(source.lineCount, doc.children.toList(), false,
                regions.any { it.hasDefinitions }, regions.any { it.hasNumberedReferences }, false)))
        } else {
            val outputs = List(regions.size) { mutableListOf<Node>() }
            var regionIndex = 0
            for (node in doc.children) {
                while (regionIndex + 1 < regions.size && regions[regionIndex + 1].start <= node.lineRange.startLine) regionIndex++
                check(node.lineRange.startLine >= regions[regionIndex].start && node.lineRange.startLine < regions[regionIndex].end) {
                    "Built-in postprocessor moved a block outside its source region"
                }
                outputs[regionIndex].add(node)
            }
            blocks = BlockIndex.of(metadata.mapIndexed { index, region ->
                region.copy(nodes = outputs[index], hasSourceRanges = outputs[index].any(::hasSourceRanges))
            })
        }
        owner = DocumentOwner(_document)
        publish(context, pipeline, blocks, runLint = false, publishedDocument = doc)
        lastUpdate = UpdateStats(source.lineCount, fullParseReason = reason)
        return _document
    }

    /** Snapshot raw semantic inputs before local processors mutate nodes. */
    private fun regionMetadata(region: ParseRegion, pipeline: Pipeline): ParsedRegion {
        val headings = mutableListOf<HeadingInput>()
        if (pipeline.hasHeadings) region.nodes.forEach { captureHeadings(it, headings) }
        return ParsedRegion(region.end - region.start, region.nodes.toList(), region.dependsOnFirstLine,
            region.hasDefinitions, region.hasNumberedReferences, region.startsWithAttribute, headings,
            bibliographyTailGap = region.bibliographyEnd?.let { region.end - it })
    }

    private fun prepareRegions(
        regions: List<ParseRegion>, context: Document, pipeline: Pipeline, oldTail: List<Node>,
    ): List<Pair<ParsedRegion, Document>> {
        val reusable = oldTail.filter { it is FencedCodeBlock || it is MathBlock }.associateBy { it.lineRange.startLine }
        return regions.map { region ->
            val nodes = reuseStreamingBlockInstances(region.nodes, reusable)
            if (nodes !== region.nodes) {
                region.nodes.clear()
                region.nodes.addAll(nodes)
            }
            val doc = Document(context.definitionContext)
            nodes.forEach(doc::appendChild)
            regionMetadata(region, pipeline) to doc
        }
    }

    private fun hasSourceRanges(node: Node): Boolean = node.sourceRange != SourceRange.EMPTY ||
        (node is ContainerNode && node.materializedChildrenSnapshot().any(::hasSourceRanges))

    private fun captureHeadings(node: Node, result: MutableList<HeadingInput>) {
        when (node) {
            is Heading -> result.add(HeadingInput(node, node.customId, HeadingIdProcessor.generateSlug(HeadingIdProcessor.extractPlainText(node))))
            is SetextHeading -> result.add(HeadingInput(node, null, HeadingIdProcessor.generateSlug(HeadingIdProcessor.extractPlainText(node))))
            is ContainerNode -> node.forEachMaterializedChild { captureHeadings(it, result) }
            else -> Unit
        }
    }

    private fun reindexHeadings(index: BlockIndex) {
        val usedIds = mutableMapOf<String, Int>()
        index.forEachHeading { heading ->
            val id = if (heading.customId != null) {
                usedIds[heading.customId] = (usedIds[heading.customId] ?: 0) + 1
                null
            } else HeadingIdProcessor.deduplicateId(heading.slug, usedIds)
            when (val node = heading.node) {
                is Heading -> if (heading.customId == null) node.autoId = id
                is SetextHeading -> node.autoId = id
                else -> Unit
            }
        }
    }

    private fun repairTail(regions: MutableList<ParseRegion>, context: Document) {
        if (regions.isEmpty()) return
        val last = regions.last()
        if (regions.size > 1 && last.start == source.lineCount - 1 && last.dependsOnFirstLine) {
            // The appendable last line can reopen its predecessor. Cache and repair them as
            // one atomic interval so edits cannot reuse only half of a display-repaired tail.
            regions.removeAt(regions.lastIndex)
            val previous = regions.last()
            previous.end = last.end
            previous.nodes.addAll(last.nodes)
            previous.hasDefinitions = previous.hasDefinitions || last.hasDefinitions
            previous.hasNumberedReferences = previous.hasNumberedReferences || last.hasNumberedReferences
            previous.acceptBibliographyNodes(last.nodes)
        }
        val nodes = regions.last().nodes
        for (i in nodes.indices) nodes[i] = autoCloseBlock(nodes[i], source, context)
    }

    private fun publish(
        context: Document, pipeline: Pipeline, newlyProcessed: BlockIndex,
        runLint: Boolean = true, publishedDocument: Document? = null,
    ) {
        val doc = publishedDocument ?: Document(context.definitionContext)
        doc.installIndexedChildren(blocks.asNodeList())
        newlyProcessed.forEachRegion { it.nodes.forEach { node -> node.attachToDocument(owner) } }
        owner.document = doc
        if (publishedDocument == null) setDocumentRanges(doc, source)
        if (runLint) pipeline.processors.filterIsInstance<LintingPostProcessor>().forEach { it.process(doc) }
        lintingProcessor?.let {
            if (pipeline.processors.none { processor -> processor === it }) it.process(doc)
            doc.diagnostics = it.result
        }
        _document = doc
        parsedSource = source
        processedWith = pipeline.processors
        hasDisplayRepairs = _isStreaming
    }

    private fun reuseStreamingBlockInstances(
        displayBlocks: List<Node>,
        oldChildren: Map<Int, Node>,
    ): List<Node> {
        if (displayBlocks.isEmpty() || oldChildren.isEmpty()) return displayBlocks

        return displayBlocks.map { newBlock ->
            when (newBlock) {
                is FencedCodeBlock -> reuseFencedCodeBlockInstance(newBlock, oldChildren)
                is MathBlock -> reuseMathBlockInstance(newBlock, oldChildren)
                else -> newBlock
            }
        }
    }

    private fun reuseFencedCodeBlockInstance(
        newBlock: FencedCodeBlock,
        oldChildren: Map<Int, Node>,
    ): Node {
        val oldBlock = oldChildren[newBlock.lineRange.startLine] as? FencedCodeBlock ?: return newBlock

        oldBlock.literal = newBlock.literal
        oldBlock.lineRange = newBlock.lineRange
        oldBlock.sourceRange = newBlock.sourceRange
        oldBlock.contentHash = newBlock.contentHash
        oldBlock.info = newBlock.info
        oldBlock.language = newBlock.language
        oldBlock.fenceChar = newBlock.fenceChar
        oldBlock.fenceLength = newBlock.fenceLength
        oldBlock.fenceIndent = newBlock.fenceIndent
        oldBlock.attributes = newBlock.attributes
        oldBlock.highlightLines = newBlock.highlightLines
        oldBlock.showLineNumbers = newBlock.showLineNumbers
        oldBlock.startLineNumber = newBlock.startLineNumber
        oldBlock.parent = null
        return oldBlock
    }

    private fun reuseMathBlockInstance(
        newBlock: MathBlock,
        oldChildren: Map<Int, Node>,
    ): Node {
        val oldBlock = oldChildren[newBlock.lineRange.startLine] as? MathBlock ?: return newBlock

        oldBlock.literal = newBlock.literal
        oldBlock.lineRange = newBlock.lineRange
        oldBlock.sourceRange = newBlock.sourceRange
        oldBlock.contentHash = newBlock.contentHash
        oldBlock.parent = null
        return oldBlock
    }


    private fun autoCloseBlock(block: Node, source: SourceText, references: Document): Node {
        return when (block) {
            is FencedCodeBlock, is MathBlock, is FrontMatter, is HtmlBlock, is DiagramBlock -> block
            is Paragraph -> {
                autoCloseInlineContent(block, source, references)
                block
            }
            is Heading -> {
                autoCloseInlineContent(block, source, references)
                block
            }
            is SetextHeading -> {
                if (shouldDowngradeTransientSetextHeading(block, source)) {
                    createDisplayParagraphFromSetextHeading(block, source, references)
                } else {
                    autoCloseInlineContent(block, source, references)
                    block
                }
            }
            is BlockQuote -> {
                val children = block.children.toList()
                if (children.isNotEmpty()) {
                    val lastChild = children.last()
                    val repaired = autoCloseBlock(lastChild, source, references)
                    if (repaired !== lastChild) {
                        block.replaceChild(lastChild, repaired)
                    }
                }
                block
            }
            is ListBlock, is ListItem, is CustomContainer -> {
                val children = block.children.toList()
                if (children.isNotEmpty()) {
                    val lastChild = children.last()
                    val repaired = autoCloseBlock(lastChild, source, references)
                    if (repaired !== lastChild) {
                        block.replaceChild(lastChild, repaired)
                    }
                }
                block
            }
            is Table -> {
                autoCloseTableCells(block, source, references)
                block
            }
            else -> block
        }
    }

    private fun shouldDowngradeTransientSetextHeading(
        block: SetextHeading,
        source: SourceText,
    ): Boolean {
        if (!_isStreaming) return false
        if (block.lineRange.endLine != source.lineCount) return false
        val underlineLine = source.lineContent(source.lineCount - 1).trim()
        if (underlineLine.isEmpty()) return false
        return underlineLine.all { it == '-' || it == '=' }
    }

    private fun createDisplayParagraphFromSetextHeading(
        heading: SetextHeading,
        source: SourceText,
        references: Document,
    ): Paragraph {
        val paragraph = Paragraph()
        val content = heading.rawContent ?: buildString {
            val contentEnd = (heading.lineRange.endLine - 1).coerceAtLeast(heading.lineRange.startLine)
            for (line in heading.lineRange.startLine until contentEnd) {
                if (line > heading.lineRange.startLine) append('\n')
                append(source.lineContent(line).trimStart().trimEnd())
            }
        }
        paragraph.rawContent = content
        paragraph.lineRange = LineRange(
            heading.lineRange.startLine,
            (heading.lineRange.endLine - 1).coerceAtLeast(heading.lineRange.startLine + 1)
        )
        paragraph.sourceRange = heading.sourceRange
        if (content.isNotEmpty()) {
            val tempDoc = Document()
            tempDoc.linkDefinitions.putAll(references.linkDefinitions)
            tempDoc.footnoteDefinitions.putAll(references.footnoteDefinitions)
            tempDoc.abbreviationDefinitions.putAll(references.abbreviationDefinitions)
            val inlineParser = InlineParser(
                tempDoc,
                customEmojiMap,
                enableAsciiEmoticons,
                flavour.enableGfmAutolinks,
                flavour.enableExtendedInline,
                flavour.enableEmphasisCoalescing,
                flavour.enableStrikethrough
            )
            inlineParser.parseInlines(content, paragraph)
        }
        return paragraph
    }

    private fun autoCloseInlineContent(node: ContainerNode, source: SourceText, references: Document) {
        val inlineText = currentInlineSource(node, source)
        if (inlineText.isEmpty()) return

        val repairSuffix = InlineAutoCloser.buildRepairSuffix(inlineText)
        val repairedContent = inlineText + repairSuffix
        val tempDoc = Document()
        tempDoc.linkDefinitions.putAll(references.linkDefinitions)
        tempDoc.footnoteDefinitions.putAll(references.footnoteDefinitions)
        tempDoc.abbreviationDefinitions.putAll(references.abbreviationDefinitions)
        val inlineParser = InlineParser(tempDoc, customEmojiMap, enableAsciiEmoticons, flavour.enableGfmAutolinks, flavour.enableExtendedInline, flavour.enableEmphasisCoalescing, flavour.enableStrikethrough)
        node.clearChildren()
        inlineParser.parseInlines(repairedContent, node)
    }

    private fun currentInlineSource(node: ContainerNode, source: SourceText): String {
        val raw = when (node) {
            is Paragraph -> node.rawContent
            is Heading -> node.rawContent
            is SetextHeading -> node.rawContent
            is TableCell -> node.rawContent
            else -> null
        }
        if (!raw.isNullOrEmpty()) return raw

        if (node.lineRange.lineCount > 0 && node.lineRange.endLine <= source.lineCount) {
            return buildString {
                for (line in node.lineRange.startLine until node.lineRange.endLine) {
                    if (line > node.lineRange.startLine) append('\n')
                    append(source.lineContent(line).trimStart().trimEnd())
                }
            }
        }

        return extractInlineText(node)
    }

    private fun autoCloseTableCells(table: Table, source: SourceText, references: Document) {
        for (child in table.children) {
            if (child is ContainerNode) {
                for (row in child.children) {
                    if (row is TableRow) {
                        val cells = row.children.toList()
                        if (cells.isNotEmpty()) {
                            val lastCell = cells.last()
                            if (lastCell is TableCell) {
                                autoCloseInlineContent(lastCell, source, references)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun extractInlineText(node: ContainerNode): String {
        val sb = StringBuilder()
        for (child in node.children) {
            appendNodeText(child, sb)
        }
        return sb.toString()
    }

    private fun appendNodeText(node: Node, sb: StringBuilder) {
        when (node) {
            is Text -> sb.append(node.literal)
            is InlineCode -> sb.append("`").append(node.literal).append("`")
            is InlineMath -> sb.append("$").append(node.literal).append("$")
            is Emphasis -> {
                val d = node.delimiter
                sb.append(d)
                for (child in node.children) appendNodeText(child, sb)
                sb.append(d)
            }
            is StrongEmphasis -> {
                val d = node.delimiter.toString().repeat(2)
                sb.append(d)
                for (child in node.children) appendNodeText(child, sb)
                sb.append(d)
            }
            is Strikethrough -> {
                sb.append("~~")
                for (child in node.children) appendNodeText(child, sb)
                sb.append("~~")
            }
            is Highlight -> {
                sb.append("==")
                for (child in node.children) appendNodeText(child, sb)
                sb.append("==")
            }
            is Link -> {
                sb.append("[")
                for (child in node.children) appendNodeText(child, sb)
                sb.append("](").append(node.destination)
                if (node.title != null) sb.append(" \"").append(node.title).append("\"")
                sb.append(")")
            }
            is Image -> {
                sb.append("![")
                for (child in node.children) appendNodeText(child, sb)
                sb.append("](").append(node.destination)
                // 重建 =WxH 尺寸后缀
                if (node.imageWidth != null || node.imageHeight != null) {
                    sb.append(" =")
                    sb.append(node.imageWidth?.toString() ?: "")
                    sb.append("x")
                    sb.append(node.imageHeight?.toString() ?: "")
                }
                if (node.title != null) sb.append(" \"").append(node.title).append("\"")
                sb.append(")")
                // 重建属性块
                if (node.attributes.isNotEmpty()) {
                    sb.append("{")
                    for ((key, value) in node.attributes) {
                        when (key) {
                            "class" -> value.split(" ").filter { it.isNotEmpty() }.forEach { sb.append(".").append(it).append(" ") }
                            "id" -> sb.append("#").append(value).append(" ")
                            else -> if (value.isNotEmpty()) sb.append(key).append("=").append(value).append(" ") else sb.append(key).append(" ")
                        }
                    }
                    sb.trimEnd()
                    sb.append("}")
                }
            }
            is EscapedChar -> sb.append("\\").append(node.literal)
            is SoftLineBreak -> sb.append("\n")
            is HardLineBreak -> sb.append("\n")
            is HtmlEntity -> sb.append(node.literal)
            is Autolink -> sb.append("<").append(node.destination).append(">")
            is ContainerNode -> {
                for (child in node.children) appendNodeText(child, sb)
            }
            is LeafNode -> sb.append(node.literal)
        }
    }

    // ────── 工具方法 ──────

    private fun setDocumentRanges(doc: Document, source: SourceText) {
        doc.lineRange = LineRange(0, source.lineCount)
        if (source.lineCount > 0) {
            doc.sourceRange = SourceRange(
                SourcePosition(0, 0, 0),
                SourcePosition(
                    source.lineCount - 1,
                    source.lineContent(source.lineCount - 1).length,
                    source.length
                )
            )
            doc.contentHash = source.contentHash(doc.lineRange)
        }
    }

}
