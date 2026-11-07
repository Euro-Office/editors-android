package lib.editors.gbase.ui.activities

import android.content.Context
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.gbase.ui.commands.Command
import lib.editors.gbase.ui.commands.CommandGroup
import lib.editors.gbase.ui.commands.CommandTab
import lib.editors.resources.R as Resources

/**
 * The commands of spreadsheets, as Excel arranges them: Home (the cells' text, alignment, fill,
 * borders, number format and style; inserting and deleting rows and columns), then the editor's
 * own tabs (Insert, Layout, Review, View), and the tab of the selected drawing. What they do is
 * what the cell format panel ([CellFormatPanel]) does.
 */
internal class CellsCommands(
    private val context: Context,
    private val state: CommandState,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val panels: PanelHost,
    private val cellPanel: CellFormatPanel,
    private val colorPicker: ColorPicker,
    private val hooks: Hooks
) : EditorCommands {

    /** The editor's other parts, opened from the commands. */
    class Hooks(
        val chooseStyle: (current: String, onBack: () -> Unit) -> Unit,
        /** The editor's tabs after Home. */
        val tabs: () -> List<CommandTab>,
        val reloadDrawing: () -> Unit,
        val drawingCommands: (current: () -> ShapePanel.Selected?, change: (EditorDocument.() -> EditorState) -> Unit, back: () -> Unit) -> List<CommandGroup>
    )

    // Fill applies the color chosen last, as Excel's does
    private var fill = FILL

    private val toCommands: () -> Unit = { panels.dismiss() }

    private val toDrawing: () -> Unit = {
        panels.dismiss()
        hooks.reloadDrawing()
    }

    private val changeDrawing: (EditorDocument.() -> EditorState) -> Unit = { command ->
        edit(command)
        hooks.reloadDrawing()
    }

    private val format get() = state.cellFormat

    // A picture, shape or chart is selected: the cells' settings don't apply to it
    private val forCells: () -> Boolean = { state.drawing == null }

    override fun tabs(): List<CommandTab> = (listOfNotNull(
        state.drawing?.let { drawing ->
            CommandTab(DocsCommands.DRAWING_TAB, drawing.title, hooks.drawingCommands({ state.drawing }, changeDrawing, toDrawing), contextual = true)
        },
        CommandTab("home", Resources.string.editors_tab_home, home())
    ) + hooks.tabs()).filter { it.groups.isNotEmpty() || it.content != null }

    private fun home() = listOf(
        CommandGroup(listOf(
            Command.Value(Resources.string.editors_format_font, value = { format.fontName.ifEmpty { "—" } }) { cellPanel.chooseFont(toCommands) },
            Command.Value(Resources.string.editors_format_size, value = { DocsCommands.size(format.fontSize) }) { chooseSize() },
            Command.Segments(Resources.string.editors_format_text, listOf(
                Command.Toggle(Resources.string.editors_bold, Resources.drawable.ic_editor_bold, { format.bold }) { edit { setBold(!format.bold) } },
                Command.Toggle(Resources.string.editors_italic, Resources.drawable.ic_editor_italic, { format.italic }) { edit { setItalic(!format.italic) } },
                Command.Toggle(Resources.string.editors_underline, Resources.drawable.ic_editor_underline, { format.underline }) { edit { setUnderline(!format.underline) } },
                Command.Toggle(Resources.string.editors_format_strikeout, Resources.drawable.ic_editor_strikeout, { format.strikeout }) {
                    edit { setStrikeout(!format.strikeout) }
                }
            )),
            fillCommand(),
            colorCommand()
        )),
        CommandGroup(listOf(
            Command.Segments(Resources.string.editors_format_paragraph, CellFormatPanel.ALIGNMENTS.map { (alignment, icon, name) ->
                Command.Toggle(name, icon, { format.alignment == alignment }, forCells) { edit { setAlignment(alignment) } }
            }, isEnabled = forCells),
            Command.Segments(Resources.string.editors_cells_vertical_align, CellFormatPanel.VERTICAL_ALIGNS.map { (icon, name, value) ->
                Command.Toggle(name, icon, { format.verticalAlign == value }, forCells) { edit { setVerticalAlign(value) } }
            }, isEnabled = forCells),
            Command.Toggle(Resources.string.editors_cells_wrap, Resources.drawable.ic_editor_wrap_text, { format.wrap }, forCells) { edit { setWrap(!format.wrap) } },
            Command.Toggle(Resources.string.editors_cells_merge, Resources.drawable.ic_editor_merge_cells, { format.merged }, forCells) { edit { setMerge(!format.merged) } },
            Command.Choice(Resources.string.editors_text_orientation, null, CellFormatPanel.TEXT_ANGLES.map { context.getString(it.first) }, {
                CellFormatPanel.TEXT_ANGLES.indexOfFirst { it.second == format.angle }
            }, forCells) { index -> edit { setTextAngle(CellFormatPanel.TEXT_ANGLES[index].second) } },
            Command.Segments(Resources.string.editors_format_direction, listOf(
                Command.Toggle(Resources.string.editors_format_ltr, Resources.drawable.ic_editor_text_ltr, { !format.rtl }) { edit { setRtl(false) } },
                Command.Toggle(Resources.string.editors_format_rtl, Resources.drawable.ic_editor_text_rtl, { format.rtl }) { edit { setRtl(true) } }
            ), isEnabled = forCells)
        )),
        CommandGroup(listOf(
            Command.Page(Resources.string.editors_cells_borders, Resources.drawable.ic_editor_border_all, forCells, commands = {
                listOf(CommandGroup(CellFormatPanel.BORDERS.map { (icon, label, kind) -> Command.Action(label, icon) { edit { setBorders(kind) } } }))
            }),
            Command.Choice(Resources.string.editors_cells_number_format, Resources.drawable.ic_editor_format_number,
                CellFormatPanel.NUMBER_FORMATS.map { context.getString(it.first) }, {
                    CellFormatPanel.NUMBER_FORMATS.indexOfFirst { it.second == format.numFormatType }
                }, forCells) { index -> edit { setNumberFormatType(CellFormatPanel.NUMBER_FORMATS[index].second) } },
            Command.Segments(Resources.string.editors_cells_number_format, listOf(
                Command.Toggle(Resources.string.editors_cells_fewer_decimals, Resources.drawable.ic_editor_decimals_less, { false }) { edit { changeDecimals(false) } },
                Command.Toggle(Resources.string.editors_cells_more_decimals, Resources.drawable.ic_editor_decimals_more, { false }) { edit { changeDecimals(true) } }
            ), isEnabled = forCells),
            Command.Value(Resources.string.editors_cells_cell_style, value = { EditorTranslations.cellStyleName(context, format.style) }, isEnabled = forCells) {
                hooks.chooseStyle(format.style, toCommands)
            }
        )),
        // Rows and columns at the selection, as the headings' menus have them
        CommandGroup(listOf(
            Command.Action(Resources.string.editors_cells_insert_above, Resources.drawable.ic_editor_table_row_above, forCells) {
                edit { insertCells(SpreadsheetUi.INSERT_ROWS) }
            },
            Command.Action(Resources.string.editors_cells_insert_left, Resources.drawable.ic_editor_table_column_left, forCells) {
                edit { insertCells(SpreadsheetUi.INSERT_COLUMNS) }
            },
            Command.Action(Resources.string.editors_cells_delete_row, Resources.drawable.ic_editor_table_delete_row, forCells) {
                edit { deleteCells(SpreadsheetUi.DELETE_ROWS) }
            },
            Command.Action(Resources.string.editors_cells_delete_column, Resources.drawable.ic_editor_table_delete_column, forCells) {
                edit { deleteCells(SpreadsheetUi.DELETE_COLUMNS) }
            }
        ))
    )

    /** View: freezing the panes at the active cell, unfreezing them. */
    fun freezeCommands(): List<Command> = listOf(
        Command.Action(Resources.string.editors_cells_freeze, null, { state.sheet != null }) {
            val sheet = state.sheet ?: return@Action
            edit { freezePanes(sheet.col, sheet.row) }
        },
        Command.Action(Resources.string.editors_cells_unfreeze, null, { state.sheet.let { it != null && (it.frozenCol > 0 || it.frozenRow > 0) } }) {
            edit { freezePanes(0, 0) }
        }
    )

    private fun fillCommand() =
        Command.Split(Resources.string.editors_cells_fill_color, Resources.drawable.ic_editor_shading, { "#$fill" }, isEnabled = forCells,
            apply = { edit { setCellFill(if (format.fill.equals(fill, ignoreCase = true)) null else fill) } },
            more = ::chooseFill)

    private fun colorCommand() =
        Command.Split(Resources.string.editors_format_color, Resources.drawable.ic_editor_text_color, { format.color.takeIf { it.isNotEmpty() }?.let { "#$it" } },
            apply = ::chooseColor, more = ::chooseColor)

    override fun barCommands(openSheet: (tab: String) -> Unit): List<Command> = listOf(
        Command.Action(Resources.string.editors_insert, Resources.drawable.ic_editor_insert) { openSheet("insert") },
        Command.Toggle(Resources.string.editors_bold, Resources.drawable.ic_editor_bold, { format.bold }) { edit { setBold(!format.bold) } },
        Command.Toggle(Resources.string.editors_italic, Resources.drawable.ic_editor_italic, { format.italic }) { edit { setItalic(!format.italic) } },
        Command.Toggle(Resources.string.editors_underline, Resources.drawable.ic_editor_underline, { format.underline }) { edit { setUnderline(!format.underline) } },
        Command.Split(Resources.string.editors_cells_fill_color, Resources.drawable.ic_editor_shading, { "#$fill" }, isEnabled = forCells,
            apply = { edit { setCellFill(if (format.fill.equals(fill, ignoreCase = true)) null else fill) } }, more = {}),
        Command.Split(Resources.string.editors_format_color, Resources.drawable.ic_editor_text_color, { format.color.takeIf { it.isNotEmpty() }?.let { "#$it" } },
            apply = { openSheet("home"); chooseColor() }, more = {}),
        Command.Toggle(Resources.string.editors_cells_wrap, Resources.drawable.ic_editor_wrap_text, { format.wrap }, forCells) { edit { setWrap(!format.wrap) } },
        Command.Toggle(Resources.string.editors_cells_merge, Resources.drawable.ic_editor_merge_cells, { format.merged }, forCells) { edit { setMerge(!format.merged) } }
    )

    /** [actions] as one group of commands (none without them), for a tab. */
    fun groups(actions: List<MenuAction>): List<CommandGroup> = actions.map { it.toCommand(context, panels) }
        .takeIf { it.isNotEmpty() }?.let { listOf(CommandGroup(it)) } ?: emptyList()

    /** Back from a page opened from a command: to the commands. */
    val back: () -> Unit get() = toCommands

    private fun chooseFill() = colorPicker.choose(
        context.getString(Resources.string.editors_cells_fill_color), format.fill, Resources.string.editors_color_none, toCommands
    ) { color ->
        if (color != null) fill = color
        edit { setCellFill(color) }
    }

    private fun chooseColor() = colorPicker.choose(
        context.getString(Resources.string.editors_format_color), format.color, null, toCommands
    ) { color -> if (color != null) edit { setTextColor(color) } }

    private fun chooseSize() {
        val current = DocsCommands.SIZES.indexOfFirst { it == format.fontSize }
        panels.choose(context.getString(Resources.string.editors_format_size), DocsCommands.SIZES.map(DocsCommands::size), current) { index ->
            edit { setFontSize(DocsCommands.SIZES[index]) }
        }
    }

    private companion object {
        const val FILL = "FFFF00"
    }
}
