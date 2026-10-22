package com.hrm.markdown.parser.ast

import kotlin.random.Random

/** Opaque identity of a sequence of committed parser publications. */
class PublicationLineage internal constructor() {
    /** Render namespace only; consumers prove lineage equality by reference identity. */
    val identityKey: Long = Random.nextLong()
    internal var currentRevision: Long = 0L
    internal var alive: Boolean = true
}

/**
 * A parser-issued description of one committed document revision.
 *
 * The splice uses indexes in [Document.children], including non-rendered nodes such as
 * [BlankLine]. It replaces [removedCount] nodes at [startIndex] in [baseRevision] with
 * [insertedCount] nodes at the same index in [revision]. Consumers may apply the splice
 * only when both the lineage identity and base revision match their retained state.
 * A null base or [globalDependenciesChanged] requires rebuilding dependent output.
 *
 * Published ASTs are read-only to callers. This protocol describes the current committed
 * tree; older Document references are not immutable historical snapshots of reused nodes.
 */
class DocumentPublication internal constructor(
    val lineageId: PublicationLineage,
    val baseRevision: Long?,
    val revision: Long,
    val startIndex: Int,
    val removedCount: Int,
    val insertedCount: Int,
    val globalDependenciesChanged: Boolean,
) {
    /** Reused parser nodes make obsolete roots unsuitable for new rendering work. */
    val isCurrent: Boolean get() = lineageId.alive && lineageId.currentRevision == revision
}
