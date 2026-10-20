package com.hrm.markdown.parser.incremental

import com.hrm.markdown.parser.LineRange
import com.hrm.markdown.parser.ast.Node
import com.hrm.markdown.parser.core.SourceText

/**
 * Compatibility entry point. AST ranges and delimiter text cannot prove parser restart safety.
 * Without parser checkpoints the only generally safe dirty region is the complete document.
 * IncrementalEngine uses its measured checkpoint index instead of this legacy helper.
 */
@Deprecated("Use MarkdownParser's checkpoint-based edit API; AST ranges cannot prove restart safety.")
@Suppress("UNUSED_PARAMETER")
class DirtyRegionTracker {
    fun computeDirtyRange(
        edit: EditOperation,
        oldSource: SourceText,
        newSource: SourceText,
        oldBlocks: List<Node>,
    ): LineRange = LineRange(0, newSource.lineCount)

    fun computeAppendDirtyRange(stableEndLine: Int, newSource: SourceText): LineRange =
        LineRange(0, newSource.lineCount)
}
