package com.hrm.markdown.parser.incremental

import com.hrm.markdown.parser.LineRange
import com.hrm.markdown.parser.SourcePosition
import com.hrm.markdown.parser.SourceRange
import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.BlockParser
import com.hrm.markdown.parser.block.postprocessors.*
import com.hrm.markdown.parser.core.SourceText
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.FlavourCache
import com.hrm.markdown.parser.flavour.GFMFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.hrm.markdown.parser.flavour.MarkdownExtraFlavour
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
    private var rootFenceContinuation: RootFenceContinuation? = null
    private var publicationLineage = PublicationLineage()
    private var publicationRevision = 0L
    private var nextPersistentId = 1L
    private val hasBuiltInFenceSyntax = flavour === CommonMarkFlavour || flavour === GFMFlavour ||
        flavour === ExtendedFlavour || flavour === MarkdownExtraFlavour
    private val hasCustomBlockStarters = !hasBuiltInFenceSyntax && flavourCache.blockStarters.any {
        it::class !in builtInStarterTypes
    }

    private data class RootFenceContinuation(
        val source: SourceText,
        val document: Document,
        val region: ParsedRegion,
        val block: FencedCodeBlock,
    ) {
        fun matches(source: SourceText, document: Document, region: ParsedRegion, block: FencedCodeBlock): Boolean =
            this.source === source && this.document === document && this.region === region && this.block === block &&
                block.parent === document
    }

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

    private var updatingDocument = false

    private inline fun <T> mutateDocument(action: () -> T): T {
        check(!updatingDocument) { "A parser callback cannot recursively update the same MarkdownParser" }
        updatingDocument = true
        try { return withDocumentMutation(_document, action) } finally { updatingDocument = false }
    }

    fun fullParse(input: String): Document = mutateDocument { fullParseMutable(input) }

    fun beginStream() = mutateDocument { beginStreamMutable() }

    fun append(chunk: String): Document = mutateDocument { appendMutable(chunk) }

    fun endStream(): Document = mutateDocument { endStreamMutable() }

    fun abort(): Document = mutateDocument { abortMutable() }

    fun applyEdit(edit: EditOperation): Document = mutateDocument { applyEditMutable(edit) }

    private fun fullParseMutable(input: String): Document {
        source = SourceText.of(input)
        _isStreaming = false
        previousRawChunkEndedWithCarriageReturn = false
        return rebuild("full input")
    }

    private fun beginStreamMutable() {
        source = SourceText.of("")
        parsedSource = source
        blocks = BlockIndex.empty()
        _document = Document()
        owner = DocumentOwner(_document)
        resetPublicationLineage()
        _document.persistentId = nextPersistentId++
        _document.publication = DocumentPublication(publicationLineage, null, ++publicationRevision,
            0, 0, 0, globalDependenciesChanged = true)
        publicationLineage.currentRevision = publicationRevision
        _document.sealPublication(emptyList())
        processedWith = null
        rootFenceContinuation = null
        _isStreaming = true
        hasDisplayRepairs = false
        previousRawChunkEndedWithCarriageReturn = false
        lastUpdate = UpdateStats(0)
    }

    private fun appendMutable(chunk: String): Document {
        if (chunk.isEmpty()) return _document
        val normalized = normalizeStreamingChunk(chunk)
        if (normalized.isEmpty()) return _document
        source = SourceText.applyEditFast(source, source.length, 0, normalized)
        if (appendCoalesceThreshold > 0 && '\n' !in normalized &&
            source.length - parsedSource.length < appendCoalesceThreshold) return _document
        continueOpenRootFence()?.let { return it }
        return update(parsedSource.length, 0, source.length - parsedSource.length)
    }

    private fun endStreamMutable(): Document {
        _isStreaming = false
        previousRawChunkEndedWithCarriageReturn = false
        // Remove display-only repairs by reparsing the same unstable interval, not the prefix.
        return update(parsedSource.length, 0, source.length - parsedSource.length, forceTail = true)
    }

    private fun abortMutable(): Document {
        if (source !== parsedSource) update(parsedSource.length, 0, source.length - parsedSource.length)
        _isStreaming = false
        previousRawChunkEndedWithCarriageReturn = false
        return _document
    }

    private fun applyEditMutable(edit: EditOperation): Document {
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
    ).apply { allowPublishedFootnoteNumbering = true }

    /**
     * A root fence with a completed opening line has no syntax decisions left until its closer.
     * Continue that raw leaf without rebuilding its accumulated body. All ambiguous boundaries,
     * custom syntax and global processing retain the checkpoint update path.
     */
    private fun continueOpenRootFence(): Document? {
        if (!_isStreaming || blocks.size == 0 || lintingProcessor != null) return null
        val continuation = rootFenceContinuation ?: return null
        // A custom starter can construct a FencedCodeBlock with different OpenBlock state.
        if (!hasBuiltInFenceSyntax) return null
        val pipeline = Pipeline(postProcessors.sortedSnapshot())
        if (processedWith !== pipeline.processors || !pipeline.supportsIncremental ||
            pipeline.hasBlockProcessors || pipeline.processors.any { it is LintingPostProcessor }) return null
        val region = blocks[blocks.size - 1]
        val block = region.nodes.singleOrNull() as? FencedCodeBlock ?: return null
        val old = parsedSource
        // Published nodes cannot be externally changed; the continuation belongs to this commit.
        if (!continuation.matches(old, _document, region, block)) return null
        val openingLine = block.lineRange.startLine
        if (region.hasDefinitions || region.headings.isNotEmpty() || block.fenceIndent != 0 ||
            block.sourceRange != SourceRange.EMPTY || openingLine >= old.lineCount - 1 ||
            block.lineRange.endLine != old.lineCount || blocks.startLine(blocks.size - 1) != openingLine) return null
        // A closed fence without a final newline also ends on the last physical line.
        val firstChangedLine = old.lineCount - 1
        if (isClosingFenceLine(old, firstChangedLine, block)) return null
        if (isClosingFenceLine(source, firstChangedLine, block)) return null
        var hasClosingFence = false
        source.forEachLine(firstChangedLine + 1, source.lineCount) { _, text, _ ->
            if (isClosingFenceText(text, block)) hasClosingFence = true
            !hasClosingFence
        }
        if (hasClosingFence) return null

        val hasNumberedReferences = region.hasNumberedReferences ||
            source.substring((old.length - 1).coerceAtLeast(0), source.length).contains("[^")
        if (_document.footnoteDefinitions.isNotEmpty() && hasNumberedReferences) return null

        // The surviving built-in processors cannot transform this single raw leaf: diagrams
        // have already become DiagramBlock, and attributes outside the fence require a closer.
        val committedSource = source
        val bodyStart = committedSource.lineEnd(openingLine)
        block.setLazyLiteral {
            val body = committedSource.substring(bodyStart, committedSource.length)
            if (body.isNotEmpty() && !body.endsWith('\n')) body + "\n" else body
        }
        block.lineRange = LineRange(openingLine, committedSource.lineCount)
        block.contentHash = committedSource.contentHash(block.lineRange)
        val prefix = blocks.splitAt(blocks.size - 1).first
        val replacement = BlockIndex.of(listOf(region.copy(
            lines = committedSource.lineCount - openingLine,
            hasNumberedReferences = hasNumberedReferences,
        )))
        blocks = prefix + replacement
        publish(_document, pipeline, replacement,
            splice = PublicationSplice(prefix.nodeCount, region.nodes.size, replacement.nodeCount))
        lastUpdate = UpdateStats(committedSource.lineCount - firstChangedLine, prefix.size)
        return _document
    }

    /** Check the old partial line without flattening a potentially very long code line. */
    private fun isClosingFenceLine(text: SourceText, line: Int, block: FencedCodeBlock): Boolean {
        var position = text.lineStart(line)
        val end = text.lineEnd(line)
        var indent = 0
        while (position < end && indent < 3 && text[position] == ' ') { position++; indent++ }
        // A leading tab reaches column four, leaving virtual indentation after advanceSpaces(3).
        var run = 0
        while (position < end && text[position] == block.fenceChar) { position++; run++ }
        if (run < block.fenceLength) return false
        while (position < end) if (!text[position++].isWhitespace()) return false
        return true
    }

    private fun isClosingFenceText(text: String, block: FencedCodeBlock): Boolean {
        var position = 0
        while (position < minOf(3, text.length) && text[position] == ' ') position++
        var run = 0
        while (position < text.length && text[position] == block.fenceChar) { position++; run++ }
        if (run < block.fenceLength) return false
        while (position < text.length) if (!text[position++].isWhitespace()) return false
        return true
    }

    private fun captureRootFenceContinuation(
        pipeline: Pipeline, newlyProcessed: BlockIndex,
    ): RootFenceContinuation? {
        if (!_isStreaming || !hasBuiltInFenceSyntax || blocks.size == 0 || newlyProcessed.size == 0 ||
            lintingProcessor != null || !pipeline.supportsIncremental ||
            pipeline.hasBlockProcessors || pipeline.processors.any { it is LintingPostProcessor }) return null
        val region = newlyProcessed[newlyProcessed.size - 1]
        val block = region.nodes.singleOrNull() as? FencedCodeBlock ?: return null
        // Only the changed tail can establish a new continuation. Cold parsing does no capture.
        if (blocks[blocks.size - 1] !== region) return null
        val openingLine = block.lineRange.startLine
        if (region.hasDefinitions || region.headings.isNotEmpty() || block.fenceIndent != 0 ||
            block.sourceRange != SourceRange.EMPTY || openingLine >= source.lineCount - 1 ||
            block.lineRange.endLine != source.lineCount || blocks.startLine(blocks.size - 1) != openingLine) return null
        return RootFenceContinuation(source, _document, region, block)
    }

    private data class Pipeline(val processors: List<PostProcessor>) {
        val hasAttributes = processors.any { it is BlockAttributeProcessor }
        val hasBibliography = processors.any { it is BibliographyProcessor }
        val hasHeadings = processors.any { it is HeadingIdProcessor }
        val hasBlockProcessors = processors.any { it is BlockPostProcessor }
        val supportsIncremental = processors.all {
            it is HeadingIdProcessor || it is BlockAttributeProcessor || it is BibliographyProcessor ||
                it is AbbreviationProcessor || it is DiagramProcessor || it is ColumnsLayoutProcessor ||
                it is HtmlFilterProcessor || it is FigureProcessor || it is LintingPostProcessor ||
                it is BlockPostProcessor
        }
        fun processLocal(document: Document, previousDocument: Document) {
            processors.forEach {
                if (it !is HeadingIdProcessor && it !is LintingPostProcessor) process(it, document, previousDocument)
            }
        }
        fun processAll(document: Document, previousDocument: Document, regions: List<ParseRegion>) {
            processors.forEach { processor ->
                if (processor !is BlockPostProcessor || !supportsIncremental || regions.isEmpty()) {
                    process(processor, document, previousDocument)
                } else {
                    // Use the same root-checkpoint partition for cold and incremental runs.
                    val groups = List(regions.size) { mutableListOf<Node>() }
                    var regionIndex = 0
                    document.children.forEach { node ->
                        while (regionIndex + 1 < regions.size &&
                            regions[regionIndex + 1].start <= node.lineRange.startLine) regionIndex++
                        groups[regionIndex].add(node)
                    }
                    val transformed = mutableListOf<Node>()
                    groups.forEach { nodes ->
                        if (nodes.isNotEmpty()) {
                            val regionDocument = Document(document.definitionContext)
                            nodes.forEach(regionDocument::appendChild)
                            process(processor, regionDocument, previousDocument)
                            transformed.addAll(regionDocument.children)
                        }
                    }
                    document.clearChildren()
                    transformed.forEach(document::appendChild)
                }
            }
        }
        private fun process(processor: PostProcessor, document: Document, previousDocument: Document) {
            if (processor !is BlockPostProcessor) {
                processor.process(document)
                return
            }
            document.inheritMutationOwner(previousDocument)
            document.definitionContext.seal(previousDocument.publicationMutationOwner)
            transformDocumentBlocks(document) { block ->
                val output = withoutDocumentMutation(previousDocument) {
                    withSubtreeMutation(block) {
                        processor.processBlock(block).also { output ->
                            check(output == null || output.mutationOwner == null || output.hasLocalMutationPermit) {
                                "BlockPostProcessor must return its input subtree or a new node, not another published block."
                            }
                            if (output != null) materializeUntrustedContent(output)
                        }
                    }
                }
                output?.seal(previousDocument.publicationMutationOwner)
                output
            }
            // A callback cannot retain a writable reference into the next committed tree.
            document.sealPublication(document.children)
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
            withoutDocumentMutation(_document) {
                newBlockParser().parseWithCheckpoints(blocks.startLine(first), source.lineCount) { line ->
                    val oldLine = line - lineDelta
                    while (candidate != null && candidate!!.line < oldLine) {
                        candidate = if (checkpoints.hasNext()) checkpoints.next() else null
                    }
                    if (candidate?.line == oldLine && !candidate!!.region.startsWithAttribute) {
                        suffixIndex = candidate!!.index
                        true
                    } else false
                }.also { result ->
                    if (hasCustomBlockStarters) materializeUntrustedContent(result.document)
                }
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
        val offsetDelta = source.length - old.length
        if (suffix.nodeCount != 0 && (lineDelta != 0 || offsetDelta != 0) &&
            (pipeline.hasBlockProcessors || hasCustomBlockStarters)) {
            // Extensions may render absolute coordinates as text. Updating metadata alone
            // would preserve stale semantics, so position changes invalidate those outputs.
            return rebuild("extension source positions changed", pipeline)
        }
        if (replaced.hasDefinitions || regions.any { it.hasDefinitions }) return rebuild("definitions changed", pipeline)
        if (pipeline.hasBlockProcessors && pipeline.hasHeadings &&
            (replaced.headingCount != 0 || regions.any { region -> region.nodes.any(::containsHeading) })) {
            return rebuild("block postprocessor heading dependencies", pipeline)
        }
        if (_document.footnoteDefinitions.isNotEmpty() &&
            (replaced.hasNumberedReferences || regions.any { it.hasNumberedReferences })) return rebuild("footnote order changed", pipeline)

        val context = parsed.document
        context.definitionContext.inherit(_document.definitionContext)
        if (_isStreaming && suffix.size == 0) repairTail(regions, context)
        val oldTail = if (_isStreaming) replaced.asNodeList() else emptyList()
        val prepared = prepareRegions(regions, context, pipeline, oldTail)
        // The entire suffix is retained. There is no second filtering pass that can drop nodes.
        if (lineDelta != 0 || (offsetDelta != 0 && suffix.hasSourceRanges)) suffix.forEachRegion(onlyPositioned = lineDelta == 0) { region ->
            region.nodes.forEach { it.shiftSubtreeCoordinates(lineDelta, offsetDelta) }
        }
        val headingsChanged = pipeline.hasHeadings &&
            (replaced.headingCount != 0 || prepared.any { it.first.headings.isNotEmpty() })
        if (headingsChanged) {
            val replacement = BlockIndex.of(prepared.map { it.first })
            reindexHeadings(prefix + replacement + suffix)
        }
        prepared.forEach { (_, doc) -> pipeline.processLocal(doc, _document) }
        // Local processors can replace/remove nodes; update only newly parsed leaves.
        val processed = BlockIndex.of(prepared.map { (region, doc) -> region.copy(nodes = doc.children.toList(), hasSourceRanges = doc.children.any(::hasSourceRanges)) })
        if (pipeline.hasBlockProcessors && (replaced.anyGlobalDependency() || processed.anyGlobalDependency())) {
            return rebuild("block postprocessor global dependencies", pipeline)
        }
        retainReplacementIdentities(replaced, processed)
        val globalDependenciesChanged = headingsChanged ||
            replaced.anyGlobalDependency() || processed.anyGlobalDependency()
        blocks = prefix + processed + suffix
        // Removed nodes belong to the old root, not to the owner's next published root.
        // Reused nodes in the replacement are rebound by publish below.
        replaced.forEachRegion { it.nodes.forEach { node -> node.parent = _document } }
        publish(context, pipeline, processed,
            splice = PublicationSplice(prefix.nodeCount, replaced.nodeCount, processed.nodeCount,
                globalDependenciesChanged))
        lastUpdate = UpdateStats(parsed.endLine - parsedStart, prefix.size, suffix.size)
        return _document
    }

    private fun rebuild(reason: String, pipeline: Pipeline = Pipeline(postProcessors.sortedSnapshot())): Document {
        resetPublicationLineage()
        val result = withoutDocumentMutation(_document) {
            newBlockParser().parseWithCheckpoints(0, source.lineCount).also {
                if (hasCustomBlockStarters) materializeUntrustedContent(it.document)
            }
        }
        val context = result.document
        val regions = parseRegions(result, pipeline.hasAttributes, pipeline.hasBibliography)
        if (_isStreaming) repairTail(regions, context)
        // Full parsing runs the original whole-document pipeline once. Region dependencies
        // were grouped on raw syntax first; built-in transformations preserve group order.
        val doc = Document(context.definitionContext)
        regions.forEach { region -> region.nodes.forEach(doc::appendChild) }
        val metadata = regions.map { regionMetadata(it, pipeline) }
        setDocumentRanges(doc, source)
        pipeline.processAll(doc, _document, regions)
        if (!pipeline.supportsIncremental) {
            // Global extensions can install new callbacks after the fresh block parse.
            // Finish those callbacks before publishing, with old committed trees locked.
            withoutDocumentMutation(_document) { materializeUntrustedContent(doc) }
        }
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
        // Output membership is captured only after postprocessing, before publication.
        // Raw nodes are not needed by the temporary heading index.
        return ParsedRegion(region.end - region.start, emptyList(), region.dependsOnFirstLine,
            region.hasDefinitions, region.hasNumberedReferences, region.startsWithAttribute, headings,
            bibliographyTailGap = region.bibliographyEnd?.let { region.end - it })
    }

    private fun prepareRegions(
        regions: List<ParseRegion>, context: Document, pipeline: Pipeline, oldTail: List<Node>,
    ): List<Pair<ParsedRegion, Document>> {
        val reusable = oldTail.filter { it is FencedCodeBlock || it is MathBlock }.associateBy { it.lineRange.startLine }
        return regions.map { region ->
            // External local callbacks get writable new syntax, never a retained published leaf.
            val nodes = if (pipeline.hasBlockProcessors) region.nodes
                else reuseStreamingBlockInstances(region.nodes, reusable)
            if (nodes !== region.nodes) {
                region.nodes.clear()
                region.nodes.addAll(nodes)
            }
            val doc = Document(context.definitionContext)
            nodes.forEach(doc::appendChild)
            regionMetadata(region, pipeline) to doc
        }
    }

    private fun hasSourceRanges(node: Node): Boolean {
        if (node.sourceRange != SourceRange.EMPTY) return true
        if (node is ContainerNode) node.forEachMaterializedChild { if (hasSourceRanges(it)) return true }
        return false
    }

    private fun captureHeadings(node: Node, result: MutableList<HeadingInput>) {
        when (node) {
            is Heading -> result.add(HeadingInput(node, node.customId, HeadingIdProcessor.generateSlug(HeadingIdProcessor.extractPlainText(node))))
            is SetextHeading -> result.add(HeadingInput(node, null, HeadingIdProcessor.generateSlug(HeadingIdProcessor.extractPlainText(node))))
            is ContainerNode -> node.forEachMaterializedChild { captureHeadings(it, result) }
            else -> Unit
        }
    }

    private fun containsHeading(node: Node): Boolean {
        if (node is Heading || node is SetextHeading) return true
        if (node is ContainerNode) node.forEachMaterializedChild { if (containsHeading(it)) return true }
        return false
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
        splice: PublicationSplice? = null,
    ) {
        val doc = publishedDocument ?: Document(context.definitionContext)
        doc.inheritMutationOwner(_document)
        doc.installIndexedChildren(blocks.asNodeList())
        doc.persistentId = _document.persistentId.takeIf { splice != null && it != 0L } ?: nextPersistentId++
        newlyProcessed.forEachRegion { region ->
            region.nodes.forEach { node ->
                node.attachToDocument(owner)
                if (splice == null || node.persistentId == 0L) node.persistentId = nextPersistentId++
            }
        }
        owner.document = doc
        if (publishedDocument == null) setDocumentRanges(doc, source)
        if (runLint) pipeline.processors.filterIsInstance<LintingPostProcessor>().forEach { it.process(doc) }
        lintingProcessor?.let {
            if (pipeline.processors.none { processor -> processor === it }) it.process(doc)
            doc.diagnostics = it.result
        }
        doc.publication = DocumentPublication(
            lineageId = publicationLineage,
            baseRevision = if (splice == null) null else publicationRevision,
            revision = ++publicationRevision,
            startIndex = splice?.startIndex ?: 0,
            removedCount = splice?.removedCount ?: 0,
            insertedCount = splice?.insertedCount ?: blocks.nodeCount,
            globalDependenciesChanged = splice?.globalDependenciesChanged ?: true,
        )
        publicationLineage.currentRevision = publicationRevision
        _document = doc
        parsedSource = source
        processedWith = pipeline.processors
        hasDisplayRepairs = _isStreaming
        rootFenceContinuation = captureRootFenceContinuation(pipeline, newlyProcessed)
        // Walk the replacement regions directly: the public persistent List iterator uses
        // a sequence coroutine and is unnecessary for this internal publication pass.
        val mutationOwner = doc.publicationMutationOwner
        newlyProcessed.forEachRegion { region -> region.nodes.forEach { it.seal(mutationOwner) } }
        doc.sealPublication(emptyList())
    }

    private data class PublicationSplice(
        val startIndex: Int,
        val removedCount: Int,
        val insertedCount: Int,
        val globalDependenciesChanged: Boolean = false,
    )

    private fun resetPublicationLineage() {
        publicationLineage.alive = false
        publicationLineage = PublicationLineage()
        publicationRevision = 0L
        nextPersistentId = 1L
    }

    /** Preserve a block's identity across edits that retain its syntax kind and start line. */
    private fun retainReplacementIdentities(previous: BlockIndex, replacement: BlockIndex) {
        if (previous.nodeCount == 0 || replacement.nodeCount == 0) return
        if (previous.nodeCount == 1 && replacement.nodeCount == 1) {
            // Typing in a single tail block needs neither a line-to-list map nor boxed ID set.
            val node = replacement.asNodeList()[0]
            if (node.persistentId != 0L) return
            val old = previous.asNodeList()[0]
            if (old.lineRange.startLine == node.lineRange.startLine && old::class == node::class) {
                node.persistentId = old.persistentId
            }
            return
        }
        val oldNodes = mutableMapOf<Int, MutableList<Node>>()
        val assignedIds = mutableSetOf<Long>()
        replacement.forEachRegion { region ->
            region.nodes.forEach { node -> if (node.persistentId != 0L) assignedIds.add(node.persistentId) }
        }
        previous.forEachRegion { region ->
            region.nodes.forEach { node -> oldNodes.getOrPut(node.lineRange.startLine) { mutableListOf() }.add(node) }
        }
        replacement.forEachRegion { region ->
            region.nodes.forEach { node ->
                if (node.persistentId == 0L) {
                    val candidates = oldNodes[node.lineRange.startLine]
                    val match = candidates?.indexOfFirst {
                        it::class == node::class && it.persistentId !in assignedIds
                    } ?: -1
                    if (match >= 0) {
                        node.persistentId = candidates!!.removeAt(match).persistentId
                        assignedIds.add(node.persistentId)
                    }
                }
            }
        }
    }

    private fun BlockIndex.anyGlobalDependency(): Boolean {
        fun hasDependency(node: Node): Boolean {
            if (node is Heading || node is SetextHeading || node is TocPlaceholder ||
                node is FrontMatter || node is FootnoteDefinition || node is LinkReferenceDefinition ||
                node is AbbreviationDefinition || node is BibliographyDefinition) return true
            if (node is ContainerNode) node.forEachMaterializedChild { if (hasDependency(it)) return true }
            return false
        }
        var found = false
        forEachRegion { region -> if (!found && region.nodes.any(::hasDependency)) found = true }
        return found
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

        oldBlock.copyLiteralFrom(newBlock)
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
                val lastChild = block.children.lastOrNull()
                if (lastChild != null) {
                    val repaired = autoCloseBlock(lastChild, source, references)
                    if (repaired !== lastChild) {
                        block.replaceChild(lastChild, repaired)
                    }
                }
                block
            }
            is ListBlock, is ListItem, is CustomContainer -> {
                val lastChild = block.children.lastOrNull()
                if (lastChild != null) {
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
            val tempDoc = Document(references.definitionContext)
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
        // Keep complete content lazy. Repairing an unchanged cell/paragraph must not
        // eagerly parse it, or copy definition tables proportional to the document.
        // Footnote indices remain observable immediately after a streaming append.
        // Escaped/code-span carets inside brackets can also become footnote references.
        val numbersFootnotes = references.footnoteDefinitions.isNotEmpty() && '[' in inlineText
        if (repairSuffix.isEmpty() && !numbersFootnotes) return
        val repairedContent = inlineText + repairSuffix
        val tempDoc = Document(references.definitionContext)
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
        // Preserve display repair for the last cell of every row, including finished rows.
        // Complete cells keep their existing lazy content and never copy the definition maps.
        for (section in table.children) {
            if (section !is ContainerNode) continue
            for (row in section.children) {
                if (row !is TableRow) continue
                val cell = row.children.lastOrNull() as? TableCell ?: continue
                autoCloseInlineContent(cell, source, references)
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

    private companion object {
        val builtInStarterTypes = (CommonMarkFlavour.blockStarters + GFMFlavour.blockStarters +
            ExtendedFlavour.blockStarters + MarkdownExtraFlavour.blockStarters).map { it::class }.toSet()
    }

    // ────── 工具方法 ──────

    private fun setDocumentRanges(doc: Document, source: SourceText) {
        doc.lineRange = LineRange(0, source.lineCount)
        if (source.lineCount > 0) {
            doc.sourceRange = SourceRange(
                SourcePosition(0, 0, 0),
                SourcePosition(
                    source.lineCount - 1,
                    source.length - source.lineStart(source.lineCount - 1),
                    source.length
                )
            )
            doc.contentHash = source.contentHash(doc.lineRange)
        }
    }

}
