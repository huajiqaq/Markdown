package com.hrm.markdown

import androidx.compose.ui.window.ComposeUIViewController
import com.hrm.latex.fonts.KatexFonts

fun MainViewController(): platform.UIKit.UIViewController {
    KatexFonts.install()
    return ComposeUIViewController { App() }
}
