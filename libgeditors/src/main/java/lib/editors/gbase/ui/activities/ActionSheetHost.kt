package lib.editors.gbase.ui.activities

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import lib.editors.gbase.ui.commands.EditorTheme
import lib.editors.resources.R as Resources

/**
 * Actions in a modal bottom sheet, as Office's "…" has the file's: one row each, a switch for
 * those on or off; one with choices shows them in its place, with the way back. Its [view] goes
 * anywhere in the activity (the sheet is a window of its own).
 */
internal class ActionSheetHost(context: Context) {

    // The actions shown and the ones they were opened from; none: closed
    private var stack by mutableStateOf(emptyList<List<MenuAction>>())

    val view: ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { EditorTheme { Sheet() } }
    }

    fun show(actions: List<MenuAction>) {
        stack = listOf(actions)
    }

    private fun close() {
        stack = emptyList()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Sheet() {
        val shown = stack.lastOrNull() ?: return
        ModalBottomSheet(onDismissRequest = ::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LazyColumn(Modifier.navigationBarsPadding()) {
                if (stack.size > 1) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(Resources.string.editors_back)) },
                            leadingContent = { Icon(painterResource(Resources.drawable.ic_editor_back), null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { stack = stack.dropLast(1) }
                        )
                        HorizontalDivider()
                    }
                }
                items(shown) { action -> Row(action) }
            }
        }
    }

    @Composable
    private fun Row(action: MenuAction) {
        val enabled = action.isEnabled()
        val checked = action.isChecked
        ListItem(
            headlineContent = { Text(stringResource(action.label)) },
            leadingContent = action.icon?.let { icon -> { Icon(painterResource(icon), null) } },
            trailingContent = when {
                checked != null -> { { Switch(checked = checked(), onCheckedChange = null) } }
                action.children.isNotEmpty() -> { { Icon(painterResource(Resources.drawable.ic_editor_chevron_right), null) } }
                else -> null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(enabled = enabled) {
                if (action.children.isNotEmpty()) {
                    stack = stack + listOf(action.children)
                } else {
                    // Its dialog or panel opens over the closed sheet
                    close()
                    action.run()
                }
            }.alpha(if (enabled) 1f else 0.38f)
        )
    }
}
