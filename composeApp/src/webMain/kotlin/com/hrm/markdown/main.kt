package com.hrm.markdown

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.hrm.latex.fonts.KatexFonts

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    KatexFonts.install()
    ComposeViewport {
        App()
    }
}
