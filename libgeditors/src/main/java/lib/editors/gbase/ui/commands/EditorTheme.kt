package lib.editors.gbase.ui.commands

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import lib.editors.resources.R as Resources

/**
 * Material 3 in the editors' accent (day and night), with neutral greys as the views around it
 * have (the document's background, the bars): Material's own surfaces are tinted.
 */
@Composable
fun EditorTheme(content: @Composable () -> Unit) {
    val accent = colorResource(Resources.color.editors_accent)
    val onAccent = colorResource(Resources.color.editors_on_accent)
    val bar = colorResource(Resources.color.editors_bar)
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = accent, onPrimary = onAccent,
            secondaryContainer = Color(0xFF1E3A4C), onSecondaryContainer = Color(0xFFCCE6F4),
            // What is selected (a shape's, a chart's tab)
            tertiary = Color(0xFFFFB74D), onTertiary = Color(0xFF3E2500),
            background = Color(0xFF202124), onBackground = Color(0xFFE3E3E3),
            surface = Color(0xFF202124), onSurface = Color(0xFFE3E3E3),
            surfaceVariant = Color(0xFF3A3A3A), onSurfaceVariant = Color(0xFFC4C4C4),
            surfaceContainerLowest = Color(0xFF161616), surfaceContainerLow = Color(0xFF242424),
            surfaceContainer = bar, surfaceContainerHigh = Color(0xFF333333), surfaceContainerHighest = Color(0xFF3D3D3D),
            surfaceBright = Color(0xFF383838), surfaceDim = Color(0xFF161616),
            outline = Color(0xFF8E8E8E), outlineVariant = Color(0xFF474747)
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = onAccent,
            secondaryContainer = Color(0xFFCCE6F4), onSecondaryContainer = Color(0xFF001E30),
            tertiary = Color(0xFFB45F06), onTertiary = Color.White,
            background = Color(0xFFE8E8E8), onBackground = Color(0xFF1F1F1F),
            surface = Color.White, onSurface = Color(0xFF1F1F1F),
            surfaceVariant = Color(0xFFE8E8E8), onSurfaceVariant = Color(0xFF474747),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F8F8),
            surfaceContainer = bar, surfaceContainerHigh = Color(0xFFF1F1F1), surfaceContainerHighest = Color(0xFFE6E6E6),
            surfaceBright = Color.White, surfaceDim = Color(0xFFDDDDDD),
            outline = Color(0xFF757575), outlineVariant = Color(0xFFD6D6D6)
        )
    }
    MaterialTheme(colorScheme = colors) {
        // Icons and text outside a Surface (panels' pictures) in the theme's, not black
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}
