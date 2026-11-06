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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    /** Changes as the editor's state comes: what commands show is read again. */
    stateRevision: Int = 0,
    modifier: Modifier = Modifier
) {
    val ordered = tabs.ordered()
    val shown = ordered.keeping(shownId)
    // What commands show may not be Compose state (a preference): drawn anew after each one ran
    var revision by remember { mutableIntStateOf(0) }
    val ran: (() -> Unit) -> Unit = { run -> run(); revision++ }
    val shownRevision = revision + stateRevision
    // The pages of commands opened (Paragraph, then a choice in it), of the tab shown; they stay
    // under pages opened from them (fonts), as those go back to them
    var opened by remember(shown?.id) { mutableStateOf(emptyList<Command>()) }
    // Where each list was scrolled to, by the way to it: going back shows it as it was left; a
    // page opened anew starts at its top
    val scrolled = remember(shown?.id) { mutableMapOf<List<Int>, LazyListState>() }
    val open: (Command) -> Unit = {
        scrolled.remove((opened + it).map(Command::label))
        opened = opened + it
    }
    val close: () -> Unit = { opened = opened.dropLast(1) }
    val listState = scrolled.getOrPut(opened.map(Command::label)) { LazyListState() }
    val top = opened.lastOrNull()
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page != null) {
                    PageTitle(page.title, page.onBack)
                } else if (top != null) {
                    PageTitle(stringResource(top.label), close)
                } else {
                    TabPicker(ordered, shown) { onShowTab(it.id) }
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
            // Scrolled views don't draw over the header: Compose doesn't clip them itself
            if (page != null) {
                // A page's view, built anew for each page
                key(page) {
                    AndroidView(factory = { (page.view.parent as? ViewGroup)?.removeView(page.view); page.view }, modifier = Modifier.fillMaxWidth().clipToBounds())
                }
            } else if (top is Command.Choice) {
                ChoiceList(top, listState) { index ->
                    ran { top.choose(index) }
                    close()
                }
            } else if (top is Command.Page && top.commands != null) {
                CommandList(top.commands.invoke(), listState, shownRevision, ran, open)
            } else if (shown?.content != null) {
                // Built anew for each tab shown, and when the tab is (what is selected changed)
                key(shown.id, shown.content) { AndroidView(factory = { shown.content.invoke() }, modifier = Modifier.fillMaxWidth().clipToBounds()) }
            } else if (shown != null) {
                CommandList(shown.groups, listState, shownRevision, ran, open)
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, onBack: (() -> Unit)?) {
    onBack?.let { back ->
        IconButton(onClick = back) { Icon(painterResource(Resources.drawable.ic_editor_back), stringResource(Resources.string.editors_back)) }
    }
    Text(title, Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
}

// The groups set apart by bands
@Composable
private fun CommandList(groups: List<CommandGroup>, state: LazyListState, revision: Int, ran: (() -> Unit) -> Unit, open: (Command) -> Unit) {
    LazyColumn(state = state) {
        groups.forEachIndexed { index, group ->
            if (index > 0) item { Band() }
            items(group.commands) { CommandRow(it, revision, ran, open) }
        }
    }
}

// A choice's options, the chosen one marked
@Composable
private fun ChoiceList(choice: Command.Choice, state: LazyListState, onChoose: (Int) -> Unit) {
    val selected = choice.selected()
    LazyColumn(state = state) {
        itemsIndexed(choice.options) { index, option -> OptionRow(option, index == selected) { if (index != selected) onChoose(index) } }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/**
 * [groups] as the sheet shows them, for panels that show views (a slide's fill in a bottom
 * sheet): in a column that scrolls with the panel; pages and choices opened from them in their
 * place, with a row back.
 */
@Composable
fun CommandPanel(groups: () -> List<CommandGroup>) {
    var revision by remember { mutableIntStateOf(0) }
    val ran: (() -> Unit) -> Unit = { run -> run(); revision++ }
    var opened by remember { mutableStateOf(emptyList<Command>()) }
    val top = opened.lastOrNull()
    Column {
        if (top != null) {
            ListItem(
                headlineContent = { Text(stringResource(top.label), color = MaterialTheme.colorScheme.primary) },
                leadingContent = { Icon(painterResource(Resources.drawable.ic_editor_back), stringResource(Resources.string.editors_back)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { opened = opened.dropLast(1) }
            )
            HorizontalDivider()
        }
        if (top is Command.Choice) {
            val selected = top.selected()
            top.options.forEachIndexed { index, option ->
                OptionRow(option, index == selected) {
                    if (index != selected) ran { top.choose(index) }
                    opened = opened.dropLast(1)
                }
            }
        } else {
            val shown = (top as? Command.Page)?.commands?.invoke() ?: groups()
            shown.forEachIndexed { index, group ->
                if (index > 0) Band()
                group.commands.forEach { CommandRow(it, revision, ran) { command -> opened = opened + command } }
            }
        }
    }
}

// Sets groups apart
@Composable
private fun Band() {
    Spacer(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest))
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

// What a command shows may not be Compose state (a preference, the engine's state): it is read
// again for each [revision] (a command ran, the editor's state came)
@Composable
private fun CommandRow(command: Command, revision: Int, ran: (() -> Unit) -> Unit, open: (Command) -> Unit) {
    key(revision) { CommandRowContent(command, ran, open) }
}

@Composable
private fun CommandRowContent(command: Command, ran: (() -> Unit) -> Unit, open: (Command) -> Unit) {
    val enabled = command.isEnabled()
    if (command is Command.Segments) {
        MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            command.toggles.forEachIndexed { index, toggle ->
                SegmentedButton(
                    checked = toggle.isOn(),
                    onCheckedChange = { ran(toggle.run) },
                    shape = SegmentedButtonDefaults.itemShape(index, command.toggles.size),
                    enabled = enabled && toggle.isEnabled(),
                    icon = {}
                ) {
                    val icon = toggle.icon
                    if (icon != null) Icon(painterResource(icon), stringResource(toggle.label)) else Text(stringResource(toggle.label), maxLines = 1)
                }
            }
        }
        return
    }
    if (command is Command.Field) {
        FieldRow(command, enabled)
        return
    }
    if (command is Command.Custom) {
        command.content()
        return
    }
    val onClick: (() -> Unit)? = when (command) {
        is Command.Action -> { { ran(command.run) } }
        is Command.Toggle -> { { ran(command.run) } }
        is Command.Value -> command.open
        is Command.Split -> { { ran(command.apply) } }
        is Command.Page -> if (command.commands != null) {
            {
                command.open()
                open(command)
            }
        } else command.open
        is Command.Choice -> { { open(command) } }
        // Its buttons do
        is Command.Stepper -> null
        is Command.Segments, is Command.Field, is Command.Custom -> error("drawn above")
    }
    ListItem(
        headlineContent = { Text(stringResource(command.label)) },
        leadingContent = command.icon?.let { icon -> { CommandIcon(icon, (command as? Command.Split)?.choice?.invoke()) } },
        trailingContent = when (command) {
            // Both open where to choose another: the arrow says so
            is Command.Value -> { { Opens(command.value()) } }
            is Command.Choice -> { { Opens(command.options.getOrNull(command.selected()).orEmpty()) } }
            is Command.Toggle -> { { Switch(checked = command.isOn(), onCheckedChange = null) } }
            is Command.Page -> { { Icon(painterResource(Resources.drawable.ic_editor_chevron_right), null) } }
            is Command.Stepper -> { {
                StepperControls(stringResource(command.label), command.value(), enabled, { ran(command.down) }, { ran(command.up) })
            } }
            is Command.Split -> { {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VerticalDivider(Modifier.height(32.dp))
                    IconButton(onClick = command.more, enabled = enabled) { Icon(painterResource(Resources.drawable.ic_editor_chevron_right), null) }
                }
            } }
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = (if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier).alpha(if (enabled) 1f else 0.38f)
    )
}

// A value, and the arrow of what opens another page
@Composable
private fun Opens(value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(value, Modifier.widthIn(max = 160.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(painterResource(Resources.drawable.ic_editor_chevron_right), null, Modifier.padding(start = 4.dp))
    }
}

// Typed in place, set when typing ends (done, or leaving the field)
@Composable
private fun FieldRow(field: Command.Field, enabled: Boolean) {
    val current = field.value()
    var text by remember(current) { mutableStateOf(current) }
    val focus = LocalFocusManager.current
    val set = { if (text != current) field.set(text) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(field.label)) },
        placeholder = field.placeholder?.let { { Text(stringResource(it)) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (field.number) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            set()
            focus.clearFocus()
        }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).onFocusChanged { if (!it.isFocused) set() }
    )
}

/** A value between buttons that make it smaller and larger, named [label] to screen readers. */
@Composable
fun StepperControls(label: String, value: String, enabled: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDown, enabled = enabled) {
            Icon(painterResource(Resources.drawable.ic_editor_remove), stringResource(Resources.string.editors_commands_less, label))
        }
        Text(value, Modifier.widthIn(min = 56.dp), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        IconButton(onClick = onUp, enabled = enabled) {
            Icon(painterResource(Resources.drawable.ic_editor_add), stringResource(Resources.string.editors_commands_more, label))
        }
    }
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
