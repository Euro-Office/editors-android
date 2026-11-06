package lib.editors.gbase.ui.commands

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import lib.editors.resources.R as Resources

/** A page opened from a command in the sheet (fonts, colors, margins): an existing panel's view. */
class SheetPage(val title: String, val view: View, val onBack: (() -> Unit)?)

/**
 * The command sheet of phones, docked at the bottom: a header with the tab picker (or a page's
 * title and the way back), undo, redo and hiding the sheet; under it the tab's commands as a list,
 * its groups set apart by bands, or the [page] opened from one.
 */
@Composable
fun CommandSheet(
    tabs: List<CommandTab>,
    /** The tab to show, by its id; the first one without it. */
    shownId: String?,
    onShowTab: (String) -> Unit,
    page: SheetPage?,
    canUndo: () -> Boolean,
    canRedo: () -> Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ordered = tabs.ordered()
    val shown = ordered.keeping(shownId)
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page == null) {
                    TabPicker(ordered, shown) { onShowTab(it.id) }
                } else {
                    page.onBack?.let { back ->
                        IconButton(onClick = back) { Icon(painterResource(Resources.drawable.ic_editor_back), stringResource(Resources.string.editors_back)) }
                    }
                    Text(page.title, Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                }
                VerticalDivider(Modifier.height(32.dp).padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                if (page == null) {
                    HeaderButton(Resources.drawable.ic_editor_undo, Resources.string.editors_undo, canUndo(), onUndo)
                    HeaderButton(Resources.drawable.ic_editor_redo, Resources.string.editors_redo, canRedo(), onRedo)
                }
                HeaderButton(Resources.drawable.ic_editor_down, Resources.string.editors_commands_hide, true, onHide)
            }
            HorizontalDivider()
            if (page != null) {
                // A page's view, built anew for each page
                key(page) {
                    AndroidView(factory = { (page.view.parent as? ViewGroup)?.removeView(page.view); page.view }, modifier = Modifier.fillMaxWidth())
                }
            } else if (shown?.content != null) {
                // Built anew for each tab shown
                key(shown.id) { AndroidView(factory = { shown.content.invoke() }, modifier = Modifier.fillMaxWidth()) }
            } else if (shown != null) {
                LazyColumn {
                    shown.groups.forEachIndexed { index, group ->
                        if (index > 0) item { Spacer(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)) }
                        items(group.commands) { CommandRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabPicker(tabs: List<CommandTab>, shown: CommandTab?, onTab: (CommandTab) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.padding(start = 4.dp)) {
            Text(shown?.let { stringResource(it.title) } ?: "", style = MaterialTheme.typography.titleMedium)
            Icon(painterResource(Resources.drawable.ic_editor_unfold), stringResource(Resources.string.editors_commands_tabs), Modifier.padding(start = 4.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (tab in tabs) {
                val selected = tab.id == shown?.id
                DropdownMenuItem(
                    text = { Text(stringResource(tab.title), color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                    onClick = { open = false; onTab(tab) }
                )
            }
        }
    }
}

@Composable
private fun HeaderButton(icon: Int, label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(painterResource(icon), stringResource(label)) }
}

@Composable
private fun CommandRow(command: Command) {
    val enabled = command.isEnabled()
    if (command is Command.Segments) {
        MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            command.toggles.forEachIndexed { index, toggle ->
                SegmentedButton(
                    checked = toggle.isOn(),
                    onCheckedChange = { toggle.run() },
                    shape = SegmentedButtonDefaults.itemShape(index, command.toggles.size),
                    enabled = enabled && toggle.isEnabled(),
                    icon = {}
                ) {
                    val icon = toggle.icon
                    if (icon != null) Icon(painterResource(icon), stringResource(toggle.label)) else Text(stringResource(toggle.label))
                }
            }
        }
        return
    }
    val onClick: () -> Unit = when (command) {
        is Command.Action -> command.run
        is Command.Toggle -> command.run
        is Command.Value -> command.open
        is Command.Split -> command.apply
        is Command.Page -> command.open
        is Command.Segments -> error("drawn above")
    }
    ListItem(
        headlineContent = { Text(stringResource(command.label)) },
        leadingContent = command.icon?.let { icon -> { CommandIcon(icon, (command as? Command.Split)?.choice?.invoke()) } },
        trailingContent = when (command) {
            is Command.Value -> { { Text(command.value(), style = MaterialTheme.typography.bodyLarge) } }
            is Command.Toggle -> { { Switch(checked = command.isOn(), onCheckedChange = null) } }
            is Command.Page -> { { Icon(painterResource(Resources.drawable.ic_editor_chevron_right), null) } }
            is Command.Split -> { {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VerticalDivider(Modifier.height(32.dp))
                    IconButton(onClick = command.more, enabled = enabled) { Icon(painterResource(Resources.drawable.ic_editor_chevron_right), null) }
                }
            } }
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.38f)
    )
}

// A split command's icon shows its color in a bar under it
@Composable
internal fun CommandIcon(icon: Int, color: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(painterResource(icon), null)
        if (color != null) {
            val parsed = runCatching { Color(color.toColorInt()) }.getOrNull()
            if (parsed != null) Box(Modifier.width(24.dp).height(4.dp).background(parsed))
        }
    }
}
