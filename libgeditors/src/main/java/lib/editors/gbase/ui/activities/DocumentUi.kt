package lib.editors.gbase.ui.activities

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.DocumentStructure
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.PageSetup
import lib.editors.gbase.R
import lib.editors.gbase.ui.commands.Command
import lib.editors.gbase.ui.commands.CommandGroup
import lib.editors.gbase.ui.commands.CommandPanel
import lib.editors.gbase.ui.commands.composePanel
import lib.editors.gbase.ui.views.DocumentView
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.abs
import lib.editors.resources.R as Resources

/**
 * Documents: breaks, headers and footers, page numbers, notes, the table of contents
 * (in the "Insert" menu), page setup, word count and track changes (in the file menu) and
 * navigation. A bar above the toolbar shows where the cursor is (header or footer, note) and
 * the tracked change at it.
 */
internal class DocumentUi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val documentView: DocumentView,
    private val document: () -> EditorDocument?,
    private val panels: PanelHost,
    private val pageSettings: PageSettingsUi,
    private val edit: (EditorDocument.() -> EditorState) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val notesPanel by lazy { NotesPanel(context, scope, document, panels, edit) }
    private var structure = DocumentStructure()

    /** Changes are always tracked (reviewing): recording can't be turned off. */
    var trackChangesForced = false

    /** Mobile view: no headers, footers or pages to set up (they are the document's pages'). */
    var mobileView = false

    /** The bar for headers and footers, notes and tracked changes; above the toolbar. */
    val bar: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(12), 0, dp(4), 0)
        minimumHeight = dp(48)
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, background, true)
        setBackgroundColor(background.data)
        elevation = 2 * density
        isVisible = false
        Accessibility.pane(this, context.getString(Resources.string.editors_doc_document))
    }

    fun update(state: EditorState) {
        val newStructure = state.structure ?: return
        if (newStructure == structure) return
        structure = newStructure
        bar.removeAllViews()
        val change = newStructure.change
        when {
            newStructure.inHeaderFooter -> {
                barTitle(Resources.string.editors_doc_header_footer)
                barButton(context.getString(Resources.string.editors_doc_different_first), selected = newStructure.differentFirst) {
                    edit { setHeaderFooterOption("differentFirst", !structure.differentFirst) }
                }
                barButton(context.getString(Resources.string.editors_doc_close)) { edit { endHeaderFooter() } }
            }
            newStructure.inNote -> {
                barTitle(Resources.string.editors_doc_note)
                barButton(context.getString(Resources.string.editors_doc_close)) { edit { endNoteEditing() } }
            }
            change != null -> {
                barText(changeDescription(change.type, change.user, change.date))
                barButton(context.getString(Resources.string.editors_doc_accept)) { edit { reviewAction("accept") } }
                barButton(context.getString(Resources.string.editors_doc_reject)) { edit { reviewAction("reject") } }
                barButton("›", description = context.getString(Resources.string.editors_doc_next_change)) { edit { reviewAction("next") } }
            }
        }
        bar.isVisible = bar.childCount > 0
    }

    // ---------------------------------------------------------------------------------
    // Menus

    /** Breaks, headers and footers, page numbers, notes and the table of contents: inserted. */
    fun insertActions(): List<MenuAction> = buildList {
        add(MenuAction(Resources.string.editors_doc_break, Resources.drawable.ic_editor_page_break, children = listOf(
            MenuAction(Resources.string.editors_doc_break_page, Resources.drawable.ic_editor_page_break) { edit { insertBreak("page") } },
            MenuAction(Resources.string.editors_doc_break_line, Resources.drawable.ic_editor_line_break) { edit { insertBreak("line") } },
            MenuAction(Resources.string.editors_doc_break_column) { edit { insertBreak("column") } },
            MenuAction(Resources.string.editors_doc_break_section_page, Resources.drawable.ic_editor_section_break) {
                edit { insertBreak("sectionNextPage") }
            },
            MenuAction(Resources.string.editors_doc_break_section_continuous, Resources.drawable.ic_editor_section_break) {
                edit { insertBreak("sectionContinuous") }
            }
        )))
        if (!mobileView) add(MenuAction(Resources.string.editors_doc_header_footer, Resources.drawable.ic_editor_page_number, children = listOf(
            MenuAction(Resources.string.editors_doc_edit_header) { type { editHeaderFooter(documentView.visiblePage(), footer = false) } },
            MenuAction(Resources.string.editors_doc_edit_footer) { type { editHeaderFooter(documentView.visiblePage(), footer = true) } },
            MenuAction(Resources.string.editors_doc_page_number_footer) { edit { insertPageNumber(2, 2) } },
            MenuAction(Resources.string.editors_doc_page_number_header) { edit { insertPageNumber(1, 0) } },
            MenuAction(Resources.string.editors_doc_page_number_here) { edit { insertPageNumber(-1, 0) } }
        )))
        // Each with its settings
        for (endnote in listOf(false, true)) {
            add(MenuAction(
                if (endnote) Resources.string.editors_doc_endnote else Resources.string.editors_doc_footnote, Resources.drawable.ic_editor_footnote,
                children = listOf(
                    MenuAction(if (endnote) Resources.string.editors_notes_insert_endnote else Resources.string.editors_notes_insert_footnote) {
                        type { addNote(endnote) }
                    },
                    MenuAction(if (endnote) Resources.string.editors_notes_endnote_settings else Resources.string.editors_notes_footnote_settings) {
                        notesPanel.show(endnote)
                    }
                )
            ))
        }
        add(MenuAction(Resources.string.editors_doc_toc, Resources.drawable.ic_editor_table_contents, children = listOf(
            MenuAction(Resources.string.editors_doc_toc_insert) { edit { addTableOfContents() } },
            MenuAction(Resources.string.editors_doc_toc_update) { edit { updateTableOfContents() } }
        )))
    }

    /** The page's setup; null in mobile view, which has no pages. */
    fun pageSetupAction(): MenuAction? =
        if (mobileView) null else MenuAction(Resources.string.editors_doc_page_setup, Resources.drawable.ic_editor_page_setup) {
            openLayout?.invoke() ?: showPageSetup()
        }

    /** Shows the page's setup as the command sheet's Layout tab, where there is one; null: in a panel. */
    var openLayout: (() -> Unit)? = null

    // The page's setup, for the Layout tab's commands (loaded as they are built; null before)
    private var pageSetup by mutableStateOf<PageSetup?>(null)

    /** Orientation, size, margins and columns as commands, for the Layout tab: each applied as it is chosen. */
    fun pageSetupCommands(back: () -> Unit = { panels.dismiss() }): List<CommandGroup> {
        if (mobileView) return emptyList()
        read({ pageSetup() }) { pageSetup = it }
        fun change(command: EditorDocument.() -> EditorState) {
            edit(command)
            read({ pageSetup() }) { pageSetup = it }
        }
        val sizes = PageSettingsUi.PAPER_SIZES
        return listOf(
            CommandGroup(listOf(
                Command.Segments(Resources.string.editors_doc_orientation, listOf(
                    Command.Toggle(Resources.string.editors_doc_portrait, null, { pageSetup?.landscape == false }) { change { setPageSetup(landscape = false) } },
                    Command.Toggle(Resources.string.editors_doc_landscape, null, { pageSetup?.landscape == true }) { change { setPageSetup(landscape = true) } }
                )),
                Command.Choice(Resources.string.editors_doc_size, Resources.drawable.ic_editor_page_setup, sizes.map { it.first }, {
                    val setup = pageSetup
                    if (setup == null) -1 else sizes.indexOfFirst { (_, width, height) ->
                        abs(minOf(setup.width, setup.height) - width) < 1 && abs(maxOf(setup.width, setup.height) - height) < 1
                    }
                }) { index -> change { setPageSetup(width = sizes[index].second, height = sizes[index].third) } },
                Command.Choice(Resources.string.editors_doc_margins, null, MARGINS.map { context.getString(it.first) }, {
                    val setup = pageSetup
                    if (setup == null) -1 else MARGINS.indexOfFirst { (_, margin) -> abs(setup.left - margin) < 0.5 && abs(setup.top - margin) < 0.5 }
                }) { index -> MARGINS[index].second.let { margin -> change { setPageSetup(margins = doubleArrayOf(margin, margin, margin, margin)) } } },
                Command.Choice(Resources.string.editors_doc_columns, Resources.drawable.ic_editor_columns_two, COLUMNS.map { it.first.toString() }, {
                    COLUMNS.indexOfFirst { it.first == pageSetup?.columns }
                }) { index -> change { setPageSetup(columns = COLUMNS[index].first) } }
            )),
            CommandGroup(listOf(
                Command.Page(Resources.string.editors_color_scheme, null) { pageSettings.chooseColorScheme(back) }
            ))
        )
    }

    fun statisticsAction() = MenuAction(Resources.string.editors_doc_statistics, Resources.drawable.ic_editor_info) { showStatistics() }

    /** Tracking changes, going through them, accepting or rejecting them, and how they show. */
    fun reviewActions(): List<MenuAction> = buildList {
        if (!trackChangesForced) {
            add(MenuAction(Resources.string.editors_doc_track_changes, isChecked = { structure.trackChanges }) {
                edit { setTrackChanges(!structure.trackChanges) }
            })
        }
        add(MenuAction(Resources.string.editors_doc_next_change) { edit { reviewAction("next") } })
        add(MenuAction(Resources.string.editors_doc_previous_change) { edit { reviewAction("previous") } })
        add(MenuAction(Resources.string.editors_doc_accept_all) { edit { reviewAction("acceptAll") } })
        add(MenuAction(Resources.string.editors_doc_reject_all) { edit { reviewAction("rejectAll") } })
        add(MenuAction(Resources.string.editors_doc_show_markup) { edit { setReviewMode(0) } })
        add(MenuAction(Resources.string.editors_doc_show_final) { edit { setReviewMode(1) } })
        add(MenuAction(Resources.string.editors_doc_show_original) { edit { setReviewMode(2) } })
    }

    /** Page setup, word count and reviewing: in the file menu. */
    fun documentActions(): List<MenuAction> = listOfNotNull(
        pageSetupAction(),
        statisticsAction(),
        MenuAction(Resources.string.editors_doc_review, Resources.drawable.ic_editor_review, children = reviewActions())
    )

    // A command that moves the cursor somewhere to type: a header, footer or note
    private fun type(command: EditorDocument.() -> EditorState) {
        edit(command)
        documentView.showKeyboard()
    }

    // ---------------------------------------------------------------------------------
    // Dialogs

    // In a panel, where there is no Layout tab (its color schemes come back to it)
    private fun showPageSetup() {
        val content = composePanel(context) { CommandPanel { pageSetupCommands { showPageSetup() } } }
        panels.show(context.getString(Resources.string.editors_doc_page_setup), content) {}
    }

    fun showNavigation() = read({ outline() }) { items ->
        val labels = listOf(context.getString(Resources.string.editors_doc_go_to_page)) + items.map { "\u2003".repeat(it.level) + it.text }
        panels.choose(context.getString(Resources.string.editors_doc_navigation), labels, selected = -1) { position ->
            if (position == 0) askPage() else edit { gotoOutline(items[position - 1].index) }
        }
    }

    private fun askPage() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((documentView.visiblePage() + 1).toString())
            selectAll()
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(Resources.string.editors_doc_go_to_page)
            .setView(LinearLayout(context).apply {
                setPaddingRelative(dp(24), dp(8), dp(24), 0)
                addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toIntOrNull()?.let { documentView.goToPage(it - 1) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // The counts, a row each
    private fun showStatistics() = read({ statistics() }) { statistics ->
        val counts = listOf(
            Resources.string.editors_doc_statistics_pages to statistics.pages,
            Resources.string.editors_doc_statistics_words to statistics.words,
            Resources.string.editors_doc_statistics_paragraphs to statistics.paragraphs,
            Resources.string.editors_doc_statistics_characters to statistics.symbols,
            Resources.string.editors_doc_statistics_characters_spaces to statistics.symbolsWithSpaces
        ).filter { (_, count) -> count >= 0 }
        val content = composePanel(context) {
            Column(Modifier.padding(bottom = 16.dp)) {
                for ((label, count) in counts) {
                    ListItem(
                        headlineContent = { Text(stringResource(label)) },
                        trailingContent = { Text(NumberFormat.getIntegerInstance().format(count), style = MaterialTheme.typography.titleMedium) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
        panels.show(context.getString(Resources.string.editors_doc_statistics), content) {}
    }

    // Reads from the document on the engine's thread, then shows the result
    private fun <T> read(query: EditorDocument.() -> T, onResult: (T) -> Unit) {
        val document = document() ?: return
        scope.launch {
            val result = withContext(EditorEngine.dispatcher) { runCatching { document.query() }.getOrNull() } ?: return@launch
            onResult(result)
        }
    }

    private fun changeDescription(type: Int, user: String, date: String): String {
        val what = context.getString(
            when (type) {
                1, 3 -> Resources.string.editors_doc_change_inserted
                2, 4 -> Resources.string.editors_doc_change_deleted
                else -> Resources.string.editors_doc_change_formatted
            }
        )
        val time = date.toLongOrNull()?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
        return listOfNotNull(what, user.ifEmpty { null }, time).joinToString(" · ")
    }

    // ---------------------------------------------------------------------------------
    // Views

    private fun dp(value: Int) = (value * density).toInt()

    private fun barTitle(text: Int) = barText(context.getString(text))

    private fun barText(text: String) {
        bar.addView(TextView(context).apply {
            this.text = text
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun barButton(text: String, selected: Boolean = false, description: String? = null, onClick: () -> Unit) {
        bar.addView(TextView(context).apply {
            this.text = text
            contentDescription = description
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setPaddingRelative(dp(10), 0, dp(10), 0)
            setTextColor(context.getColor(Resources.color.editors_selection_handle))
            setBackgroundResource(R.drawable.editor_tool_background)
            isSelected = selected
            setOnClickListener { onClick() }
        })
    }

    private companion object {
        // All four margins, in mm
        val MARGINS = listOf(
            Resources.string.editors_doc_margins_narrow to 12.7,
            Resources.string.editors_doc_margins_normal to 25.4,
            Resources.string.editors_doc_margins_wide to 38.1
        )
        val COLUMNS = listOf(
            1 to Resources.drawable.ic_editor_columns_one,
            2 to Resources.drawable.ic_editor_columns_two,
            3 to Resources.drawable.ic_editor_columns_three
        )
    }
}
