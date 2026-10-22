package com.hrm.markdown.renderer.internal.layout.engine

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.markdown.parser.MarkdownParser
import com.hrm.markdown.renderer.DiagramHostRegistry
import com.hrm.markdown.renderer.MarkdownRenderOptions
import com.hrm.markdown.renderer.ResolvedMarkdownStyle
import com.hrm.markdown.renderer.internal.MarkdownEngineHost
import com.hrm.markdown.renderer.internal.RendererFacadeState
import com.hrm.markdown.renderer.internal.core.model.HeadingBlockModel
import com.hrm.markdown.renderer.internal.core.model.InternalRenderDocumentModel
import com.hrm.markdown.renderer.internal.layout.model.InternalLayoutBlockModel
import com.hrm.markdown.renderer.internal.layout.model.InternalLayoutDocumentModel
import com.hrm.markdown.renderer.internal.layout.model.LayoutInlineBlockModel
import com.hrm.markdown.renderer.internal.layout.model.LayoutRenderBlockModel
import com.hrm.markdown.renderer.internal.layout.model.LayoutTextRun
import com.hrm.markdown.renderer.internal.layout.model.LayoutTocBlockModel
import com.hrm.markdown.runtime.MarkdownDirectiveRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class MarkdownDerivedLayoutCacheContractTest {
    @Test
    fun should_refreshTocAndItsContainer_when_anOutsideHeadingChangesAtEqualLength() = withLayoutFixture { fixture ->
        for (tocSource in listOf("[TOC]", "> [TOC]")) {
            val parser = MarkdownParser()
            val source = "$tocSource\n\n# First\n\nending"
            val before = fixture.compile(parser.parse(source))
            val oldLayout = fixture.session(before).blockAt(0)
            assertEquals(listOf("First"), oldLayout.toc().entries.map { it.entry.text })

            val after = fixture.compile(parser.replace(source.indexOf("First"), 5, "Other"))
            // 占位符及容器的源码未变，原有 AST 派生版本不足以区分这两份目录。
            assertEquals(before.blocks.first().identity, after.blocks.first().identity)
            val updated = fixture.session(after).blockAt(0)
            assertNotSame(oldLayout, updated)
            assertEquals(listOf("Other"), updated.toc().entries.map { it.entry.text })
            val expected = fixture.eager(after).blocks.first().toc()
            assertEquals(expected.entries, updated.toc().entries)
            assertEquals(expected.frame, updated.toc().frame)
        }
    }

    @Test
    fun should_refreshStableHeadingLayout_when_anEarlierHeadingChangesItsNumber() =
        withLayoutFixture(enableHeadingNumbering = true) { fixture ->
            val parser = MarkdownParser()
            val source = "# First\n\nmiddle\n\n# Target\n\nending"
            val before = fixture.compile(parser.parse(source))
            val oldIndex = before.blocks.indexOfLast { it is HeadingBlockModel }
            val oldHeading = before.blocks[oldIndex] as HeadingBlockModel
            val oldLayout = fixture.session(before).blockAt(oldIndex)
            assertEquals("2 Target", oldLayout.inlineText())

            val after = fixture.compile(parser.insert(0, "# Added\n\n"))
            val newIndex = after.blocks.indexOfLast { it is HeadingBlockModel }
            val newHeading = after.blocks[newIndex] as HeadingBlockModel
            assertEquals(oldHeading.identity, newHeading.identity)
            assertEquals("3", newHeading.numbering)
            val updated = fixture.session(after).blockAt(newIndex)
            assertNotSame(oldLayout, updated)
            assertEquals("3 Target", updated.inlineText())
            assertEquals(fixture.eager(after).blocks[newIndex].inlineText(), updated.inlineText())
        }

    @Test
    fun should_reuseStableIrLayout_when_onlyTheStreamingTailChanges() = withLayoutFixture { fixture ->
        val parser = MarkdownParser()
        parser.beginStream()
        val before = fixture.compile(parser.append("stable\n\ntail"))
        val oldSession = fixture.session(before)
        val stable = oldSession.blockAt(0)
        val oldTail = oldSession.blockAt(1)

        val after = fixture.compile(parser.append(" updated"))
        assertSame(before.blocks.first(), after.blocks.first())
        val updatedSession = fixture.session(after)
        assertSame(stable, updatedSession.blockAt(0))
        assertNotSame(oldTail, updatedSession.blockAt(1))
        assertEquals("tail updated", updatedSession.blockAt(1).inlineText())
    }

    private fun withLayoutFixture(
        enableHeadingNumbering: Boolean = false,
        check: (Fixture) -> Unit,
    ) = runComposeUiTest {
        var fixture: Fixture? = null
        setContent {
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val latexMeasurer = rememberLatexMeasurer()
            val host = remember { MarkdownEngineHost() }
            val registry = remember { DiagramHostRegistry() }
            val facade = remember {
                RendererFacadeState(
                    ResolvedMarkdownStyle(),
                    MarkdownRenderOptions(enableHeadingNumbering = enableHeadingNumbering),
                    MarkdownDirectiveRegistry.Empty,
                    false,
                )
            }
            fixture = Fixture(
                compile = { document -> host.compile(document, facade) },
                session = { document ->
                    host.layoutSession(document, facade, 240f, density = density,
                        textMeasurer = textMeasurer, latexMeasurer = latexMeasurer,
                        diagramHostRegistry = registry)
                },
                eager = { document ->
                    host.layout(document, facade, 240f, density = density,
                        textMeasurer = textMeasurer, latexMeasurer = latexMeasurer,
                        diagramHostRegistry = registry)
                },
            )
        }
        runOnIdle { check(checkNotNull(fixture)) }
    }

    private class Fixture(
        val compile: (com.hrm.markdown.parser.ast.Document) -> InternalRenderDocumentModel,
        val session: (InternalRenderDocumentModel) -> MarkdownLayoutSession,
        val eager: (InternalRenderDocumentModel) -> InternalLayoutDocumentModel,
    )

    private fun InternalLayoutBlockModel.toc(): LayoutTocBlockModel = when (this) {
        is LayoutTocBlockModel -> this
        is LayoutRenderBlockModel -> children.single().toc()
        else -> error("Expected a TOC or its blockquote container")
    }

    private fun InternalLayoutBlockModel.inlineText(): String = (this as LayoutInlineBlockModel)
        .lines.flatMap { it.runs }.filterIsInstance<LayoutTextRun>().joinToString("") { it.text.text }
}
