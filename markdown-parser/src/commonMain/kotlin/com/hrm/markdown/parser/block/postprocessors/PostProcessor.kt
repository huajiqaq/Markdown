package com.hrm.markdown.parser.block.postprocessors

import com.hrm.markdown.parser.ast.Document

/**
 * 后处理器接口。
 *
 * 在文档构建阶段执行依赖全文的 AST 变换。
 * 独立块转换使用 [BlockPostProcessor]，引擎会自动局部重做。
 */
fun interface PostProcessor : AstPostProcessor {
    /**
     * 对文档 AST 进行后处理变换。
     */
    fun process(document: Document)
}
