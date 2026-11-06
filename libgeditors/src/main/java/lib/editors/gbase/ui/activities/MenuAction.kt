package lib.editors.gbase.ui.activities

import android.content.Context
import android.view.Menu
import lib.editors.gbase.ui.commands.Command

/**
 * An action offered in a menu and among the commands alike (inserting a break, tracking changes):
 * it does something, or offers [children] to choose from, or is on or off ([isChecked]).
 */
internal class MenuAction(
    val label: Int,
    val icon: Int? = null,
    val children: List<MenuAction> = emptyList(),
    val isChecked: (() -> Boolean)? = null,
    val isEnabled: () -> Boolean = { true },
    val run: () -> Unit = {}
)

/** Adds [actions] to a menu; the children as a submenu. */
internal fun Menu.addActions(actions: List<MenuAction>) {
    for (action in actions) {
        if (action.children.isNotEmpty()) {
            val sub = addSubMenu(action.label)
            action.icon?.let { sub.setIcon(it) }
            sub.addActions(action.children)
            continue
        }
        val item = add(Menu.NONE, Menu.NONE, Menu.NONE, action.label).setOnMenuItemClickListener {
            action.run()
            true
        }
        action.icon?.let { item.setIcon(it) }
        item.isEnabled = action.isEnabled()
        action.isChecked?.let { checked ->
            item.isCheckable = true
            item.isChecked = checked()
        }
    }
}

/** The command of [action]: its children are chosen from on a page of their own. */
internal fun MenuAction.toCommand(context: Context, panels: PanelHost): Command = when {
    children.isNotEmpty() -> Command.Page(label, icon, isEnabled) {
        panels.choose(context.getString(label), children.map { context.getString(it.label) }, selected = -1) { index -> children[index].run() }
    }
    isChecked != null -> Command.Toggle(label, icon, isChecked, isEnabled, run)
    else -> Command.Action(label, icon, isEnabled, run)
}
