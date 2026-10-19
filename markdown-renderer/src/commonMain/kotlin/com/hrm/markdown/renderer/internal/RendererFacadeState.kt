package com.hrm.markdown.renderer.internal

import com.hrm.codehigh.theme.CodeTheme
import com.hrm.markdown.renderer.MarkdownConfig
import com.hrm.markdown.renderer.MarkdownTheme
import com.hrm.markdown.runtime.MarkdownDirectiveRegistry

data class RendererFacadeState(
    val theme: MarkdownTheme,
    val config: MarkdownConfig,
    val codeTheme: CodeTheme?,
    val directiveRegistry: MarkdownDirectiveRegistry,
    val isStreaming: Boolean,
)
