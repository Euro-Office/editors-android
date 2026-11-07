package lib.editors.gbase.ui.activities

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.SlideTransition
import lib.editors.gbase.ui.commands.Command
import lib.editors.gbase.ui.commands.CommandGroup
import lib.editors.gbase.ui.commands.CommandPanel
import lib.editors.gbase.ui.commands.PictureCell
import lib.editors.gbase.ui.commands.composePanel
import lib.editors.resources.R as Resources

/**
 * The transition to the current slide, as web-apps sets it, as commands (see [commands]) or in a
 * panel (see [PanelHost]): the effect (its icons from web-apps) and its option, the duration,
 * and how the slideshow goes on to the next slide (on a tap, after a delay). Changes apply at
 * once; all slides can take them.
 */
internal class TransitionPanel(
    private val context: Context,
    private val panels: PanelHost,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    /** After a change (the slides' strip shows transitions). */
    private val onChanged: () -> Unit
) {

    private var current by mutableStateOf(DEFAULT)

    /** Takes the current slide's [transition] (none if null) for [commands]. */
    fun load(transition: SlideTransition?) {
        current = transition?.let {
            it.copy(
                type = EFFECTS.firstOrNull { effect -> effect.type == it.type }?.type ?: 0,
                durationMs = it.durationMs.takeIf { ms -> ms > 0 } ?: DEFAULT_DURATION_MS
            )
        } ?: DEFAULT
    }

    /** Shows the panel for the current slide's [transition]; [onBack] to the panel it was opened from. */
    fun show(transition: SlideTransition?, onBack: (() -> Unit)? = null) {
        load(transition)
        panels.show(context.getString(Resources.string.editors_slides_transition), composePanel(context) { CommandPanel { commands() } }, onBack, modal = false) {}
    }

    /** The effect and its option; the timing; applying it to all slides. */
    fun commands(): List<CommandGroup> {
        val transition = current
        val options = OPTIONS[transition.type].orEmpty()
        return listOf(
            CommandGroup(listOfNotNull(
                Command.Custom(Resources.string.editors_slides_effect) { Effects() },
                if (options.isEmpty()) null else Command.Choice(Resources.string.editors_slides_option, null, options.map { context.getString(it.first) }, {
                    options.indexOfFirst { it.second == current.option }
                }) { index -> apply(current.copy(option = options[index].second)) }
            )),
            CommandGroup(listOfNotNull(
                Command.Stepper(Resources.string.editors_slides_duration, value = { seconds(current.durationMs) },
                    down = { apply(current.copy(durationMs = step(current.durationMs, DURATION_STEP_MS, false).coerceIn(DURATION_STEP_MS, MAX_MS))) },
                    up = { apply(current.copy(durationMs = step(current.durationMs, DURATION_STEP_MS, true).coerceIn(DURATION_STEP_MS, MAX_MS))) }),
                Command.Toggle(Resources.string.editors_slides_start_on_tap, null, { current.onClick }) { apply(current.copy(onClick = !current.onClick)) },
                Command.Toggle(Resources.string.editors_slides_delay, null, { current.advanceAfter }) { apply(current.copy(advanceAfter = !current.advanceAfter)) },
                if (!transition.advanceAfter) null else Command.Stepper(Resources.string.editors_slides_delay_after, value = { seconds(current.afterMs) },
                    down = { apply(current.copy(afterMs = step(current.afterMs, DELAY_STEP_MS, false).coerceIn(0, MAX_MS))) },
                    up = { apply(current.copy(afterMs = step(current.afterMs, DELAY_STEP_MS, true).coerceIn(0, MAX_MS))) })
            )),
            CommandGroup(listOf(
                Command.Action(Resources.string.editors_slides_apply_all, null) { save(all = true) }
            ))
        )
    }

    // The effects: an icon with its name each, the chosen one marked
    @Composable
    private fun Effects() {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(Resources.string.editors_slides_effect), Modifier.padding(start = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.bodyLarge)
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (effect in EFFECTS) {
                    val label = stringResource(effect.label)
                    PictureCell(current.type == effect.type, label, onClick = {
                        val options = OPTIONS[effect.type].orEmpty()
                        val option = current.option.takeIf { value -> options.any { it.second == value } } ?: options.firstOrNull()?.second ?: 0
                        apply(current.copy(type = effect.type, option = option))
                    }, modifier = Modifier.width(76.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(painterResource(effect.icon), null, Modifier.size(32.dp))
                            Text(label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }

    // To this slide at once
    private fun apply(transition: SlideTransition) {
        current = transition
        save(all = false)
    }

    private fun save(all: Boolean) {
        val transition = current
        edit {
            setTransition(transition.type, transition.option, transition.durationMs, all)
            setSlideAdvance(transition.onClick, transition.advanceAfter, transition.afterMs, all)
        }
        onChanged()
        // The slides don't show it
        if (all) Toast.makeText(context, Resources.string.editors_slides_applied_all, Toast.LENGTH_SHORT).show()
    }

    private fun seconds(ms: Int): String {
        val value = ms / 1000.0
        val number = if (value == Math.floor(value)) value.toInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.', ',')
        return context.getString(Resources.string.editors_slides_seconds, number)
    }

    // The next multiple of size above or below value
    private fun step(value: Int, size: Int, up: Boolean) = if (up) (value / size + 1) * size else ((value + size - 1) / size - 1) * size

    private class Effect(val type: Int, val label: Int, val icon: Int)

    private companion object {
        const val DEFAULT_DURATION_MS = 1000
        const val DURATION_STEP_MS = 250
        const val DELAY_STEP_MS = 1000
        const val MAX_MS = 600_000
        val DEFAULT = SlideTransition(0, 0, DEFAULT_DURATION_MS, onClick = true, advanceAfter = false, afterMs = 0)

        // Asc.c_oAscSlideTransitionTypes (Morph isn't played by the slideshow)
        val EFFECTS = listOf(
            Effect(0, Resources.string.editors_slides_effect_none, Resources.drawable.ic_editor_transition_none),
            Effect(1, Resources.string.editors_slides_effect_fade, Resources.drawable.ic_editor_transition_fade),
            Effect(2, Resources.string.editors_slides_effect_push, Resources.drawable.ic_editor_transition_push),
            Effect(3, Resources.string.editors_slides_effect_wipe, Resources.drawable.ic_editor_transition_wipe),
            Effect(4, Resources.string.editors_slides_effect_split, Resources.drawable.ic_editor_transition_split),
            Effect(5, Resources.string.editors_slides_effect_uncover, Resources.drawable.ic_editor_transition_uncover),
            Effect(6, Resources.string.editors_slides_effect_cover, Resources.drawable.ic_editor_transition_cover),
            Effect(7, Resources.string.editors_slides_effect_clock, Resources.drawable.ic_editor_transition_clock),
            Effect(8, Resources.string.editors_slides_effect_zoom, Resources.drawable.ic_editor_transition_zoom)
        )

        // Asc.c_oAscSlideTransitionParams of each effect, as web-apps offers them
        private val SIDES = listOf(
            Resources.string.editors_slides_from_left to 2,
            Resources.string.editors_slides_from_top to 3,
            Resources.string.editors_slides_from_right to 4,
            Resources.string.editors_slides_from_bottom to 5
        )
        private val SIDES_AND_CORNERS = SIDES + listOf(
            Resources.string.editors_slides_from_top_left to 6,
            Resources.string.editors_slides_from_top_right to 7,
            Resources.string.editors_slides_from_bottom_left to 8,
            Resources.string.editors_slides_from_bottom_right to 9
        )
        val OPTIONS = mapOf(
            1 to listOf(Resources.string.editors_slides_smoothly to 0, Resources.string.editors_slides_through_black to 1),
            2 to SIDES,
            3 to SIDES_AND_CORNERS,
            4 to listOf(
                Resources.string.editors_slides_vertical_in to 10,
                Resources.string.editors_slides_vertical_out to 11,
                Resources.string.editors_slides_horizontal_in to 12,
                Resources.string.editors_slides_horizontal_out to 13
            ),
            5 to SIDES_AND_CORNERS,
            6 to SIDES_AND_CORNERS,
            7 to listOf(
                Resources.string.editors_slides_clockwise to 14,
                Resources.string.editors_slides_counterclockwise to 15,
                Resources.string.editors_slides_wedge to 16
            ),
            8 to listOf(
                Resources.string.editors_slides_zoom_in to 17,
                Resources.string.editors_slides_zoom_out to 18,
                Resources.string.editors_slides_zoom_rotate to 19
            )
        )
    }
}
