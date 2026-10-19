package com.hrm.markdown.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hrm.markdown.renderer.Markdown
import com.hrm.markdown.renderer.MarkdownBlockSlot
import com.hrm.markdown.renderer.MarkdownComponents
import com.hrm.markdown.renderer.MarkdownConfig
import com.hrm.markdown.renderer.MarkdownImageClickEvent
import com.hrm.markdown.renderer.MarkdownInteractions
import com.hrm.markdown.renderer.MarkdownSoftBreakPolicy

internal val rendererExtensionsPreviewGroup = PreviewGroup(
    id = "renderer_extensions",
    title = "交互与标准组件插槽",
    description = "引用来源、图片图集、软换行和代码/表格操作栏",
    items = listOf(
        PreviewItem("citation_actions", "引用点击与回调更新", content = {
            var selected by remember { mutableStateOf<String?>(null) }
            var label by remember { mutableStateOf("来源") }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Button(onClick = { label = if (label == "来源") "更新后的来源" else "来源" }) {
                    Text("更新回调，不重新解析正文")
                }
                Markdown(
                    markdown = "研究结论来自 [@paper]，相关数据来自 [@dataset]。",
                    enableScroll = false,
                    interactions = MarkdownInteractions(onCitationClick = { selected = "$label：${it.key}" }),
                )
            }
            selected?.let { source ->
                AlertDialog(onDismissRequest = { selected = null },
                    title = { Text("引用来源") }, text = { Text(source) },
                    confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
            }
        }),
        PreviewItem("image_gallery_actions", "图片点击与按需图集", content = {
            var selected by remember { mutableStateOf<MarkdownImageClickEvent?>(null) }
            val url = "https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png"
            Markdown(
                markdown = "第一处 ![图片一]($url =160x60)，第二处 ![图片二]($url =160x60)。",
                interactions = MarkdownInteractions(onImageClick = { selected = it }),
            )
            selected?.let { event ->
                val gallery = event.gallery
                var index by remember(event) { mutableStateOf(gallery.initialIndex.coerceAtLeast(0)) }
                AlertDialog(
                    onDismissRequest = { selected = null },
                    title = { Text("图片 ${index + 1} / ${gallery.images.size}") },
                    text = {
                        Column {
                            Markdown("![预览](${gallery.images[index].image.url})", enableScroll = false)
                            Row {
                                TextButton(onClick = { index-- }, enabled = index > 0) { Text("上一张") }
                                TextButton(onClick = { index++ }, enabled = index < gallery.images.lastIndex) { Text("下一张") }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } },
                )
            }
        }),
        PreviewItem("soft_break_policy", "软换行显示策略", content = {
            var lineBreak by remember { mutableStateOf(false) }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Button(onClick = { lineBreak = !lineBreak }) { Text(if (lineBreak) "当前：换行" else "当前：空格") }
                Markdown(
                    markdown = "第一行\n第二行\n**第三行\n第四行**",
                    config = MarkdownConfig(softBreakPolicy = if (lineBreak) MarkdownSoftBreakPolicy.LineBreak else MarkdownSoftBreakPolicy.Space),
                    enableScroll = false,
                )
            }
        }),
        PreviewItem("standard_block_chrome", "代码与表格操作栏", content = {
            var action by remember { mutableStateOf("点击操作栏按钮") }
            // PreviewCard bounds this viewport; Markdown lays out its complete content below.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(action)
                Markdown(
                    markdown = "| 名称 | 值 |\n| --- | --- |\n| answer | 42 |\n\n> ```kotlin\n> 嵌套代码块也保留操作栏高度\n> ```",
                    enableScroll = false,
                    components = MarkdownComponents(
                        codeBlockHeader = MarkdownBlockSlot(48.dp) { code ->
                            TextButton(onClick = { action = "${code.language}：${code.code}" }) { Text("查看代码") }
                        },
                        tableToolbar = MarkdownBlockSlot(48.dp) { table ->
                            TextButton(onClick = { action = "导出 ${table.rowCount} 行 × ${table.columnCount} 列" }) { Text("导出表格") }
                        },
                    ),
                )
            }
        }),
    ),
)
