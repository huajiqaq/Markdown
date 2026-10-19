package com.hrm.markdown

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.hrm.latex.fonts.KatexFonts
import com.hrm.markdown.parser.log.HLog
import com.hrm.markdown.parser.log.ILogger

fun main() {
    KatexFonts.install()
    application {
        HLog.setLogger(object : ILogger {
            override fun v(tag: String, message: String) {
                println("$tag, $message")
            }

            override fun d(tag: String, message: String) {
                println("$tag, $message")
            }

            override fun i(tag: String, message: String) {
                println("$tag, $message")
            }

            override fun w(tag: String, message: String) {
                println("$tag, $message")
            }

            override fun e(
                tag: String,
                message: String,
                throwable: Throwable?
            ) {
                println("$tag, $message")
            }
        })

        Window(
            onCloseRequest = ::exitApplication,
            title = "Markdown",
        ) {
            App()
        }
    }
}
