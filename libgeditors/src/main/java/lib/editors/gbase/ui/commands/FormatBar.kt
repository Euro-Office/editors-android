package lib.editors.gbase.ui.commands

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import lib.editors.resources.R as Resources

/**
 * The bar floating over the document while the command sheet is closed, as Office's while typing:
 * the most used [commands] as buttons, then one that opens the sheet ([onMore]). Values and pages
 * aren't buttons: they are in the sheet.
 */
@Composable
fun FormatBar(commands: List<Command>, onMore: () -> Unit, modifier: Modifier = Modifier) {
    // At most the window's width: views around may measure it without a bound (a LinearLayout's
    // weight), which a scrolling row doesn't allow
    val maxWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    Surface(
        modifier = modifier.widthIn(max = maxWidth),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (command in commands) BarButton(command)
            IconButton(onClick = onMore) {
                Icon(painterResource(Resources.drawable.ic_editor_more), stringResource(Resources.string.editors_commands_show))
            }
        }
    }
}

@Composable
private fun BarButton(command: Command) {
    val enabled = command.isEnabled()
    when (command) {
        is Command.Toggle -> FilledIconToggleButton(
            checked = command.isOn(),
            onCheckedChange = { command.run() },
            enabled = enabled,
            // Off and disabled alike without a background: only on has one
            colors = IconButtonDefaults.filledIconToggleButtonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent)
        ) { command.icon?.let { Icon(painterResource(it), stringResource(command.label)) } }
        is Command.Segments -> command.toggles.forEach { BarButton(it) }
        is Command.Split -> IconButton(onClick = command.apply, enabled = enabled) { CommandIcon(command.icon, command.choice()) }
        is Command.Action -> IconButton(onClick = command.run, enabled = enabled) {
            command.icon?.let { Icon(painterResource(it), stringResource(command.label)) }
        }
        is Command.Page, is Command.Value, is Command.Stepper, is Command.Choice, is Command.Field, is Command.Custom -> Unit
    }
}
