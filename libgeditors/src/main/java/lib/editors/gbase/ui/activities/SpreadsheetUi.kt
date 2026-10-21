package lib.editors.gbase.ui.activities

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.SheetState
import lib.editors.gbase.R
import lib.editors.gbase.ui.views.SheetView

/**
 * The spreadsheet editor's parts around the sheet: the formula bar, in which cells are edited,
 * and the sheet tabs. Commands go to the engine through [edit], in order.
 */
internal class SpreadsheetUi(
    private val activity: AppCompatActivity,
    private val sheetView: SheetView,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val clipboard: Clipboard
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
    private var shownTabs: List<String> = emptyList()
    private var shownActive = -1

    /** Shows the formula bar and takes input. Without it, the sheet is read-only. */
    fun enableEditing() {
        formulaBar.isVisible = true
        sheetView.listener = object : SheetView.SheetListener {
            override fun onSelectCells(c1: Int, r1: Int, c2: Int, r2: Int) = select(c1, r1, c2, r2)
            override fun onEditCell() = editCell()
            override fun onCut() = clipboard.cut()
            override fun onCopy() = clipboard.copy()
            override fun onPaste() = clipboard.paste()
        }
        cellText.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                confirm()
                true
            } else {
                false
            }
        }
        activity.findViewById<View>(R.id.cellConfirm).setOnClickListener { confirm() }
    }

    fun setState(state: EditorState) {
        val newSheet = state.sheet ?: return
        val previous = sheet
        sheet = newSheet
        sheetView.setState(newSheet, state.dirtyPages.isNotEmpty())

        cellName.text = newSheet.name
        // The bar shows the active cell, unless it is being edited
        val cellChanged = previous == null || previous.col != newSheet.col || previous.row != newSheet.row ||
            previous.active != newSheet.active
        if (cellChanged || !cellText.hasFocus()) {
            cellText.setText(newSheet.text)
            cellText.setSelection(cellText.length())
        }
        updateTabs(newSheet)
    }

    // Text typed into the bar for the active cell and not entered yet
    private fun pendingText(): String? {
        val current = sheet ?: return null
        val text = cellText.text.toString()
        return if (cellText.hasFocus() && text != current.text) text else null
    }

    // Selecting another cell enters what was typed first, as in desktop spreadsheets
    private fun select(c1: Int, r1: Int, c2: Int, r2: Int) {
        val pending = pendingText()
        edit { selectCells(c1, r1, c2, r2, enterText = pending) }
    }

    // Enters the text and moves to the cell below, keeping the keyboard for the next value
    private fun confirm() {
        val current = sheet ?: return
        val pending = pendingText()
        edit { selectCells(current.col, current.row + 1, current.col, current.row + 1, enterText = pending) }
    }

    private fun editCell() {
        cellText.requestFocus()
        cellText.setSelection(cellText.length())
        activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(cellText, 0)
    }

    private fun updateTabs(current: SheetState) {
        val names = current.sheets.map { it.name }
        if (names == shownTabs && current.active == shownActive) return
        shownTabs = names
        shownActive = current.active
        tabs.removeAllViews()
        val density = activity.resources.displayMetrics.density
        current.sheets.forEachIndexed { index, sheetInfo ->
            if (sheetInfo.hidden) return@forEachIndexed
            val tab = TextView(activity).apply {
                text = sheetInfo.name
                gravity = android.view.Gravity.CENTER
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                setBackgroundResource(R.drawable.editor_tool_background)
                isSelected = index == current.active
                setOnClickListener {
                    if (index == sheet?.active) return@setOnClickListener
                    val pending = pendingText()
                    edit { setActiveSheet(index, enterText = pending) }
                }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }
}
