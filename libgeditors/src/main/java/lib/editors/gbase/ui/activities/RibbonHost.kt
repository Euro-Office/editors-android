package lib.editors.gbase.ui.activities

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import lib.editors.gbase.ui.commands.Command
import lib.editors.gbase.ui.commands.CommandTab
import lib.editors.gbase.ui.commands.EditorTheme
import lib.editors.gbase.ui.commands.Ribbon

/** Where an editor's commands show: the command sheet on phones, the ribbon on tablets. */
internal interface CommandHost {
    var tabs: List<CommandTab>

    /** The tabs show (the sheet is open; the ribbon always is). */
    val isOpen: Boolean

    /** The tab shown, by its id; null: the first. */
    val shownTabId: String?

    /** Shows the commands, at the tab [tab] if given (by its id). */
    fun show(tab: String? = null)

    /** Shows the tab [id] (when the commands show). */
    fun showTab(id: String)

    /** The editor's state came: commands show what isn't Compose state anew. */
    fun stateChanged()
}

/**
 * The ribbon of an editor on tablets: its [view] goes under the top bar. Pages of commands open
 * as panels beside the document ([openPage]); the tabs are built anew ([build]) when another is
 * shown, so they show what the document has now.
 */
internal class RibbonHost(
    context: Context,
    private val build: () -> List<CommandTab>,
    private val openPage: (Command.Page) -> Unit
) : CommandHost {

    override var tabs by mutableStateOf(emptyList<CommandTab>())
    private var stateRevision by mutableIntStateOf(0)
    private var shownTab by mutableStateOf<String?>("home")
    private var collapsed by mutableStateOf(false)

    override val isOpen: Boolean get() = true

    override val shownTabId: String? get() = shownTab

    val view: ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            EditorTheme {
                Ribbon(
                    tabs = tabs, shownId = shownTab, onShowTab = ::showTab, collapsed = collapsed, onCollapse = { collapsed = it },
                    onOpenPage = openPage, stateRevision = stateRevision
                )
            }
        }
    }

    override fun stateChanged() {
        stateRevision++
    }

    override fun show(tab: String?) {
        collapsed = false
        if (tab != null) showTab(tab)
    }

    override fun showTab(id: String) {
        tabs = build()
        shownTab = id
    }
}
