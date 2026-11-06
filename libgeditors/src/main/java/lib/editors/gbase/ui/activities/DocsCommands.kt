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
import lib.editors.resources.R as Resources

/** What the commands show, kept as Compose state: the editor's state sets it, the commands read it. */
internal class CommandState {
    var format by mutableStateOf(TextFormat())
    /** A picture, shape or chart is selected (not text in it): paragraph commands don't apply. */
    var drawingSelected by mutableStateOf(false)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
}

/**
 * The commands of text documents, as Office arranges them: Home (the text, its paragraphs and
 * style), Insert, Layout, Review and View. Pages open where the existing panels are.
 */
internal class DocsCommands(
    private val context: Context,
    private val state: CommandState,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val panels: PanelHost,
    private val formatPanel: FormatPanel,
    private val colorPicker: ColorPicker,
    private val hooks: Hooks
) {

    /** The editor's other parts, opened from the commands. */
    class Hooks(
        val chooseList: (kind: ListKind, onBack: () -> Unit) -> Unit,
        val chooseStyle: (current: String, onBack: () -> Unit) -> Unit,
        val insertActions: () -> List<MenuAction>,
        val layoutActions: () -> List<MenuAction>,
        val reviewActions: () -> List<MenuAction>,
        val viewActions: () -> List<MenuAction>
    )

    // Highlighting applies the color chosen last, as Office's does
    private var highlight = HIGHLIGHT

    // Back from a page opened from a command: to the commands
    private val toCommands: () -> Unit = { panels.dismiss() }

    private val format get() = state.format
    private val forText: () -> Boolean = { !state.drawingSelected }

    fun tabs(): List<CommandTab> = listOf(
        CommandTab("home", Resources.string.editors_tab_home, home()),
        CommandTab("insert", Resources.string.editors_insert, groups(hooks.insertActions())),
        CommandTab("layout", Resources.string.editors_tab_layout, groups(hooks.layoutActions())),
        CommandTab("review", Resources.string.editors_tab_review, groups(hooks.reviewActions())),
        CommandTab("view", Resources.string.editors_tab_view, groups(hooks.viewActions()))
    ).filter { it.groups.isNotEmpty() }

    private fun home() = listOf(
        CommandGroup(listOf(
            Command.Value(Resources.string.editors_format_font, value = { format.fontName.ifEmpty { "—" } }) { formatPanel.chooseFont(toCommands) },
            Command.Value(Resources.string.editors_format_size, value = { size(format.fontSize) }) { chooseSize() },
            Command.Segments(Resources.string.editors_format_text, listOf(
                Command.Toggle(Resources.string.editors_bold, Resources.drawable.ic_editor_bold, { format.bold }) { edit { setBold(!format.bold) } },
                Command.Toggle(Resources.string.editors_italic, Resources.drawable.ic_editor_italic, { format.italic }) { edit { setItalic(!format.italic) } },
                Command.Toggle(Resources.string.editors_underline, Resources.drawable.ic_editor_underline, { format.underline }) {
                    edit { setUnderline(!format.underline) }
                },
                Command.Toggle(Resources.string.editors_format_strikeout, Resources.drawable.ic_editor_strikeout, { format.strikeout }) {
                    edit { setStrikeout(!format.strikeout) }
                }
            )),
            Command.Split(Resources.string.editors_format_highlight, Resources.drawable.ic_editor_highlight, { "#$highlight" }, isEnabled = forText,
                apply = { edit { setHighlight(if (format.highlight.equals(highlight, ignoreCase = true)) "" else highlight) } },
                more = { formatPanel.chooseHighlight(toCommands) }),
            Command.Split(Resources.string.editors_format_color, Resources.drawable.ic_editor_text_color, { format.color.takeIf { it.isNotEmpty() }?.let { "#$it" } },
                apply = { chooseColor() }, more = { chooseColor() }),
            Command.Action(Resources.string.editors_format_clear, Resources.drawable.ic_editor_clear_format) { edit { clearFormatting() } },
            Command.Page(Resources.string.editors_format_more, Resources.drawable.ic_editor_superscript) { formatPanel.show() }
        )),
        CommandGroup(listOf(
            Command.Page(Resources.string.editors_format_bullets, Resources.drawable.ic_editor_bullets, isEnabled = forText) {
                hooks.chooseList(ListKind.BULLETS, toCommands)
            },
            Command.Page(Resources.string.editors_format_numbers, Resources.drawable.ic_editor_numbering, isEnabled = forText) {
                hooks.chooseList(ListKind.NUMBERS, toCommands)
            },
            Command.Segments(Resources.string.editors_format_indent_more, listOf(
                Command.Toggle(Resources.string.editors_format_indent_less, Resources.drawable.ic_editor_indent_less, { false }) { edit { indent(false) } },
                Command.Toggle(Resources.string.editors_format_indent_more, Resources.drawable.ic_editor_indent_more, { false }) { edit { indent(true) } }
            ), isEnabled = forText),
            Command.Segments(Resources.string.editors_format_paragraph, FormatPanel.ALIGNMENTS.map { (alignment, icon, name) ->
                Command.Toggle(name, icon, { format.alignment == alignment }) { edit { setAlignment(alignment) } }
            }, isEnabled = forText),
            Command.Page(Resources.string.editors_format_paragraph, null, isEnabled = forText) { formatPanel.show() }
        )),
        CommandGroup(listOf(
            Command.Value(Resources.string.editors_format_style, value = { EditorTranslations.styleName(context, format.style.ifEmpty { "—" }) }, isEnabled = forText) {
                hooks.chooseStyle(format.style, toCommands)
            }
        ))
    )

    // Each action a group of its own when it opens choices, the others together, in their order
    private fun groups(actions: List<MenuAction>): List<CommandGroup> = actions.map { it.toCommand(context, panels) }
        .takeIf { it.isNotEmpty() }?.let { listOf(CommandGroup(it)) } ?: emptyList()

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
        private const val HIGHLIGHT = "FFFF00"
        // Word's sizes
        private val SIZES = listOf(8.0, 9.0, 10.0, 11.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0, 26.0, 28.0, 36.0, 48.0, 72.0)
    }
}
