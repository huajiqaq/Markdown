package com.hrm.markdown.parser.block.postprocessors

import com.hrm.markdown.parser.ast.Node

/**
 * 转换独立的 Markdown 块及其完整子树。
 *
 * 修改后返回输入块，或返回替换节点；返回 null 表示删除该块。
 * 解析器自动重新处理变化的块。回调应仅根据输入块产生结果，依赖全文的转换使用 [PostProcessor]。
 * 已发布节点保持只读。替换节点自动从输入块继承缺失的源码坐标，调用方无需维护解析器元数据。
 * 包含引用定义的块保持原样；需要修改定义时使用 [PostProcessor]。
 */
fun interface BlockPostProcessor : AstPostProcessor {
    fun processBlock(block: Node): Node?
}
