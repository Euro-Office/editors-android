package lib.editors.gbase.ui.commands

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import lib.editors.resources.R as Resources

/** Material 3 in the editors' accent (day and night), as the views around it have. */
@Composable
fun EditorTheme(content: @Composable () -> Unit) {
    val accent = colorResource(Resources.color.editors_accent)
    val onAccent = colorResource(Resources.color.editors_on_accent)
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = accent, onPrimary = onAccent)
    } else {
        lightColorScheme(primary = accent, onPrimary = onAccent)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
