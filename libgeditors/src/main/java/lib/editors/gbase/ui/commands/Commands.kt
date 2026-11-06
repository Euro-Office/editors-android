package lib.editors.gbase.ui.commands

import android.view.View

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

    /** Opens a page of its own (in the sheet, with a way back): "Margins", "Bullets". */
    class Page(
        override val label: Int,
        override val icon: Int?,
        override val isEnabled: () -> Boolean = { true },
        val open: () -> Unit
    ) : Command
}

/** The tabs to show: the contextual ones first, as Office puts what is selected first. */
fun List<CommandTab>.ordered(): List<CommandTab> = filter { it.contextual } + filterNot { it.contextual }

/** The tab to show after the tabs changed: the one shown before if it is still there, else the first. */
fun List<CommandTab>.keeping(shownId: String?): CommandTab? = firstOrNull { it.id == shownId } ?: firstOrNull()
