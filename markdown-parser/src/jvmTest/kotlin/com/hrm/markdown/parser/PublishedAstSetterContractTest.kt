package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Check the public setter boundary independently of individual property implementations. */
class PublishedAstSetterContractTest {
    @Test
    fun should_rejectEveryPublicSetter_when_eachBuiltInNodeIsPublished() {
        val nodes = listOf(
            Heading(1), SetextHeading(1), Paragraph(), ThematicBreak(), FencedCodeBlock(),
            IndentedCodeBlock(), BlockQuote(), ListBlock(), ListItem(), HtmlBlock(),
            LinkReferenceDefinition(), Table(), TableHead(), TableBody(), TableRow(), TableCell(),
            FootnoteDefinition(), MathBlock(), DefinitionList(), DefinitionTerm(), DefinitionDescription(),
            Admonition(), FrontMatter(), BlankLine(), TocPlaceholder(), AbbreviationDefinition(),
            CustomContainer(), DiagramBlock(), ColumnsLayout(), ColumnItem(), PageBreak(), DirectiveBlock(),
            TabBlock(), TabItem(), BibliographyDefinition(), Figure(), Text(), SoftLineBreak(), HardLineBreak(),
            Emphasis(), StrongEmphasis(), Strikethrough(), InlineCode(), Link(), Image(), Autolink(),
            InlineHtml(), HtmlEntity(), EscapedChar(), FootnoteReference(), InlineMath(), Highlight(),
            Superscript(), Subscript(), InsertedText(), Emoji(), StyledText(), Abbreviation(), KeyboardInput(),
            DirectiveInline(), CitationReference(), Spoiler(), WikiLink(), RubyText(),
        )
        val document = Document()
        nodes.forEach(document::appendChild)
        document.sealPublication(document.children)
        var checked = 0
        for (node in listOf(document) + nodes) {
            for (setter in node.javaClass.methods.filter {
                it.name.startsWith("set") && '$' !in it.name && it.parameterCount == 1
            }) {
                // Null reference values are sufficient: publication must be checked before assignment.
                val value: Any? = when (setter.parameterTypes.single()) {
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Boolean.TYPE -> false
                    java.lang.Character.TYPE -> '-'
                    else -> null
                }
                try {
                    setter.invoke(node, value)
                    fail("${node.javaClass.simpleName}.${setter.name} allowed mutation after publication")
                } catch (error: InvocationTargetException) {
                    // Non-null Kotlin arguments reject null before entering the setter; use a current
                    // getter value for those properties, so the authority check is actually exercised.
                    if (error.cause is NullPointerException && value == null) {
                        val suffix = setter.name.removePrefix("set")
                        val getter = node.javaClass.methods.firstOrNull {
                            it.parameterCount == 0 && (it.name == "get$suffix" || it.name == "is$suffix")
                        } ?: throw error
                        try {
                            setter.invoke(node, getter.invoke(node))
                            fail("${node.javaClass.simpleName}.${setter.name} allowed mutation after publication")
                        } catch (guard: InvocationTargetException) {
                            assertIs<IllegalStateException>(guard.cause, "${node.javaClass.simpleName}.${setter.name}")
                        }
                    } else {
                        assertIs<IllegalStateException>(error.cause, "${node.javaClass.simpleName}.${setter.name}")
                    }
                }
                checked++
            }
        }
        assertTrue(checked > 200, "Expected all semantic and inherited metadata setters, checked $checked")
    }
}
