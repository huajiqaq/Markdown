package com.hrm.markdown.renderer.internal.core.compile

import com.hrm.markdown.renderer.MarkdownSoftBreakPolicy
import com.hrm.markdown.runtime.MarkdownDirectiveRegistry

data class RenderCompileEnvironment(
    val config: RenderConfigSnapshot = RenderConfigSnapshot(),
    val directiveRegistry: MarkdownDirectiveRegistry = MarkdownDirectiveRegistry.Empty,
)

data class RenderConfigSnapshot(
    val enableHeadingNumbering: Boolean = false,
    val streaming: Boolean = false,
    val softBreakPolicy: MarkdownSoftBreakPolicy = MarkdownSoftBreakPolicy.Space,
)
