package lib.editors.gbase.ui.commands

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * [content] in Material 3 as a view, for the panels that show views (the command sheet's pages,
 * side panes, bottom sheets). It scrolls with the panel: no lazy lists in it.
 */
fun composePanel(context: Context, content: @Composable () -> Unit): View = ComposeView(context).apply {
    // Composed again when the panel shows it again
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    setContent { EditorTheme(content) }
}

/** A heading over settings that belong together: "Foreground", "Stops". */
@Composable
fun SettingsCaption(text: Int) {
    Text(
        stringResource(text), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge
    )
}
