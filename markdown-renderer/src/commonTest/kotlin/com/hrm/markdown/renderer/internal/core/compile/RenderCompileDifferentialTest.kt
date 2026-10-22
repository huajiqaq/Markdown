package com.hrm.markdown.renderer.internal.core.compile

import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.parser.ast.CustomContainer
import com.hrm.markdown.parser.ast.Document
import com.hrm.markdown.parser.ast.Heading
import com.hrm.markdown.parser.ast.Node
import com.hrm.markdown.parser.ast.Paragraph
import com.hrm.markdown.parser.ast.Text
import com.hrm.markdown.parser.ast.TocPlaceholder
import com.hrm.markdown.parser.block.postprocessors.BlockPostProcessor
import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.GFMFlavour
import com.hrm.markdown.parser.flavour.MarkdownExtraFlavour
import com.hrm.markdown.parser.flavour.MarkdownFlavour
import com.hrm.markdown.renderer.MarkdownSoftBreakPolicy
import com.hrm.markdown.renderer.internal.core.model.CustomContainerBlockModel
import com.hrm.markdown.renderer.internal.core.model.InternalRenderDocumentModel
import com.hrm.markdown.renderer.internal.core.model.TocBlockModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Compare each committed publication against an independent, uncached renderer compilation. */
class RenderCompileDifferentialTest {
    private val environment = RenderCompileEnvironment(
        config = RenderConfigSnapshot(
            enableHeadingNumbering = true,
            streaming = true,
            softBreakPolicy = MarkdownSoftBreakPolicy.LineBreak,
        ),
    )

    @Test
    fun should_match_cold_compilation_for_syntax_growth_and_reproducible_structural_edits() {
        for ((flavourName, flavour) in flavours) {
            for (case in cases) {
                val parser = MarkdownParser(flavour, appendCoalesceThreshold = 0)
                val session = RenderCompileSession()
                parser.beginStream()
                var source = ""
                val input = case.markdown + "\n\nstable-suffix **safe**\n\nlast-suffix\n"
                val chunkSize = (input.length + 4) / 5
                for ((index, chunk) in input.chunked(chunkSize).withIndex()) {
                    source += chunk
                    verify(parser.append(chunk), session, "$flavourName/${case.name}/append-$index")
                }
                assertEquals(input, source)
                verify(parser.endStream(), session, "$flavourName/${case.name}/end")

                val prefix = "intro with **extra text**\n\n"
                source = prefix + source
                verify(parser.insert(0, prefix), session, "$flavourName/${case.name}/insert-prefix")

                val replacementOffset = source.indexOf(case.before)
                assertTrue(replacementOffset >= 0, "$flavourName/${case.name}: missing edit target")
                source = source.replaceRange(replacementOffset, replacementOffset + case.before.length, case.after)
                verify(
                    parser.replace(replacementOffset, case.before.length, case.after),
                    session,
                    "$flavourName/${case.name}/replace-semantic-input",
                )

                // This intentionally splits different syntax contexts: fence bodies, definition
                // lines, HTML, directives and tables. Every following commit has its own oracle.
                val middleOffset = replacementOffset + case.after.length
                val middle = "\n\ninserted **block**\n\n"
                source = source.substring(0, middleOffset) + middle + source.substring(middleOffset)
                verify(parser.insert(middleOffset, middle), session, "$flavourName/${case.name}/insert-middle")

                source = source.removeRange(middleOffset, middleOffset + middle.length)
                verify(parser.delete(middleOffset, middle.length), session, "$flavourName/${case.name}/delete-middle")

                source = source.removePrefix(prefix)
                verify(parser.delete(0, prefix.length), session, "$flavourName/${case.name}/delete-prefix")
                assertEquals(input.replaceFirst(case.before, case.after), source)
                assertEquals(source, parser.currentText())
            }
        }
    }

    @Test
    fun should_reuse_unchanged_suffix_ir_after_offset_and_line_shifts_in_every_flavour() {
        for ((flavourName, flavour) in flavours) {
            val parser = MarkdownParser(flavour, appendCoalesceThreshold = 0)
            val session = RenderCompileSession()
            val initial = verify(
                parser.parse("first\n\nmiddle\n\nsuffix one\n\nsuffix two\n"),
                session,
                "$flavourName/initial",
            )
            val suffixOne = initial.blocks[initial.blocks.lastIndex - 1]
            val suffixTwo = initial.blocks.last()

            fun verifySuffix(document: Document, phase: String) {
                val result = verify(document, session, "$flavourName/$phase")
                assertFalse(session.lastUpdate.coldRebuild, "$flavourName/$phase traversed the stable suffix")
                assertSame(suffixOne, result.blocks[result.blocks.lastIndex - 1], "$flavourName/$phase")
                assertSame(suffixTwo, result.blocks.last(), "$flavourName/$phase")
            }

            verifySuffix(parser.insert(1, " longer content"), "offset-shift")
            val lineInsertion = "\nnew **block**\n"
            verifySuffix(parser.insert(1, lineInsertion), "line-shift")
            verifySuffix(parser.delete(1, lineInsertion.length), "remove-line-shift")
            verifySuffix(parser.replace(1, " longer content".length, " short"), "shorten-prefix")
        }
    }

    @Test
    fun should_invalidate_global_render_dependencies_created_inside_extension_lazy_content() {
        for (kind in listOf("heading", "toc")) {
            val extension = object : BlockPostProcessor {
                override val priority: Int get() = 999
                override fun processBlock(block: Node): Node {
                    val paragraph = block as? Paragraph ?: return block
                    if (paragraph.rawContent?.trim() != "lazy $kind") return block
                    return if (kind == "heading") {
                        paragraph.apply {
                            clearChildren()
                            setLazyInlineContent("Generated heading") { content, parent ->
                                parent.appendChild(Heading(2).apply {
                                    autoId = "generated-heading"
                                    appendChild(Text(content))
                                })
                            }
                        }
                    } else {
                        CustomContainer(type = "generated").apply {
                            setLazyInlineContent("toc") { _, parent -> parent.appendChild(TocPlaceholder()) }
                        }
                    }
                }
            }
            val flavour = object : MarkdownFlavour by ExtendedFlavour {
                override val postProcessors = ExtendedFlavour.postProcessors + extension
            }
            val parser = MarkdownParser(flavour, appendCoalesceThreshold = 0)
            val session = RenderCompileSession()
            val source = "[TOC]\n\n# Existing\n\nplain tail"
            verify(parser.parse(source), session, "lazy-$kind/initial")
            val result = verify(
                parser.replace(source.indexOf("plain tail"), "plain tail".length, "lazy $kind"),
                session,
                "lazy-$kind/generated-dependency",
            )
            assertTrue(session.lastUpdate.coldRebuild, "A lazy $kind must invalidate document-wide render dependencies")
            if (kind == "heading") {
                val toc = result.blocks.filterIsInstance<TocBlockModel>().single()
                assertEquals(listOf("Existing", "Generated heading"), toc.entries.map { it.text })
            } else {
                val container = result.blocks.filterIsInstance<CustomContainerBlockModel>().single()
                val nestedToc = container.children.single() as TocBlockModel
                assertEquals(listOf("Existing"), nestedToc.entries.map { it.text })
            }
        }
    }

    private fun verify(
        document: Document,
        session: RenderCompileSession,
        context: String,
    ): InternalRenderDocumentModel {
        val incremental = session.compile(document, environment)
        val cold = DefaultRenderModelCompiler.compile(document, environment)
        assertEquals(cold, incremental, context)
        return incremental
    }

    private data class SyntaxCase(val name: String, val markdown: String, val before: String, val after: String)

    private val flavours: List<Pair<String, MarkdownFlavour>> = listOf(
        "commonmark" to CommonMarkFlavour,
        "gfm" to GFMFlavour,
        "extended" to ExtendedFlavour,
        "markdown-extra" to MarkdownExtraFlavour,
    )

    private val cases = listOf(
        SyntaxCase(
            "heading-toc", "[TOC]\n\n# First\n\n## Section\n\nbody\n\n# Last\n",
            "Section", "Changed section",
        ),
        SyntaxCase(
            "link-definition", "[read][ref]\n\n[ref]: /marker \"title\"\n",
            "/marker", "/replacement-longer",
        ),
        SyntaxCase(
            "footnote-order", "a[^first] b[^second]\n\n[^first]: first note\n\n[^second]: second note\n",
            "a[^first] b[^second]", "b[^second] a[^first]",
        ),
        SyntaxCase(
            "abbreviation", "*[API]: marker expansion\n\nAPI and API\n",
            "marker expansion", "replacement expansion",
        ),
        SyntaxCase(
            "bibliography", "[@smith] and [@jones]\n\n[^bibliography]: smith: Smith, marker paper\njones: Jones, other paper\n",
            "marker paper", "updated longer paper",
        ),
        SyntaxCase(
            "directives", "{% alert level=old %}\ncontent **body**\n{% endalert %}\n\ninline {% icon name=star %}\n",
            "level=old", "level=updated",
        ),
        SyntaxCase(
            "fenced-code", "```kotlin title=sample\nval marker = 1\n```\n",
            "val marker = 1", "val marker = 22",
        ),
        SyntaxCase(
            "html", "<div>\n<p>marker <strong>body</strong></p>\n</div>\n",
            "marker", "updated html content",
        ),
        SyntaxCase(
            "nested-list", "- [ ] marker\n  - child **strong**\n- second\n",
            "- [ ] marker", "- [x] checked marker",
        ),
        SyntaxCase(
            "table", "| A | B |\n|---|:---:|\n| marker | **body** |\n",
            "|---|:---:|", "|:---|---:|",
        ),
        SyntaxCase(
            "frontmatter-figure", "---\ntitle: marker\n---\n\n![caption](/image.png){.card}\n",
            "title: marker", "title: updated",
        ),
        SyntaxCase(
            "blank-quote-definition-list", "\n\n> marker\n>\n> second\n\nTerm\n: Description\n\n---\n",
            "> marker", "> updated content",
        ),
    )
}
