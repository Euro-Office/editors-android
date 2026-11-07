package lib.editors.gbase.ui.activities

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.ColorSchemes
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.PageSize
import lib.editors.base.engine.SheetSetup
import lib.editors.gbase.ui.commands.Command
import lib.editors.gbase.ui.commands.CommandGroup
import lib.editors.gbase.ui.commands.CommandPanel
import lib.editors.gbase.ui.commands.composePanel
import kotlin.math.abs
import lib.editors.resources.R as Resources

/**
 * Page setup of presentations (slide size) and spreadsheets (gridlines, the pages' orientation
 * and size), in panels as web-apps' mobile settings, and the color schemes of all editors.
 * Choices are applied as they are made.
 */
internal class PageSettingsUi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val panels: PanelHost,
    private val edit: (EditorDocument.() -> EditorState) -> Unit
) {

    /** The color schemes, each with its name and accent colors, the current one marked; then [onBack]. */
    fun chooseColorScheme(onBack: () -> Unit) = read({ colorSchemes() }) { found -> showColorSchemes(found, onBack) }

    private fun showColorSchemes(found: ColorSchemes, onBack: () -> Unit) {
        val content = composePanel(context) {
            Column(Modifier.padding(bottom = 16.dp)) {
                found.schemes.forEachIndexed { index, scheme ->
                    val current = index == found.current
                    ListItem(
                        headlineContent = { Text(scheme.name) },
                        leadingContent = { RadioButton(selected = current, onClick = null) },
                        // The accents, as web-apps shows a scheme
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                for (color in scheme.colors.drop(ACCENTS_FROM).take(ACCENTS)) {
                                    Box(Modifier.size(width = 12.dp, height = 22.dp).clip(RoundedCornerShape(3.dp)).background(Color(android.graphics.Color.parseColor("#$color"))))
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(selected = current, role = Role.RadioButton) {
                            if (!current) edit { setColorScheme(index) }
                            onBack()
                        }
                    )
                }
            }
        }
        panels.show(context.getString(Resources.string.editors_color_scheme), content, onBack) {}
    }

    /** Presentations: the slides' size and the color scheme. */
    fun showSlides() = showCommands { back -> listOf(CommandGroup(slideCommands(back))) }

    /** Spreadsheets: the active sheet's gridlines, its pages' orientation and size, and the color scheme. */
    fun showSheet() = showCommands(::sheetCommands)

    // Commands in a panel; their pages come back to it
    private fun showCommands(commands: (back: () -> Unit) -> List<CommandGroup>) {
        lateinit var show: () -> Unit
        show = { panels.show(context.getString(Resources.string.editors_doc_page_setup), composePanel(context) { CommandPanel { commands(show) } }) {} }
        show()
    }

    // The slides' size, for the Design tab's commands (loaded as they are built; null before)
    private var slideSize by mutableStateOf<PageSize?>(null)

    /** Presentations: the slides' size (16:9 or 4:3) and the color scheme, as commands; pages go [back]. */
    fun slideCommands(back: () -> Unit): List<Command> {
        read({ slideSize() }) { slideSize = it }
        fun change(command: EditorDocument.() -> EditorState) {
            edit(command)
            read({ slideSize() }) { slideSize = it }
        }
        val ratios = listOf(16.0 / 9, 4.0 / 3)
        return listOf(
            Command.Choice(Resources.string.editors_slide_size, null, listOf("16:9", "4:3"), {
                val size = slideSize
                if (size == null || size.heightMm <= 0) -1 else ratios.indexOfFirst { abs(size.widthMm / size.heightMm - it) < 0.01 }
            }) { index -> change { setSlideSize(wide = index == 0) } },
            Command.Page(Resources.string.editors_color_scheme, null) { chooseColorScheme(back) }
        )
    }

    // The active sheet's setup, for the commands (loaded as they are built; null before)
    private var sheetSetup by mutableStateOf<SheetSetup?>(null)

    /** Spreadsheets: gridlines, the pages' orientation and size, and the color scheme, as commands; pages go [back]. */
    fun sheetCommands(back: () -> Unit): List<CommandGroup> {
        read({ sheetSetup() }) { sheetSetup = it }
        fun change(command: EditorDocument.() -> EditorState) {
            edit(command)
            read({ sheetSetup() }) { sheetSetup = it }
        }
        return listOf(
            CommandGroup(listOf(
                Command.Toggle(Resources.string.editors_cells_gridlines, Resources.drawable.ic_editor_border_all, { sheetSetup?.gridlines == true }) {
                    val on = sheetSetup?.gridlines == true
                    change { setGridlines(!on) }
                }
            )),
            CommandGroup(listOf(
                Command.Segments(Resources.string.editors_doc_orientation, listOf(
                    Command.Toggle(Resources.string.editors_doc_portrait, null, { sheetSetup?.landscape == false }) { change { setSheetPage(landscape = false) } },
                    Command.Toggle(Resources.string.editors_doc_landscape, null, { sheetSetup?.landscape == true }) { change { setSheetPage(landscape = true) } }
                )),
                Command.Choice(Resources.string.editors_doc_size, Resources.drawable.ic_editor_page_setup, PAPER_SIZES.map { it.first }, {
                    val setup = sheetSetup
                    if (setup == null) -1 else PAPER_SIZES.indexOfFirst { (_, width, height) ->
                        abs(minOf(setup.width, setup.height) - width) < 1 && abs(maxOf(setup.width, setup.height) - height) < 1
                    }
                }) { index -> change { setSheetPage(width = PAPER_SIZES[index].second, height = PAPER_SIZES[index].third) } },
                Command.Page(Resources.string.editors_color_scheme, null) { chooseColorScheme(back) }
            ))
        )
    }

    // Reads from the document on the engine's thread, then shows the result
    private fun <T> read(query: EditorDocument.() -> T, onResult: (T) -> Unit) {
        val document = document() ?: return
        scope.launch {
            val result = withContext(EditorEngine.dispatcher) { runCatching { document.query() }.getOrNull() } ?: return@launch
            onResult(result)
        }
    }

    companion object {
        // Name, width and height (portrait) in mm
        val PAPER_SIZES = listOf(
            Triple("A4", 210.0, 297.0),
            Triple("A5", 148.0, 210.0),
            Triple("Letter", 215.9, 279.4),
            Triple("Legal", 215.9, 355.6)
        )
        // A scheme's accents 1 to 6 (after dark 1, light 1, dark 2, light 2)
        const val ACCENTS_FROM = 4
        const val ACCENTS = 6
    }
}
