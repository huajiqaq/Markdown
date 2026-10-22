package com.hrm.markdown.parser.block.postprocessors

/** 有序的 AST 转换。优先级数值较小者先执行；数值相同时保留列表顺序。 */
sealed interface AstPostProcessor {
    val priority: Int get() = 100
}

internal fun AstPostProcessor.requireSingleScope() {
    require(this !is BlockPostProcessor || this !is PostProcessor) {
        "An AST processor must implement either BlockPostProcessor or PostProcessor, not both."
    }
}
