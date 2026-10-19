package com.hrm.markdown

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController(): platform.UIKit.UIViewController {
    return ComposeUIViewController { App() }
}
