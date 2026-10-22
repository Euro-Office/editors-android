package lib.editors.gbase.ui.activities

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.SheetState
import lib.editors.base.engine.ToolbarState
import lib.editors.base.engine.TouchAction
import lib.editors.gbase.R
import lib.editors.gbase.ui.views.SheetView
import lib.editors.gbase.ui.views.cellReference
import lib.editors.gbase.ui.views.columnName
import lib.editors.resources.R as Resources

/**
 * The spreadsheet editor's parts around the sheet: the formula bar, in which cells are edited
 * (with references by tapping cells and function suggestions), the menus of headings and sheet
 * tabs, and keyboard navigation. Commands go to the engine through [edit], in order.
 */
internal class SpreadsheetUi(
    private val activity: AppCompatActivity,
    private val sheetView: SheetView,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val clipboard: Clipboard,
    /** Function names for suggestions, from the engine (on its thread). */
    private val loadFunctions: (onLoaded: (List<String>) -> Unit) -> Unit
) {

    interface Clipboard {
        fun cut()
        fun copy()
        fun paste()
    }

    private val formulaBar = activity.findViewById<View>(R.id.formulaBar)
    private val cellName = activity.findViewById<TextView>(R.id.cellName)
    private val cellText = activity.findViewById<EditText>(R.id.cellText)
    private val tabs = activity.findViewById<LinearLayout>(R.id.sheetTabs)

    private var sheet: SheetState? = null
    private var toolbar = ToolbarState()
    private var shownTabs: List<String> = emptyList()
    private var shownActive = -1
    private var functions: List<String> = emptyList()
    private var suggestions: ListPopupWindow? = null

    // A reference inserted into a formula by tapping cells; the next tap replaces it
    private var referenceStart = -1
    private var referenceEnd = -1

    // Text was sent to be entered; until the state comes back it is not pending any more, so a
    // second command can't enter it again into the cell that is active by then
    private var entering = false

    /** Read-only again: co-editing ended. */
    fun disableEditing() {
        formulaBar.isVisible = false
        suggestions?.dismiss()
        sheetView.listener = null
    }

    /** Shows the formula bar and takes input. Without it, the sheet is read-only. */
    fun enableEditing() {
        formulaBar.isVisible = true
        loadFunctions { functions = it }
        sheetView.listener = object : SheetView.SheetListener {
            override fun onSelectCells(c1: Int, r1: Int, c2: Int, r2: Int) = select(c1, r1, c2, r2)
            override fun onEditCell() = editCell(null)
            override fun onCut() = clipboard.cut()
            override fun onCopy() = clipboard.copy()
            override fun onPaste() = clipboard.paste()
            override fun onTouchDrawing(action: TouchAction, xMm: Double, yMm: Double, addToSelection: Boolean) {
                // Typed text goes into its cell before the touch selects a drawing
                enterPending()
                sheetView.requestFocus()
                edit { touchDrawing(action, xMm, yMm, 1, addToSelection) }
            }
            override fun onSelectColumns(c1: Int, c2: Int) {
                if (isEditingFormula()) return insertReference("${columnName(c1)}:${columnName(c2)}")
                val pending = takePending()
                sheetView.requestFocus()
                edit { selectColumns(c1, c2, enterText = pending) }
            }
            override fun onSelectRows(r1: Int, r2: Int) {
                if (isEditingFormula()) return insertReference("${r1 + 1}:${r2 + 1}")
                val pending = takePending()
                sheetView.requestFocus()
                edit { selectRows(r1, r2, enterText = pending) }
            }
            override fun onHeadingMenu(isColumn: Boolean, index: Int) = headingMenu(isColumn, index)
            override fun onResizeColumn(col: Int, widthMm: Double) {
                edit { selectColumns(col, col) }
                edit { setColumnWidth(widthMm) }
            }
            override fun onResizeRow(row: Int, heightMm: Double) {
                edit { selectRows(row, row) }
                edit { setRowHeight(heightMm) }
            }
            override fun onMoveSelection(dCol: Int, dRow: Int, extend: Boolean) = move(dCol, dRow, extend)
            override fun onClearCells() = edit { clearCells() }
            override fun onTypeText(text: String) = editCell(text)
            override fun onFillSelection() = edit { fillSelection() }
            override fun onSelectAll() = select(0, 0, MAX_COLUMN, MAX_ROW)
            override fun onUndo(redo: Boolean) = edit { if (redo) redo() else undo() }
            override fun onToggleStyle(style: SheetView.Style) {
                val current = toolbar
                when (style) {
                    SheetView.Style.BOLD -> edit { setBold(!current.bold) }
                    SheetView.Style.ITALIC -> edit { setItalic(!current.italic) }
                    SheetView.Style.UNDERLINE -> edit { setUnderline(!current.underline) }
                }
            }
        }
        cellText.setOnEditorActionListener { _, actionId, event ->
            // Hardware Enter comes as a key down and up (and may follow as an action): only the down counts
            val isAction = event == null && actionId == EditorInfo.IME_ACTION_DONE
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isAction || isEnter) confirm()
            isAction || event?.keyCode == KeyEvent.KEYCODE_ENTER
        }
        cellText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!cellText.hasFocus()) return
                sheetView.editingText = pendingText()
                // After the keyboard has moved the cursor too
                cellText.post { suggest() }
            }
        })
        cellText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                sheetView.editingText = null
                suggestions?.dismiss()
            }
        }
        activity.findViewById<View>(R.id.cellConfirm).setOnClickListener { confirm() }
    }

    fun setState(state: EditorState) {
        val newSheet = state.sheet ?: return
        val previous = sheet
        sheet = newSheet
        toolbar = state.toolbar
        entering = false
        sheetView.setState(newSheet, state.dirtyPages.isNotEmpty(), state.drawings)
        sheetView.foreignSelections = state.coEditing?.selections.orEmpty()

        cellName.text = newSheet.name
        // The bar shows the active cell, unless it is being edited
        val cellChanged = previous == null || previous.col != newSheet.col || previous.row != newSheet.row ||
            previous.active != newSheet.active
        if (cellChanged) {
            // Screen readers: the cell and its content
            val cell = activity.getString(
                Resources.string.editors_cells_cell_value, newSheet.name,
                newSheet.text.ifEmpty { activity.getString(Resources.string.editors_cells_empty) }
            )
            sheetView.contentDescription = cell
            if (previous != null) sheetView.announceForAccessibility(cell)
        }
        // Someone else changed the cell (co-editing) while nothing is typed in the bar
        val changedByOthers = previous != null && newSheet.text != previous.text && cellText.text.toString() == previous.text
        if (cellChanged || !cellText.hasFocus() || changedByOthers) {
            cellText.setText(newSheet.text)
            cellText.setSelection(cellText.length())
            referenceStart = -1
            sheetView.editingText = null
        }
        updateTabs(newSheet)
        newSheet.warning?.let { toast(activity.getString(Resources.string.editors_cells_refused, it)) }
    }

    // Text typed into the bar for the active cell and not entered yet
    private fun pendingText(): String? {
        val current = sheet ?: return null
        val text = cellText.text.toString()
        return if (!entering && cellText.hasFocus() && text != current.text) text else null
    }

    private fun takePending(): String? = pendingText()?.also { entering = true }

    private fun enterPending() {
        val pending = takePending() ?: return
        edit { setCellText(pending) }
    }

    private fun isEditingFormula() = cellText.hasFocus() && cellText.text.startsWith("=")

    // Selecting another cell enters what was typed first, as in desktop spreadsheets. While a
    // formula is edited, the cells go into it as a reference instead.
    private fun select(c1: Int, r1: Int, c2: Int, r2: Int) {
        if (isEditingFormula()) {
            insertReference(cellReference(c1, r1, c2, r2))
            return
        }
        val pending = takePending()
        sheetView.requestFocus()
        edit { selectCells(c1, r1, c2, r2, enterText = pending) }
    }

    private fun insertReference(reference: String) {
        val editable = cellText.text
        val cursor = cellText.selectionStart.coerceIn(0, editable.length)
        // Replaces the reference the previous tap inserted, if the cursor is still after it
        val start = if (referenceStart >= 0 && referenceEnd == cursor) referenceStart else cursor
        editable.replace(start, cursor, reference)
        referenceStart = start
        referenceEnd = start + reference.length
        cellText.setSelection(referenceEnd)
        activity.getSystemService(InputMethodManager::class.java)?.restartInput(cellText)
    }

    // Enters the text and moves to the cell below, keeping the keyboard for the next value
    private fun confirm() {
        val current = sheet ?: return
        val pending = takePending()
        suggestions?.dismiss()
        edit { selectCells(current.col, current.row + 1, current.col, current.row + 1, enterText = pending) }
    }

    // Keyboard: arrows, Tab and Enter; Shift extends the selection from the active cell
    private fun move(dCol: Int, dRow: Int, extend: Boolean) {
        val current = sheet ?: return
        if (extend) {
            val otherCol = if (current.col == current.c1) current.c2 else current.c1
            val otherRow = if (current.row == current.r1) current.r2 else current.r1
            edit { selectCells(current.col, current.row, maxOf(0, otherCol + dCol), maxOf(0, otherRow + dRow)) }
        } else {
            val pending = takePending()
            val col = maxOf(0, current.col + dCol)
            val row = maxOf(0, current.row + dRow)
            edit { selectCells(col, row, col, row, enterText = pending) }
        }
    }

    /** Edits the active cell in the formula bar; [typed] replaces its content (typing on the sheet). */
    private fun editCell(typed: String?) {
        cellText.requestFocus()
        if (typed != null) cellText.setText(typed)
        cellText.setSelection(cellText.length())
        sheetView.editingText = pendingText()
        activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(cellText, 0)
    }

    // Functions starting with the name being typed after "=", an operator or "("
    private fun suggest() {
        val text = cellText.text.toString()
        val cursor = cellText.selectionStart
        if (!cellText.hasFocus() || !text.startsWith("=") || cursor <= 0 || functions.isEmpty()) {
            suggestions?.dismiss()
            return
        }
        var start = cursor
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '.')) start--
        val prefix = text.substring(start, cursor).uppercase()
        val matches = if (prefix.isEmpty() || prefix[0].isDigit()) emptyList() else functions.filter { it.startsWith(prefix) }.take(8)
        if (matches.isEmpty() || (matches.size == 1 && matches[0] == prefix)) {
            suggestions?.dismiss()
            return
        }
        val popup = suggestions ?: ListPopupWindow(activity).also {
            it.anchorView = formulaBar
            it.isModal = false
            suggestions = it
        }
        popup.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, matches))
        popup.setOnItemClickListener { parent, _, position, _ ->
            // The name being typed at the time of the click
            val editable = cellText.text
            val end = cellText.selectionStart.coerceIn(0, editable.length)
            var from = end
            while (from > 0 && (editable[from - 1].isLetterOrDigit() || editable[from - 1] == '.')) from--
            val name = parent.getItemAtPosition(position).toString() + "("
            editable.replace(from, end, name)
            cellText.setSelection(from + name.length)
            // The keyboard must not commit what it was composing again
            activity.getSystemService(InputMethodManager::class.java)?.restartInput(cellText)
            popup.dismiss()
        }
        popup.show()
    }

    // ---------------------------------------------------------------------------------
    // Menus

    private fun headingMenu(isColumn: Boolean, index: Int) {
        enterPending()
        if (isColumn) edit { selectColumns(index, index) } else edit { selectRows(index, index) }
        val items: List<Pair<Int, () -> Unit>> = if (isColumn) {
            listOf(
                Resources.string.editors_cells_insert_left to { edit { insertCells(INSERT_COLUMNS) } },
                Resources.string.editors_cells_insert_right to {
                    edit { selectColumns(index + 1, index + 1) }
                    edit { insertCells(INSERT_COLUMNS) }
                },
                Resources.string.editors_cells_delete_column to { edit { deleteCells(DELETE_COLUMNS) } },
                Resources.string.editors_cells_hide to { edit { hideColumns(true) } },
                Resources.string.editors_cells_show to {
                    edit { selectColumns(maxOf(0, index - 1), index + 1) }
                    edit { hideColumns(false) }
                },
                Resources.string.editors_cells_autofit to { edit { autoFitColumns() } },
                Resources.string.editors_cells_freeze to { edit { freezePanes(index + 1, sheet?.frozenRow ?: 0) } }
            )
        } else {
            listOf(
                Resources.string.editors_cells_insert_above to { edit { insertCells(INSERT_ROWS) } },
                Resources.string.editors_cells_insert_below to {
                    edit { selectRows(index + 1, index + 1) }
                    edit { insertCells(INSERT_ROWS) }
                },
                Resources.string.editors_cells_delete_row to { edit { deleteCells(DELETE_ROWS) } },
                Resources.string.editors_cells_hide to { edit { hideRows(true) } },
                Resources.string.editors_cells_show to {
                    edit { selectRows(maxOf(0, index - 1), index + 1) }
                    edit { hideRows(false) }
                },
                Resources.string.editors_cells_freeze to { edit { freezePanes(sheet?.frozenCol ?: 0, index + 1) } }
            )
        } + listOf(Resources.string.editors_cells_unfreeze to { edit { freezePanes(0, 0) } })
        AlertDialog.Builder(activity)
            .setTitle(if (isColumn) columnName(index) else (index + 1).toString())
            .setItems(items.map { activity.getString(it.first) }.toTypedArray()) { _, position -> items[position].second() }
            .show()
    }

    private fun updateTabs(current: SheetState) {
        val names = current.sheets.map { it.name + it.hidden }
        if (names == shownTabs && current.active == shownActive) return
        shownTabs = names
        shownActive = current.active
        tabs.removeAllViews()
        val density = activity.resources.displayMetrics.density
        fun tab(text: String, selected: Boolean, onClick: () -> Unit) = TextView(activity).apply {
            this.text = text
            gravity = Gravity.CENTER
            setPaddingRelative((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            setBackgroundResource(R.drawable.editor_tool_background)
            isSelected = selected
            setOnClickListener { onClick() }
        }.also { tabs.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)) }

        current.sheets.forEachIndexed { index, sheetInfo ->
            if (sheetInfo.hidden) return@forEachIndexed
            tab(sheetInfo.name, index == current.active) {
                if (index != sheet?.active) {
                    val pending = takePending()
                    edit { setActiveSheet(index, enterText = pending) }
                }
            }.setOnLongClickListener { view ->
                if (index != sheet?.active) edit { setActiveSheet(index) }
                sheetMenu(view, index)
                true
            }
        }
        // A new sheet
        tab("+", false) {
            val count = sheet?.sheets?.size ?: 0
            edit { addSheet(activity.getString(Resources.string.editors_cells_sheet_name, count + 1)) }
        }.contentDescription = activity.getString(Resources.string.editors_cells_add_sheet)
    }

    private fun sheetMenu(anchor: View, index: Int) {
        val current = sheet ?: return
        val visible = current.sheets.withIndex().filter { !it.value.hidden }.map { it.index }
        val position = visible.indexOf(index)
        PopupMenu(activity, anchor).apply {
            menu.add(0, 0, 0, Resources.string.editors_cells_rename_sheet)
            if (position > 0) menu.add(0, 1, 1, Resources.string.editors_cells_move_left)
            if (position >= 0 && position < visible.size - 1) menu.add(0, 2, 2, Resources.string.editors_cells_move_right)
            if (visible.size > 1) {
                menu.add(0, 3, 3, Resources.string.editors_cells_hide_sheet)
                menu.add(0, 4, 4, Resources.string.editors_cells_delete_sheet)
            }
            if (current.sheets.any { it.hidden }) menu.add(0, 5, 5, Resources.string.editors_cells_show_sheets)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> renameSheet(current.sheets[index].name)
                    1 -> edit { moveSheet(visible[position - 1]) }
                    // Before the sheet after the next one, or at the end
                    2 -> edit { moveSheet(visible.getOrNull(position + 2) ?: current.sheets.size) }
                    3 -> edit { hideSheet() }
                    4 -> AlertDialog.Builder(activity)
                        .setMessage(activity.getString(Resources.string.editors_cells_delete_sheet_confirm, current.sheets[index].name))
                        .setPositiveButton(Resources.string.editors_delete) { _, _ -> edit { deleteSheet() } }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    5 -> showHiddenSheets(current)
                }
                true
            }
        }.show()
    }

    private fun renameSheet(name: String) {
        val input = EditText(activity).apply {
            setText(name)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            selectAll()
        }
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        AlertDialog.Builder(activity)
            .setTitle(Resources.string.editors_cells_rename_sheet)
            .setView(FrameLayout(activity).apply {
                setPaddingRelative(padding, 0, padding, 0)
                addView(input)
            })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != name) edit { renameSheet(newName) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHiddenSheets(current: SheetState) {
        val hidden = current.sheets.withIndex().filter { it.value.hidden }
        AlertDialog.Builder(activity)
            .setTitle(Resources.string.editors_cells_show_sheets)
            .setItems(hidden.map { it.value.name }.toTypedArray()) { _, which -> edit { showSheet(hidden[which].index) } }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private companion object {
        // Asc.c_oAscInsertOptions / c_oAscDeleteOptions
        const val INSERT_COLUMNS = 3
        const val INSERT_ROWS = 4
        const val DELETE_COLUMNS = 3
        const val DELETE_ROWS = 4
        // The last column and row of a sheet
        const val MAX_COLUMN = 16383
        const val MAX_ROW = 1048575
    }
}
