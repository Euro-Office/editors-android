package lib.editors.gbase.ui.activities

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import lib.editors.gbase.ui.commands.CommandSheet
import lib.editors.gbase.ui.commands.CommandTab
import lib.editors.gbase.ui.commands.EditorTheme
import lib.editors.gbase.ui.commands.SheetPage

/**
 * The command sheet of an editor on phones: its [view] goes at the bottom of the activity. It is
 * where [PanelHost] opens panels while it is open, as pages in place of the commands.
 */
internal class CommandSheetHost(
    context: Context,
    private val canUndo: () -> Boolean,
    private val canRedo: () -> Boolean,
    private val onUndo: () -> Unit,
    private val onRedo: () -> Unit,
    /** Closes the panel shown as a page, when the sheet closes with it. */
    private val dismissPanel: () -> Unit,
    /** After the sheet opened or closed: the bars it takes the place of come and go. */
    private val onOpenChanged: (Boolean) -> Unit
) : PanelHost.Pages {

    var tabs by mutableStateOf(emptyList<CommandTab>())

    private var open by mutableStateOf(false)
    private var page by mutableStateOf<SheetPage?>(null)
    private var shownTab by mutableStateOf<String?>(null)

    override val isOpen: Boolean get() = open

    // At most half the screen, so the document shows what is formatted above it
    private val maxHeight = context.resources.displayMetrics.run { heightPixels / density / 2 }.dp

    val view: ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            EditorTheme {
                if (open) {
                    CommandSheet(
                        tabs = tabs, shownId = shownTab, onShowTab = { shownTab = it }, page = page, canUndo = canUndo, canRedo = canRedo, onUndo = onUndo, onRedo = onRedo,
                        onHide = ::close, modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)
                    )
                }
            }
        }
    }

    /** Opens the sheet, at the tab [tab] if given (by its id), else where it was. */
    fun show(tab: String? = null) {
        if (tab != null) shownTab = tab
        if (open) return
        open = true
        onOpenChanged(true)
    }

    fun close() {
        if (!open) return
        if (page != null) dismissPanel()
        page = null
        open = false
        onOpenChanged(false)
    }

    override fun showPage(title: String, content: View, onBack: () -> Unit) {
        page = SheetPage(title, content, onBack)
    }

    override fun closePage() {
        page = null
    }
}
