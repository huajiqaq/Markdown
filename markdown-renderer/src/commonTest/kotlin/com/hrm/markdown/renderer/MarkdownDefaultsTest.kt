package com.hrm.markdown.renderer

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.codehigh.renderer.CodeDefaults
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownDefaultsTest {
    @Test
    fun material3ThemeUsesProvidedColorScheme() {
        val colorScheme = lightColorScheme(
            primary = Color(0xFF0057D9),
            onSurface = Color(0xFF101010),
            onSurfaceVariant = Color(0xFF555555),
            surfaceVariant = Color(0xFFE8F0FE),
            outline = Color(0xFF777777),
            outlineVariant = Color(0xFFBBBBBB),
            tertiaryContainer = Color(0xFFFFD8E4),
        )

        val theme = ResolvedMarkdownStyle(colors = MarkdownDefaults.material3Colors(colorScheme))

        assertEquals(colorScheme.onSurface, theme.bodyStyle.color)
        assertEquals(colorScheme.onSurface, theme.headingStyles.first().color)
        assertEquals(colorScheme.primary, theme.colors.link)
        assertEquals(colorScheme.surfaceVariant, theme.colors.containerBackground)
        assertEquals(colorScheme.outline, theme.colors.blockQuoteBorder)
        assertEquals(colorScheme.outlineVariant, theme.colors.divider)
        assertEquals(colorScheme.tertiaryContainer, theme.colors.highlight)
    }

    @Test
    fun should_keepOriginalCodeDefaults_when_markdownPaletteChanges() {
        val colorScheme = darkColorScheme(
            surface = Color(0xFF101010),
            onSurface = Color(0xFFF0F0F0),
        )

        val theme = ResolvedMarkdownStyle(colors = MarkdownDefaults.material3Colors(colorScheme))

        assertEquals(colorScheme.onSurface, theme.bodyStyle.color)
        assertEquals(CodeDefaults.oneDarkProColors(), theme.codeColors)
        assertEquals(14.sp, theme.codeTypography.text.fontSize)
        assertEquals(20.sp, theme.codeTypography.text.lineHeight)
        assertEquals(14.sp, theme.codeTypography.inlineCode.fontSize)
        assertEquals(13.sp, theme.codeTypography.title.fontSize)
        assertEquals(PaddingValues(12.dp), theme.codeLayout.contentPadding)
        assertEquals(RoundedCornerShape(8.dp), theme.codeLayout.shape)
    }
}
