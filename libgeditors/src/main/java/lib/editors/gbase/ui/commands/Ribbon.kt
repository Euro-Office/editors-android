package lib.editors.gbase.ui.commands

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    // The band of the tabs is the top bar's; the commands are on a strip of their own
    val strip = MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 8.dp, top = 4.dp)) {
                    for (tab in ordered) {
                        val selected = tab.id == shown?.id
                        // The shown tab is joined to its commands, as Office's (not when they are hidden)
                        val lifted = selected && !collapsed
                        Surface(
                            onClick = {
                                onShowTab(tab.id)
                                if (collapsed) onCollapse(false)
                            },
                            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                            color = if (lifted) strip else Color.Transparent,
                            modifier = Modifier.semantics { this.selected = selected; role = Role.Tab }
                        ) {
                            Text(
                                stringResource(tab.title),
                                Modifier.widthIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                // What is selected stands out, as Office's contextual tabs
                                color = when {
                                    tab.contextual -> MaterialTheme.colorScheme.tertiary
                                    selected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
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
                Surface(color = strip, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                    // A tab's own scroll position
                    key(shown.id) {
                        Row(
                            Modifier.fillMaxWidth().height(STRIP_HEIGHT).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Inserting: what each button inserts is said, as Office's Insert tab
                            val labeled = shown.id == "insert"
                            shown.groups.forEachIndexed { index, group ->
                                if (index > 0) VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 12.dp, horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    for (command in group.commands) key(command) {
                                        // A choice's menu stays open while the editor's state comes
                                        val menuOpen = remember { mutableStateOf(false) }
                                        key(stateRevision) { RibbonCommand(command, labeled, onOpenPage, menuOpen) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun RibbonCommand(command: Command, labeled: Boolean, onOpenPage: (Command.Page) -> Unit, menuOpen: MutableState<Boolean>) {
    val enabled = command.isEnabled()
    val label = stringResource(command.label)
    when (command) {
        is Command.Action -> {
            val icon = command.icon
            if (icon != null && !labeled) {
                IconButton(onClick = command.run, enabled = enabled) { Icon(painterResource(icon), label) }
            } else {
                TextButton(onClick = command.run, enabled = enabled, shape = BUTTON_SHAPE, colors = neutralText()) {
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
        // None ("—": text of several fonts, nothing selected): the box's name, as Office's
        is Command.Value -> command.value().let { value ->
            ComboButton(if (value == NONE) label else value, label, enabled, placeholder = value == NONE, onClick = command.open)
        }
        is Command.Choice -> {
            var open by menuOpen
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
        is Command.Page -> TextButton(onClick = { onOpenPage(command) }, enabled = enabled, shape = BUTTON_SHAPE, colors = neutralText()) {
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
            // On: a quiet tone, as Office marks it
            shape = BUTTON_SHAPE,
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer, checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                disabledContainerColor = Color.Transparent
            )
        ) { Icon(painterResource(icon), stringResource(toggle.label)) }
    } else {
        FilterChip(selected = toggle.isOn(), onClick = toggle.run, enabled = enabled, label = { Text(stringResource(toggle.label), maxLines = 1) },
            modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun ComboButton(text: String, description: String, enabled: Boolean, placeholder: Boolean = false, onClick: () -> Unit) {
    // A field, as Office's font box
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(start = 12.dp, end = 4.dp),
        modifier = Modifier.height(36.dp).padding(horizontal = 2.dp)
    ) {
        Text(
            text, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
            color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
        )
        Icon(painterResource(Resources.drawable.ic_editor_drop_down), description, Modifier.size(20.dp))
    }
}

// Labels in the text's color, not the accent: the strip is calm, as Office's
@Composable
private fun neutralText() = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)

private val BUTTON_SHAPE = RoundedCornerShape(8.dp)
private const val NONE = "—"
private val STRIP_HEIGHT = 52.dp
