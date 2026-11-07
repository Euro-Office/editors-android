package lib.editors.gbase.ui.activities

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import lib.editors.gbase.ui.commands.EditorTheme
import lib.editors.resources.R as Resources

/**
 * The top bar of all editors (Compose Material 3, in [view]): closing, the file's name, history,
 * what concerns the whole document (navigation, comments, slideshow, people), editing and the
 * file's menu. Buttons that don't apply aren't [Button.isVisible].
 */
internal class EditorTopBar(private val view: ComposeView) {

    /** A button of the bar; [viewing]: it is there while viewing too. */
    class Button(icon: Int, val label: Int, val viewing: Boolean, val accent: Boolean = false) {
        var isVisible by mutableStateOf(false)
        var isEnabled by mutableStateOf(true)
        /** In the accent: others co-editing. */
        var isSelected by mutableStateOf(false)
        var icon by mutableIntStateOf(icon)
        /** A count on it (unsolved comments); 0: none. */
        var count by mutableIntStateOf(0)
        /** What screen readers say instead of [label]. */
        var description by mutableStateOf<String?>(null)
        var onClick: () -> Unit = {}
    }

    var title by mutableStateOf("")

    val close = Button(Resources.drawable.ic_editor_back, Resources.string.editors_close, viewing = true).apply { isVisible = true }
    val undo = Button(Resources.drawable.ic_editor_undo, Resources.string.editors_undo, viewing = false).apply { isVisible = true }
    val redo = Button(Resources.drawable.ic_editor_redo, Resources.string.editors_redo, viewing = false).apply { isVisible = true }
    val navigation = Button(Resources.drawable.ic_editor_outline, Resources.string.editors_doc_navigation, viewing = true)
    val comments = Button(Resources.drawable.ic_editor_comment, Resources.string.editors_comments, viewing = true)
    val slideshow = Button(Resources.drawable.ic_editor_slideshow, Resources.string.editors_slides_slideshow, viewing = true)
    val coEditors = Button(Resources.drawable.ic_editor_people, Resources.string.editors_coediting_people, viewing = true)
    // Editing starts here: in the accent
    val edit = Button(Resources.drawable.ic_editor_edit, Resources.string.editors_edit, viewing = true, accent = true).apply { isVisible = true }
    val file = Button(Resources.drawable.ic_editor_more, Resources.string.editors_file, viewing = true)

    /** The buttons after the title, in their order. */
    val buttons = listOf(undo, redo, navigation, comments, slideshow, coEditors, edit, file)

    init {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent { EditorTheme { Bar() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Bar() {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BarButton(close) },
            actions = { buttons.filter { it.isVisible }.forEach { BarButton(it) } },
            // The activity isn't drawn behind the status bar
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        )
    }

    @Composable
    private fun BarButton(button: Button) {
        val tint = if (button.accent || button.isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified
        IconButton(onClick = { button.onClick() }, enabled = button.isEnabled) {
            val description = button.description ?: stringResource(button.label)
            if (button.count > 0) {
                BadgedBox(badge = { Badge { Text(button.count.toString()) } }) {
                    Icon(painterResource(button.icon), description, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint)
                }
            } else {
                Icon(painterResource(button.icon), description, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint)
            }
        }
    }
}
