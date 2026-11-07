package lib.editors.gbase.ui.commands

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lib.editors.resources.R as Resources

/**
 * The commands on tablets, as Office's ribbon: a row of [tabs] (what is selected first), and the
 * shown tab's commands in one strip under it, their groups divided, scrolling when they don't
 * fit. The strip can be [collapsed]. Pages open beside the document ([onOpenPage]).
 */
@Composable
fun Ribbon(
    tabs: List<CommandTab>,
    shownId: String?,
    onShowTab: (String) -> Unit,
    collapsed: Boolean,
    onCollapse: (Boolean) -> Unit,
    onOpenPage: (Command.Page) -> Unit,
    stateRevision: Int,
    modifier: Modifier = Modifier
) {
    val ordered = tabs.ordered()
    val shown = ordered.keeping(shownId)
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    for (tab in ordered) {
                        val selected = tab.id == shown?.id
                        Tab(
                            selected = selected,
                            onClick = {
                                onShowTab(tab.id)
                                if (collapsed) onCollapse(false)
                            },
                            text = {
                                Text(
                                    stringResource(tab.title),
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    // What is selected stands out, as Office's contextual tabs
                                    color = when {
                                        tab.contextual -> MaterialTheme.colorScheme.tertiary
                                        selected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            },
                            modifier = Modifier.widthIn(min = 72.dp)
                        )
                    }
                }
                IconButton(onClick = { onCollapse(!collapsed) }) {
                    Icon(
                        painterResource(if (collapsed) Resources.drawable.ic_editor_expand_more else Resources.drawable.ic_editor_expand_less),
                        stringResource(if (collapsed) Resources.string.editors_commands_show else Resources.string.editors_commands_hide)
                    )
                }
            }
            if (!collapsed && shown != null) {
                HorizontalDivider()
                Surface(color = MaterialTheme.colorScheme.surface) {
                    // A tab's own scroll position
                    key(shown.id) {
                        Row(
                            Modifier.fillMaxWidth().height(STRIP_HEIGHT).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Inserting: what each button inserts is said, as Office's Insert tab
                            val labeled = shown.id == "insert"
                            shown.groups.forEachIndexed { index, group ->
                                if (index > 0) VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 10.dp, horizontal = 6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    for (command in group.commands) key(command, stateRevision) { RibbonCommand(command, labeled, onOpenPage) }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RibbonCommand(command: Command, labeled: Boolean, onOpenPage: (Command.Page) -> Unit) {
    val enabled = command.isEnabled()
    val label = stringResource(command.label)
    when (command) {
        is Command.Action -> {
            val icon = command.icon
            if (icon != null && !labeled) {
                IconButton(onClick = command.run, enabled = enabled) { Icon(painterResource(icon), label) }
            } else {
                TextButton(onClick = command.run, enabled = enabled) {
                    if (icon != null) Icon(painterResource(icon), null, Modifier.size(20.dp))
                    Text(label, Modifier.padding(start = if (icon != null) 6.dp else 0.dp), maxLines = 1)
                }
            }
        }
        is Command.Toggle -> ToggleButton(command, enabled && command.isEnabled())
        is Command.Segments -> Row {
            for (toggle in command.toggles) ToggleButton(toggle, enabled && toggle.isEnabled())
        }
        is Command.Split -> Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = command.apply, enabled = enabled) { CommandIcon(command.icon, command.choice()) }
            IconButton(onClick = command.more, enabled = enabled, modifier = Modifier.size(width = 20.dp, height = 40.dp)) {
                Icon(painterResource(Resources.drawable.ic_editor_drop_down), stringResource(Resources.string.editors_commands_more, label))
            }
        }
        // A combo box: the value, opening where to choose another
        is Command.Value -> ComboButton(command.value(), label, enabled, command.open)
        is Command.Choice -> {
            var open by remember { mutableStateOf(false) }
            val selected = command.selected()
            Box {
                ComboButton(command.options.getOrNull(selected)?.let { "$label: $it" } ?: label, label, enabled) { open = true }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    command.options.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            leadingIcon = { RadioButton(selected = index == selected, onClick = null) },
                            onClick = {
                                open = false
                                if (index != selected) command.choose(index)
                            }
                        )
                    }
                }
            }
        }
        is Command.Page -> TextButton(onClick = { onOpenPage(command) }, enabled = enabled) {
            command.icon?.let { Icon(painterResource(it), null, Modifier.size(20.dp)) }
            Text(label, Modifier.padding(start = if (command.icon != null) 6.dp else 0.dp), maxLines = 1)
            Icon(painterResource(Resources.drawable.ic_editor_drop_down), null, Modifier.size(20.dp))
        }
        is Command.Stepper -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
            StepperControls(label, command.value(), enabled, command.down, command.up)
        }
        // In pages only
        is Command.Field, is Command.Custom -> Unit
    }
}

@Composable
private fun ToggleButton(toggle: Command.Toggle, enabled: Boolean) {
    val icon = toggle.icon
    if (icon != null) {
        FilledIconToggleButton(
            checked = toggle.isOn(),
            onCheckedChange = { toggle.run() },
            enabled = enabled,
            colors = IconButtonDefaults.filledIconToggleButtonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent)
        ) { Icon(painterResource(icon), stringResource(toggle.label)) }
    } else {
        FilterChip(selected = toggle.isOn(), onClick = toggle.run, enabled = enabled, label = { Text(stringResource(toggle.label), maxLines = 1) },
            modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun ComboButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 4.dp),
        modifier = Modifier.height(36.dp).padding(horizontal = 2.dp)
    ) {
        Text(text, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        Icon(painterResource(Resources.drawable.ic_editor_drop_down), description, Modifier.size(20.dp))
    }
}

private val STRIP_HEIGHT = 52.dp
