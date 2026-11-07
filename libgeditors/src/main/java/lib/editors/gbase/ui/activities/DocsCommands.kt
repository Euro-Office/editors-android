package lib.editors.gbase.ui.activities

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.ListKind
import lib.editors.base.engine.TextFormat
import lib.editors.gbase.ui.commands.Command
import lib.editors.gbase.ui.commands.CommandGroup
import lib.editors.gbase.ui.commands.CommandTab
import lib.toolkit.base.managers.utils.EditorDictionaries
import lib.editors.resources.R as Resources

/** What the commands show, kept as Compose state: the editor's state sets it, the commands read it. */
internal class CommandState {
    var format by mutableStateOf(TextFormat())
    /** A picture, shape or chart is selected (not text in it): paragraph commands don't apply. */
    var drawingSelected by mutableStateOf(false)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    /** The selected drawings (a chart, a shape, a picture), loaded after they were selected; none: no tab of theirs. */
    var drawing by mutableStateOf<ShapePanel.Selected?>(null)
    var inTable by mutableStateOf(false)
}

/** An editor's commands for the command sheet: its tabs, and the floating bar's while it is closed. */
internal interface EditorCommands {
    fun tabs(): List<CommandTab>

    /** [openSheet] opens the sheet at a tab (by its id). */
    fun barCommands(openSheet: (tab: String) -> Unit): List<Command>
}

/**
 * The commands of text documents and presentations, as Office arranges them: Home (the text, its
 * paragraphs and style), then the editor's own tabs (Insert, Layout or Design, Review, View),
 * and the tabs of what is selected (a drawing, a table). Pages open where the panels are.
 */
internal class DocsCommands(
    private val context: Context,
    private val state: CommandState,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val panels: PanelHost,
    private val formatPanel: FormatPanel,
    private val colorPicker: ColorPicker,
    private val features: Features,
    private val hooks: Hooks
) : EditorCommands {

    /** What the editor's text has beyond what all have: text documents' paragraph styles, shading, numbering and options. */
    class Features(val styles: Boolean, val shading: Boolean, val numbering: Boolean, val advanced: Boolean) {
        companion object {
            val DOCS = Features(styles = true, shading = true, numbering = true, advanced = true)
            val SLIDES = Features(styles = false, shading = false, numbering = false, advanced = false)
        }
    }

    /** The editor's other parts, opened from the commands. */
    class Hooks(
        val chooseList: (kind: ListKind, onBack: () -> Unit) -> Unit,
        val chooseStyle: (current: String, onBack: () -> Unit) -> Unit,
        val chooseTableStyle: (onBack: () -> Unit) -> Unit,
        /** Home's first commands (presentations: a new slide, its layout). */
        val homeFirst: () -> List<Command> = { emptyList() },
        /** The editor's tabs after Home. */
        val tabs: () -> List<CommandTab>,
        /** The selected drawing from the engine anew: what was set in its tab shows. */
        val reloadDrawing: () -> Unit,
        /** The selected drawings' commands (see [ShapePanel.commands]). */
        val drawingCommands: (current: () -> ShapePanel.Selected?, change: (EditorDocument.() -> EditorState) -> Unit, back: () -> Unit) -> List<CommandGroup>
    )

    // Highlighting applies the color chosen last, as Office's does
    private var highlight = HIGHLIGHT

    // Back from a page opened from a command: to the commands
    private val toCommands: () -> Unit = { panels.dismiss() }

    // Back to the drawing's tab, or a setting made in it (the chart's part): it shows anew
    private val toDrawing: () -> Unit = {
        panels.dismiss()
        hooks.reloadDrawing()
    }

    // A drawing's setting, then the drawing anew
    private val changeDrawing: (EditorDocument.() -> EditorState) -> Unit = { command ->
        edit(command)
        hooks.reloadDrawing()
    }

    private val format get() = state.format
    private val forText: () -> Boolean = { !state.drawingSelected }

    override fun tabs(): List<CommandTab> = (listOfNotNull(
        // What is selected first, as its own tab (Office's contextual tabs)
        state.drawing?.let { drawing ->
            CommandTab(DRAWING_TAB, drawing.title, hooks.drawingCommands({ state.drawing }, changeDrawing, toDrawing), contextual = true)
        },
        if (state.inTable) CommandTab(TABLE_TAB, Resources.string.editors_doc_table_menu, table(), contextual = true) else null,
        CommandTab("home", Resources.string.editors_tab_home, home())
    ) + hooks.tabs()).filter { it.groups.isNotEmpty() || it.content != null }

    private fun home() = listOfNotNull(
        hooks.homeFirst().takeIf { it.isNotEmpty() }?.let { CommandGroup(it) },
        CommandGroup(listOf(
            fontCommand(),
            Command.Value(Resources.string.editors_format_size, value = { size(format.fontSize) }) { chooseSize() },
            textStyles(),
            highlightCommand(),
            colorCommand(),
            clearCommand(),
            Command.Page(Resources.string.editors_format_more, null, commands = ::textPage)
        )),
        CommandGroup(listOf(
            *listCommands(),
            indentCommand(),
            alignmentCommand(),
            Command.Page(Resources.string.editors_format_paragraph, null, isEnabled = forText, commands = ::paragraphPage)
        )),
        if (features.styles) CommandGroup(listOf(styleCommand())) else null
    )

    // "More formatting": all of the text's own settings, as Office's "Font formatting" page
    private fun textPage() = listOf(
        CommandGroup(listOfNotNull(
            fontCommand(),
            Command.Stepper(Resources.string.editors_format_size, value = { size(format.fontSize) },
                down = { if (format.fontSize > 1) edit { setFontSize(format.fontSize - 1) } },
                up = { if (format.fontSize > 0) edit { setFontSize(format.fontSize + 1) } }),
            formatPanel.chooseTextLanguage?.let { choose ->
                Command.Value(Resources.string.editors_format_language, Resources.drawable.ic_editor_language,
                    value = { format.languageTag.ifEmpty { null }?.let { tag -> EditorDictionaries.languageName(tag) } ?: "—" }) { choose(toCommands) }
            }
        )),
        CommandGroup(listOf(
            textStyles(),
            Command.Segments(Resources.string.editors_format_superscript, listOf(
                Command.Toggle(Resources.string.editors_format_subscript, Resources.drawable.ic_editor_subscript, { format.baseline == 1 }) {
                    edit { setBaseline(if (format.baseline == 1) 0 else 1) }
                },
                Command.Toggle(Resources.string.editors_format_superscript, Resources.drawable.ic_editor_superscript, { format.baseline == 2 }) {
                    edit { setBaseline(if (format.baseline == 2) 0 else 2) }
                }
            )),
            Command.Toggle(Resources.string.editors_format_all_caps, null, { format.caps }) { edit { setCaps(!format.caps) } },
            Command.Toggle(Resources.string.editors_format_small_caps, null, { format.smallCaps }) { edit { setSmallCaps(!format.smallCaps) } }
        )),
        CommandGroup(listOf(highlightCommand(), colorCommand(), clearCommand()))
    )

    // "Paragraph": its style, lists, alignment, indents, direction and background; its spacing
    // and the advanced settings (what keeps it together)
    private fun paragraphPage(): List<CommandGroup> = listOf(
        CommandGroup(listOfNotNull(
            if (features.styles) styleCommand() else null,
            *listCommands(),
            // In a numbered list
            if (features.numbering && format.list == 1) Command.Action(Resources.string.editors_list_restart, null, forText) { edit { restartNumbering() } } else null,
            if (features.numbering && format.list == 1) Command.Action(Resources.string.editors_list_continue, null, forText) { edit { continueNumbering() } } else null
        )),
        CommandGroup(listOfNotNull(
            alignmentCommand(),
            indentCommand(),
            Command.Segments(Resources.string.editors_format_direction, listOf(
                Command.Toggle(Resources.string.editors_format_ltr, Resources.drawable.ic_editor_text_ltr, { !format.rtl }) { edit { setRtl(false) } },
                Command.Toggle(Resources.string.editors_format_rtl, Resources.drawable.ic_editor_text_rtl, { format.rtl }) { edit { setRtl(true) } }
            ), isEnabled = forText),
            if (features.shading) {
                Command.Split(Resources.string.editors_format_paragraph_background, Resources.drawable.ic_editor_shading,
                    { format.shading.takeIf { it.isNotEmpty() }?.let { "#$it" } }, isEnabled = forText,
                    apply = ::chooseShading, more = ::chooseShading)
            } else null
        )),
        CommandGroup(listOf(
            Command.Segments(Resources.string.editors_format_line_spacing, listOf(
                Command.Toggle(Resources.string.editors_format_line_multiple, null, { format.lineRule == FormatPanel.LINE_MULTIPLE }) {
                    if (format.lineRule != FormatPanel.LINE_MULTIPLE) edit { setLineSpacing(FormatPanel.LINE_MULTIPLE, 1.0) }
                }
            ) + listOf(FormatPanel.LINE_AT_LEAST to Resources.string.editors_format_line_at_least, FormatPanel.LINE_EXACTLY to Resources.string.editors_format_line_exactly)
                .map { (rule, label) ->
                    Command.Toggle(label, null, { format.lineRule == rule }) {
                        if (format.lineRule != rule) edit { setLineSpacing(rule, FormatPanel.DEFAULT_LINE_POINTS * FormatPanel.MM_PER_POINT) }
                    }
                }, isEnabled = forText),
            Command.Stepper(Resources.string.editors_format_line_spacing, Resources.drawable.ic_editor_line_spacing, { formatPanel.lineSpacingText(format) }, forText,
                down = { formatPanel.stepLineSpacing(up = false) }, up = { formatPanel.stepLineSpacing(up = true) }),
            Command.Stepper(Resources.string.editors_format_space_before, null, { formatPanel.pointsText(format.spacingBefore) }, forText,
                down = { formatPanel.stepSpacing(before = true, up = false) }, up = { formatPanel.stepSpacing(before = true, up = true) }),
            Command.Stepper(Resources.string.editors_format_space_after, null, { formatPanel.pointsText(format.spacingAfter) }, forText,
                down = { formatPanel.stepSpacing(before = false, up = false) }, up = { formatPanel.stepSpacing(before = false, up = true) })
        )),
        if (features.advanced) CommandGroup(listOf<Command>(
            Command.Stepper(Resources.string.editors_format_first_line, null, { formatPanel.centimeters(format.firstLine) }, forText,
                down = { formatPanel.stepFirstLine(up = false) }, up = { formatPanel.stepFirstLine(up = true) })
        ) + FormatPanel.PARAGRAPH_OPTIONS.map { (option, label) ->
            Command.Toggle(label, null, { option in format.paragraphOptions }, forText) {
                edit { setParagraphOption(option, option !in format.paragraphOptions) }
            }
        }) else null
    ).filterNotNull()

    // The commands Home and the pages share

    private fun fontCommand() =
        Command.Value(Resources.string.editors_format_font, value = { format.fontName.ifEmpty { "—" } }) { formatPanel.chooseFont(toCommands) }

    private fun textStyles() =
        Command.Segments(Resources.string.editors_format_text, listOf(
            Command.Toggle(Resources.string.editors_bold, Resources.drawable.ic_editor_bold, { format.bold }) { edit { setBold(!format.bold) } },
            Command.Toggle(Resources.string.editors_italic, Resources.drawable.ic_editor_italic, { format.italic }) { edit { setItalic(!format.italic) } },
            Command.Toggle(Resources.string.editors_underline, Resources.drawable.ic_editor_underline, { format.underline }) {
                edit { setUnderline(!format.underline) }
            },
            Command.Toggle(Resources.string.editors_format_strikeout, Resources.drawable.ic_editor_strikeout, { format.strikeout }) {
                edit { setStrikeout(!format.strikeout) }
            }
        ))

    private fun highlightCommand() =
        Command.Split(Resources.string.editors_format_highlight, Resources.drawable.ic_editor_highlight, { "#$highlight" }, isEnabled = forText,
            apply = { edit { setHighlight(if (format.highlight.equals(highlight, ignoreCase = true)) "" else highlight) } },
            more = { formatPanel.chooseHighlight(toCommands) })

    private fun colorCommand() =
        Command.Split(Resources.string.editors_format_color, Resources.drawable.ic_editor_text_color, { format.color.takeIf { it.isNotEmpty() }?.let { "#$it" } },
            apply = { chooseColor() }, more = { chooseColor() })

    private fun clearCommand() = Command.Action(Resources.string.editors_format_clear, Resources.drawable.ic_editor_clear_format) { edit { clearFormatting() } }

    private fun listCommands() = arrayOf(
        Command.Page(Resources.string.editors_format_bullets, Resources.drawable.ic_editor_bullets, isEnabled = forText) {
            hooks.chooseList(ListKind.BULLETS, toCommands)
        },
        Command.Page(Resources.string.editors_format_numbers, Resources.drawable.ic_editor_numbering, isEnabled = forText) {
            hooks.chooseList(ListKind.NUMBERS, toCommands)
        }
    )

    private fun indentCommand() = Command.Segments(Resources.string.editors_format_indent_more, listOf(
        Command.Toggle(Resources.string.editors_format_indent_less, Resources.drawable.ic_editor_indent_less, { false }) { edit { indent(false) } },
        Command.Toggle(Resources.string.editors_format_indent_more, Resources.drawable.ic_editor_indent_more, { false }) { edit { indent(true) } }
    ), isEnabled = forText)

    private fun alignmentCommand() = Command.Segments(Resources.string.editors_format_paragraph, FormatPanel.ALIGNMENTS.map { (alignment, icon, name) ->
        Command.Toggle(name, icon, { format.alignment == alignment }) { edit { setAlignment(alignment) } }
    }, isEnabled = forText)

    private fun styleCommand() =
        Command.Value(Resources.string.editors_format_style, value = { EditorTranslations.styleName(context, format.style.ifEmpty { "—" }) }, isEnabled = forText) {
            hooks.chooseStyle(format.style, toCommands)
        }

    private fun table(): List<CommandGroup> {
        fun action(label: Int, icon: Int?, name: String) = Command.Action(label, icon) { edit { tableAction(name) } }
        return listOf(
            CommandGroup(listOf(
                action(Resources.string.editors_doc_row_above, Resources.drawable.ic_editor_table_row_above, "rowAbove"),
                action(Resources.string.editors_doc_row_below, Resources.drawable.ic_editor_table_row_below, "rowBelow"),
                action(Resources.string.editors_doc_column_left, Resources.drawable.ic_editor_table_column_left, "columnLeft"),
                action(Resources.string.editors_doc_column_right, Resources.drawable.ic_editor_table_column_right, "columnRight")
            )),
            CommandGroup(listOf(
                action(Resources.string.editors_doc_delete_row, Resources.drawable.ic_editor_table_delete_row, "deleteRow"),
                action(Resources.string.editors_doc_delete_column, Resources.drawable.ic_editor_table_delete_column, "deleteColumn"),
                action(Resources.string.editors_doc_merge_cells, null, "merge"),
                action(Resources.string.editors_doc_split_cell, null, "split"),
                action(Resources.string.editors_doc_delete_table, null, "deleteTable")
            )),
            CommandGroup(listOf(Command.Page(Resources.string.editors_table_style, Resources.drawable.ic_editor_table) { hooks.chooseTableStyle(toCommands) }))
        )
    }

    /**
     * The bar's commands while the sheet is closed: inserting ([openSheet] at Insert), the text's
     * format, its color (chosen in the sheet) and lists.
     */
    override fun barCommands(openSheet: (tab: String) -> Unit): List<Command> = listOf(
        Command.Action(Resources.string.editors_insert, Resources.drawable.ic_editor_insert) { openSheet("insert") },
        Command.Toggle(Resources.string.editors_bold, Resources.drawable.ic_editor_bold, { format.bold }) { edit { setBold(!format.bold) } },
        Command.Toggle(Resources.string.editors_italic, Resources.drawable.ic_editor_italic, { format.italic }) { edit { setItalic(!format.italic) } },
        Command.Toggle(Resources.string.editors_underline, Resources.drawable.ic_editor_underline, { format.underline }) {
            edit { setUnderline(!format.underline) }
        },
        Command.Split(Resources.string.editors_format_highlight, Resources.drawable.ic_editor_highlight, { "#$highlight" }, isEnabled = forText,
            apply = { edit { setHighlight(if (format.highlight.equals(highlight, ignoreCase = true)) "" else highlight) } },
            more = {}),
        Command.Split(Resources.string.editors_format_color, Resources.drawable.ic_editor_text_color, { format.color.takeIf { it.isNotEmpty() }?.let { "#$it" } },
            apply = { openSheet("home"); chooseColor() }, more = {}),
        Command.Toggle(Resources.string.editors_format_bullets, Resources.drawable.ic_editor_bullets, { format.list == 0 }, isEnabled = forText) {
            edit { setList(0, if (format.list == 0) -1 else 1) }
        },
        Command.Toggle(Resources.string.editors_format_numbers, Resources.drawable.ic_editor_numbering, { format.list == 1 }, isEnabled = forText) {
            edit { setList(1, if (format.list == 1) -1 else 1) }
        }
    )

    /** [actions] as one group of commands (none without them), for a tab. */
    fun groups(actions: List<MenuAction>): List<CommandGroup> = actions.map { it.toCommand(context, panels) }
        .takeIf { it.isNotEmpty() }?.let { listOf(CommandGroup(it)) } ?: emptyList()

    /** Back from a page opened from a command: to the commands. */
    val back: () -> Unit get() = toCommands

    private fun chooseShading() = colorPicker.choose(
        context.getString(Resources.string.editors_format_paragraph_background), format.shading, Resources.string.editors_color_none, toCommands
    ) { color -> edit { setParagraphShading(color) } }

    private fun chooseColor() = colorPicker.choose(
        context.getString(Resources.string.editors_format_color), format.color, Resources.string.editors_color_automatic, toCommands
    ) { color -> edit { setTextColor(color) } }

    private fun chooseSize() {
        val current = SIZES.indexOfFirst { it == format.fontSize }
        panels.choose(context.getString(Resources.string.editors_format_size), SIZES.map(::size), current) { index ->
            edit { setFontSize(SIZES[index]) }
        }
    }

    private fun size(points: Double) = if (points <= 0) "—" else if (points % 1.0 == 0.0) points.toInt().toString() else points.toString()

    companion object {
        const val DRAWING_TAB = "drawing"
        const val TABLE_TAB = "table"
        private const val HIGHLIGHT = "FFFF00"
        // Word's sizes
        private val SIZES = listOf(8.0, 9.0, 10.0, 11.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0, 26.0, 28.0, 36.0, 48.0, 72.0)
    }
}
