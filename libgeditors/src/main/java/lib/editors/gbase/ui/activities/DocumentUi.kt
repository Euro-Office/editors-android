package lib.editors.gbase.ui.activities

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.DocumentStructure
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.gbase.R
import lib.editors.gbase.ui.views.DocumentView
import java.text.DateFormat
import java.util.Date
import lib.editors.resources.R as Resources

/**
 * Documents: tables, breaks, headers and footers, page numbers, notes, the table of contents,
 * page setup, navigation, word count and track changes. A bar above the toolbar shows where
 * the cursor is (header or footer, note) and the tracked change at it.
 */
internal class DocumentUi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val documentView: DocumentView,
    private val document: () -> EditorDocument?,
    private val edit: (EditorDocument.() -> EditorState) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private var structure = DocumentStructure()

    /** Changes are always tracked (reviewing): recording can't be turned off. */
    var trackChangesForced = false

    /** The bar for headers and footers, notes and tracked changes; above the toolbar. */
    val bar: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(4), 0)
        minimumHeight = dp(48)
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, background, true)
        setBackgroundColor(background.data)
        elevation = 2 * density
        isVisible = false
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
                barButton("›") { edit { reviewAction("next") } }
            }
        }
        bar.isVisible = bar.childCount > 0
    }

    val isInTable get() = structure.inTable

    // ---------------------------------------------------------------------------------
    // Menus

    fun showDocumentMenu(anchor: View) {
        val menu = PopupMenu(context, anchor)
        val actions = HashMap<Int, () -> Unit>()
        var id = 1
        fun item(parent: Menu, label: Int, action: () -> Unit) {
            parent.add(Menu.NONE, id, id, label)
            actions[id++] = action
        }
        fun checkItem(parent: Menu, label: Int, checked: Boolean, action: () -> Unit) {
            parent.add(Menu.NONE, id, id, label).isCheckable = true
            parent.findItem(id).isChecked = checked
            actions[id++] = action
        }
        item(menu.menu, Resources.string.editors_doc_table) { askTableSize() }
        menu.menu.addSubMenu(Resources.string.editors_doc_break).let { sub ->
            item(sub, Resources.string.editors_doc_break_page) { edit { insertBreak("page") } }
            item(sub, Resources.string.editors_doc_break_line) { edit { insertBreak("line") } }
            item(sub, Resources.string.editors_doc_break_column) { edit { insertBreak("column") } }
            item(sub, Resources.string.editors_doc_break_section_page) { edit { insertBreak("sectionNextPage") } }
            item(sub, Resources.string.editors_doc_break_section_continuous) { edit { insertBreak("sectionContinuous") } }
        }
        menu.menu.addSubMenu(Resources.string.editors_doc_header_footer).let { sub ->
            item(sub, Resources.string.editors_doc_edit_header) { type { editHeaderFooter(documentView.visiblePage(), footer = false) } }
            item(sub, Resources.string.editors_doc_edit_footer) { type { editHeaderFooter(documentView.visiblePage(), footer = true) } }
            item(sub, Resources.string.editors_doc_page_number_footer) { edit { insertPageNumber(2, 2) } }
            item(sub, Resources.string.editors_doc_page_number_header) { edit { insertPageNumber(1, 0) } }
            item(sub, Resources.string.editors_doc_page_number_here) { edit { insertPageNumber(-1, 0) } }
        }
        item(menu.menu, Resources.string.editors_doc_footnote) { type { addNote(endnote = false) } }
        item(menu.menu, Resources.string.editors_doc_endnote) { type { addNote(endnote = true) } }
        menu.menu.addSubMenu(Resources.string.editors_doc_toc).let { sub ->
            item(sub, Resources.string.editors_doc_toc_insert) { edit { addTableOfContents() } }
            item(sub, Resources.string.editors_doc_toc_update) { edit { updateTableOfContents() } }
        }
        item(menu.menu, Resources.string.editors_doc_page_setup) { showPageSetup() }
        item(menu.menu, Resources.string.editors_doc_navigation) { showNavigation() }
        item(menu.menu, Resources.string.editors_doc_statistics) { showStatistics() }
        menu.menu.addSubMenu(Resources.string.editors_doc_review).let { sub ->
            if (!trackChangesForced) {
                checkItem(sub, Resources.string.editors_doc_track_changes, structure.trackChanges) { edit { setTrackChanges(!structure.trackChanges) } }
            }
            item(sub, Resources.string.editors_doc_next_change) { edit { reviewAction("next") } }
            item(sub, Resources.string.editors_doc_previous_change) { edit { reviewAction("previous") } }
            item(sub, Resources.string.editors_doc_accept_all) { edit { reviewAction("acceptAll") } }
            item(sub, Resources.string.editors_doc_reject_all) { edit { reviewAction("rejectAll") } }
            item(sub, Resources.string.editors_doc_show_markup) { edit { setReviewMode(0) } }
            item(sub, Resources.string.editors_doc_show_final) { edit { setReviewMode(1) } }
            item(sub, Resources.string.editors_doc_show_original) { edit { setReviewMode(2) } }
        }
        menu.setOnMenuItemClickListener { actions[it.itemId]?.invoke(); actions.containsKey(it.itemId) }
        menu.show()
    }

    fun showTableMenu(anchor: View) {
        val actions = listOf(
            Resources.string.editors_doc_row_above to "rowAbove",
            Resources.string.editors_doc_row_below to "rowBelow",
            Resources.string.editors_doc_column_left to "columnLeft",
            Resources.string.editors_doc_column_right to "columnRight",
            Resources.string.editors_doc_merge_cells to "merge",
            Resources.string.editors_doc_split_cell to "split",
            Resources.string.editors_doc_delete_row to "deleteRow",
            Resources.string.editors_doc_delete_column to "deleteColumn",
            Resources.string.editors_doc_delete_table to "deleteTable"
        )
        PopupMenu(context, anchor).apply {
            actions.forEachIndexed { index, (label, _) -> menu.add(Menu.NONE, index, index, label) }
            setOnMenuItemClickListener { item ->
                val action = actions[item.itemId].second
                edit { tableAction(action) }
                true
            }
        }.show()
    }

    // A command that moves the cursor somewhere to type: a header, footer or note
    private fun type(command: EditorDocument.() -> EditorState) {
        edit(command)
        documentView.showKeyboard()
    }

    // ---------------------------------------------------------------------------------
    // Dialogs

    private fun askTableSize() {
        fun picker(value: Int) = NumberPicker(context).apply {
            minValue = 1
            maxValue = 20
            this.value = value
        }
        val cols = picker(3)
        val rows = picker(3)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(labeled(Resources.string.editors_doc_columns, cols))
            addView(labeled(Resources.string.editors_doc_rows, rows))
        }
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_doc_table)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ -> edit { insertTable(cols.value, rows.value) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPageSetup() = read({ pageSetup() }) { setup ->
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val orientation = choices(
            content, Resources.string.editors_doc_orientation,
            listOf(context.getString(Resources.string.editors_doc_portrait), context.getString(Resources.string.editors_doc_landscape)),
            if (setup.landscape) 1 else 0
        )
        val sizeIndex = PAPER_SIZES.indexOfFirst {
            kotlin.math.abs(it.second - minOf(setup.width, setup.height)) < 1 && kotlin.math.abs(it.third - maxOf(setup.width, setup.height)) < 1
        }
        val size = choices(content, Resources.string.editors_doc_size, PAPER_SIZES.map { it.first }, sizeIndex)
        val marginIndex = MARGINS.indexOfFirst { kotlin.math.abs(it.second - setup.left) < 0.5 && kotlin.math.abs(it.second - setup.top) < 0.5 }
        val margins = choices(content, Resources.string.editors_doc_margins, MARGINS.map { context.getString(it.first) }, marginIndex)
        val columns = choices(content, Resources.string.editors_doc_columns, listOf("1", "2", "3"), setup.columns - 1)
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_doc_page_setup)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val paper = PAPER_SIZES.getOrNull(size())
                val margin = MARGINS.getOrNull(margins())?.second
                edit {
                    setPageSetup(
                        width = paper?.second, height = paper?.third,
                        landscape = orientation() == 1,
                        margins = margin?.let { doubleArrayOf(it, it, it, it) },
                        columns = columns().takeIf { it >= 0 }?.plus(1)
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNavigation() = read({ outline() }) { items ->
        val labels = mutableListOf(context.getString(Resources.string.editors_doc_go_to_page))
        labels += items.map { " ".repeat(it.level) + it.text }
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_doc_navigation)
            .setItems(labels.toTypedArray()) { _, position ->
                if (position == 0) askPage() else edit { gotoOutline(items[position - 1].index) }
            }
            .show()
    }

    private fun askPage() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((documentView.visiblePage() + 1).toString())
            selectAll()
        }
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_doc_go_to_page)
            .setView(LinearLayout(context).apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toIntOrNull()?.let { documentView.goToPage(it - 1) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showStatistics() = read({ statistics() }) { statistics ->
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_doc_statistics)
            .setMessage(
                context.getString(
                    Resources.string.editors_doc_statistics_text, statistics.pages, statistics.words,
                    statistics.paragraphs, statistics.symbols, statistics.symbolsWithSpaces
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
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

    private fun barButton(text: String, selected: Boolean = false, onClick: () -> Unit) {
        bar.addView(TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(44)
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(context.getColor(Resources.color.editors_selection_handle))
            setBackgroundResource(R.drawable.editor_tool_background)
            isSelected = selected
            setOnClickListener { onClick() }
        })
    }

    private fun labeled(label: Int, view: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(8), dp(16), 0)
        addView(TextView(context).apply { setText(label) })
        addView(view)
    }

    // A titled row of choices; returns the chosen index (-1 if none) when called
    private fun choices(parent: LinearLayout, title: Int, labels: List<String>, selected: Int): () -> Int {
        parent.addView(TextView(context).apply {
            setText(title)
            setPadding(0, dp(12), 0, dp(4))
        })
        val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        labels.forEachIndexed { index, label ->
            group.addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = label
                isChecked = index == selected
            })
        }
        parent.addView(group)
        return { (0 until group.childCount).firstOrNull { (group.getChildAt(it) as RadioButton).isChecked } ?: -1 }
    }

    private companion object {
        // Name, width and height (portrait) in mm
        val PAPER_SIZES = listOf(
            Triple("A4", 210.0, 297.0),
            Triple("A5", 148.0, 210.0),
            Triple("Letter", 215.9, 279.4),
            Triple("Legal", 215.9, 355.6)
        )
        // All four margins, in mm
        val MARGINS = listOf(
            Resources.string.editors_doc_margins_narrow to 12.7,
            Resources.string.editors_doc_margins_normal to 25.4,
            Resources.string.editors_doc_margins_wide to 38.1
        )
    }
}
