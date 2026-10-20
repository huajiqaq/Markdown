package com.hrm.markdown.parser.block.starters

import com.hrm.markdown.parser.LineRange
import com.hrm.markdown.parser.ast.*
import com.hrm.markdown.parser.block.OpenBlock
import com.hrm.markdown.parser.block.TableParser
import com.hrm.markdown.parser.core.LineCursor

/**
 * 表格开启器（GFM 扩展）。
 * 检测段落后跟的分隔行 `| --- | --- |`。
 */
internal class TableStarter : BlockStarter {
    override val priority: Int = 200
    override val canInterruptParagraph: Boolean = true

    override fun tryStart(cursor: LineCursor, lineIdx: Int, tip: OpenBlock): OpenBlock? {
        if (tip.paragraphContent == null) return null
        // Most continuation lines cannot delimit a table. Reject them before copying
        // the growing paragraph, which would otherwise cost O(N²) for a long paragraph.
        val delimLine = cursor.rest().trim()
        val alignments = TableParser.parseTableDelimiterRow(delimLine) ?: return null
        val headerLine = tip.paragraphContent.toString().trim()
        if (!headerLine.contains('|')) return null
        val headerCells = TableParser.parseTableRow(headerLine)
        if (headerCells.isEmpty()) return null

        val table = Table()
        table.columnAlignments = alignments
        table.lineRange = LineRange(tip.contentStartLine, lineIdx + 1)

        val head = TableHead()
        head.lineRange = LineRange(tip.contentStartLine, lineIdx)
        val headerRow = TableRow()
        headerRow.lineRange = head.lineRange
        val colCount = alignments.size
        for (i in 0 until colCount) {
            val cellContent = headerCells.getOrElse(i) { "" }
            val align = alignments[i]
            val cell = TableCell(alignment = align, isHeader = true)
            cell.lineRange = LineRange(tip.contentStartLine, tip.contentStartLine + 1)
            cell.rawContent = cellContent
            headerRow.appendChild(cell)
        }
        head.appendChild(headerRow)
        table.appendChild(head)

        val body = TableBody()
        body.lineRange = LineRange(lineIdx + 1, lineIdx + 1)
        table.appendChild(body)

        val ob = OpenBlock(table, contentStartLine = tip.contentStartLine, lastLineIndex = lineIdx)
        ob.contentLines.clear()
        ob.starterTag = this::class.simpleName
        return ob
    }
}
