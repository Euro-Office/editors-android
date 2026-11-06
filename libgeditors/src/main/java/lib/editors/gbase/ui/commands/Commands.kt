package lib.editors.gbase.ui.commands

import android.view.View
import androidx.compose.runtime.Composable

/**
 * An editor's commands: tabs ("Home", "Insert", …) of groups of commands. Phones show them in the
 * command sheet at the bottom, one tab at a time as a list; large screens in the ribbon, a tab's
 * groups in one row. What a command shows (on, its value, its color) is read when it is drawn, so
 * the editor's format kept in Compose state updates it.
 */
class CommandTab(
    /** Tells tabs apart across rebuilds: the tab shown stays shown. */
    val id: String,
    val title: Int,
    val groups: List<CommandGroup>,
    /** Shown for what is selected (a picture, a table) and only then; first while it is. */
    val contextual: Boolean = false,
    /** A view shown in place of commands: settings not yet made commands (a drawing's). */
    val content: (() -> View)? = null
)

/** Commands that belong together; the sheet sets groups apart with a band, the ribbon with a line. */
class CommandGroup(val commands: List<Command>)

sealed interface Command {
    val label: Int
    val icon: Int?
    val isEnabled: () -> Boolean

    /** Does what it says at once: "Clear formatting". */
    class Action(
        override val label: Int,
        override val icon: Int?,
        override val isEnabled: () -> Boolean = { true },
        val run: () -> Unit
    ) : Command

    /** On or off: "Bold". */
    class Toggle(
        override val label: Int,
        override val icon: Int?,
        val isOn: () -> Boolean,
        override val isEnabled: () -> Boolean = { true },
        val run: () -> Unit
    ) : Command

    /** Toggles side by side, as one row of the sheet: bold, italic, underline; the alignments. */
    class Segments(
        override val label: Int,
        val toggles: List<Toggle>,
        override val isEnabled: () -> Boolean = { true }
    ) : Command {
        override val icon: Int? get() = null
    }

    /** Shows its current value and opens where to choose another: the font, its size, the style. */
    class Value(
        override val label: Int,
        override val icon: Int? = null,
        val value: () -> String,
        override val isEnabled: () -> Boolean = { true },
        val open: () -> Unit
    ) : Command

    /** Applies its last choice, or opens where to choose another: the text's color, highlighting. */
    class Split(
        override val label: Int,
        override val icon: Int,
        /** The choice it applies, a color as "#rrggbb", or none. */
        val choice: () -> String?,
        override val isEnabled: () -> Boolean = { true },
        val apply: () -> Unit,
        val more: () -> Unit
    ) : Command

    /**
     * Opens a page of its own (in the sheet, with a way back): of [commands] ("Paragraph"), or
     * one [open] shows ("Margins", "Bullets"). With [commands], [open] runs as it opens (loads
     * what they show).
     */
    class Page(
        override val label: Int,
        override val icon: Int?,
        override val isEnabled: () -> Boolean = { true },
        val commands: (() -> List<CommandGroup>)? = null,
        val open: () -> Unit = {}
    ) : Command

    /** One of [options] (their names), the [selected] one shown; they open as a page to choose from. */
    class Choice(
        override val label: Int,
        override val icon: Int? = null,
        val options: List<String>,
        /** Its index in [options]; -1: none of them. */
        val selected: () -> Int,
        override val isEnabled: () -> Boolean = { true },
        val choose: (Int) -> Unit
    ) : Command

    /** Text typed in place (a chart's title), or a [number]; [set] when typing ends. */
    class Field(
        override val label: Int,
        val value: () -> String,
        val number: Boolean = false,
        /** Shown while it is empty ("Auto"). */
        val placeholder: Int? = null,
        override val isEnabled: () -> Boolean = { true },
        val set: (String) -> Unit
    ) : Command {
        override val icon: Int? get() = null
    }

    /** What isn't one of the others, drawn by [content]: pictures to choose from (patterns), a preview. */
    class Custom(
        override val label: Int,
        val content: @Composable () -> Unit
    ) : Command {
        override val icon: Int? get() = null
        override val isEnabled: () -> Boolean get() = { true }
    }

    /** A value made smaller or larger a step at a time: line spacing, space before. */
    class Stepper(
        override val label: Int,
        override val icon: Int? = null,
        val value: () -> String,
        override val isEnabled: () -> Boolean = { true },
        val down: () -> Unit,
        val up: () -> Unit
    ) : Command
}

/** The tabs to show: the contextual ones first, as Office puts what is selected first. */
fun List<CommandTab>.ordered(): List<CommandTab> = filter { it.contextual } + filterNot { it.contextual }

/** The tab to show after the tabs changed: the one shown before if it is still there, else the first. */
fun List<CommandTab>.keeping(shownId: String?): CommandTab? = firstOrNull { it.id == shownId } ?: firstOrNull()
