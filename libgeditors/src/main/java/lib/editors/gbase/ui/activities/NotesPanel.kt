package lib.editors.gbase.ui.activities

import android.content.Context
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.NoteConversion
import lib.editors.base.engine.NoteFormat
import lib.editors.base.engine.NotePosition
import lib.editors.base.engine.NoteRestart
import lib.editors.base.engine.NoteSettings
import lib.editors.resources.R as Resources

/**
 * Text documents: the footnotes' or endnotes' settings, as web-apps' note settings have them
 * (where they go, their numbers' format, first number and restarting), in a panel (see
 * [PanelHost]); changes apply to the whole document at once. Converting and deleting all notes.
 */
internal class NotesPanel(
    context: Context,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val panels: PanelHost,
    private val edit: (EditorDocument.() -> EditorState) -> Unit
) : PanelBuilder<NoteSettings>(context) {

    private var endnote = false
    private var current = NoteSettings(NotePosition.PAGE_BOTTOM, NoteFormat.DECIMAL, 1, NoteRestart.CONTINUOUS)

    /** Shows the footnotes' or ([endnote]) endnotes' settings. */
    fun show(endnote: Boolean) {
        val document = document() ?: return
        scope.launch {
            val settings = withContext(EditorEngine.dispatcher) { runCatching { document.noteSettings(endnote) }.getOrNull() } ?: return@launch
            this@NotesPanel.endnote = endnote
            current = settings
            build()
        }
    }

    private fun build() {
        updaters.clear()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), dp(24))

            caption(Resources.string.editors_notes_location)
            scrollRow {
                for (position in if (endnote) NotePosition.ENDNOTES else NotePosition.FOOTNOTES) {
                    choice(context.getString(POSITIONS.getValue(position)), { it.position == position }) { apply(current.copy(position = position)) }
                }
            }

            caption(Resources.string.editors_notes_format)
            action(Resources.string.editors_notes_numbering_format, { FORMATS.getValue(it.format) }) { chooseFormat() }
            caption(Resources.string.editors_notes_start_at)
            // A first number above 1 counts on through the document, as in web-apps
            stepper(Resources.string.editors_notes_start_at, { it.format.format(it.start) }, {
                apply(current.copy(start = (current.start - 1).coerceAtLeast(1)))
            }, {
                apply(current.copy(start = current.start + 1, restart = NoteRestart.CONTINUOUS))
            })
            action(Resources.string.editors_notes_numbering, { context.getString(RESTARTS.getValue(it.restart)) }) { chooseRestart() }

            action(Resources.string.editors_notes_convert) { chooseConversion() }
            scrollRow {
                button(context.getString(if (endnote) Resources.string.editors_notes_remove_endnotes else Resources.string.editors_notes_remove_footnotes)) {
                    edit { removeNotes(footnotes = !endnote, endnotes = endnote) }
                }
            }
        }
        val title = if (endnote) Resources.string.editors_notes_endnote_settings else Resources.string.editors_notes_footnote_settings
        panels.show(context.getString(title), content, modal = false) {}
        updaters.forEach { it(current) }
    }

    private fun chooseFormat() {
        val formats = NoteFormat.entries
        panels.choose(
            context.getString(Resources.string.editors_notes_numbering_format), formats.map { FORMATS.getValue(it) },
            formats.indexOf(current.format), onBack = ::build
        ) { index -> apply(current.copy(format = formats[index])) }
    }

    // Footnotes start again on each page too
    private fun chooseRestart() {
        val restarts = NoteRestart.entries.filter { !endnote || it != NoteRestart.EACH_PAGE }
        panels.choose(
            context.getString(Resources.string.editors_notes_numbering), restarts.map { context.getString(RESTARTS.getValue(it)) },
            restarts.indexOf(current.restart), onBack = ::build
        ) { index ->
            val restart = restarts[index]
            apply(current.copy(restart = restart, start = if (restart == NoteRestart.CONTINUOUS) current.start else 1))
        }
    }

    private fun chooseConversion() {
        val conversions = NoteConversion.entries
        panels.choose(
            context.getString(Resources.string.editors_notes_convert), conversions.map { context.getString(CONVERSIONS.getValue(it)) },
            -1, onBack = ::build
        ) { index -> edit { convertNotes(conversions[index]) } }
    }

    private fun apply(settings: NoteSettings) {
        current = settings
        updaters.forEach { it(current) }
        val isEndnote = endnote
        edit { setNoteSettings(isEndnote, settings) }
    }

    private companion object {
        val POSITIONS = mapOf(
            NotePosition.PAGE_BOTTOM to Resources.string.editors_notes_page_bottom,
            NotePosition.BENEATH_TEXT to Resources.string.editors_notes_below_text,
            NotePosition.SECTION_END to Resources.string.editors_notes_section_end,
            NotePosition.DOCUMENT_END to Resources.string.editors_notes_document_end
        )
        val FORMATS = mapOf(
            NoteFormat.DECIMAL to "1, 2, 3, …",
            NoteFormat.LOWER_LETTER to "a, b, c, …",
            NoteFormat.UPPER_LETTER to "A, B, C, …",
            NoteFormat.LOWER_ROMAN to "i, ii, iii, …",
            NoteFormat.UPPER_ROMAN to "I, II, III, …"
        )
        val RESTARTS = mapOf(
            NoteRestart.CONTINUOUS to Resources.string.editors_notes_continuous,
            NoteRestart.EACH_SECTION to Resources.string.editors_notes_restart_section,
            NoteRestart.EACH_PAGE to Resources.string.editors_notes_restart_page
        )
        val CONVERSIONS = mapOf(
            NoteConversion.TO_ENDNOTES to Resources.string.editors_notes_to_endnotes,
            NoteConversion.TO_FOOTNOTES to Resources.string.editors_notes_to_footnotes,
            NoteConversion.SWAP to Resources.string.editors_notes_swap
        )
    }
}
