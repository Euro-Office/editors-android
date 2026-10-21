package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import lib.editors.base.engine.CellFormat
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * Spreadsheets: the format of the selected cells (number format, font, colors, borders, wrap,
 * merge, alignment) in a panel (see [PanelHost]). It shows the active cell's format (see [update]).
 */
internal class CellFormatPanel(
    private val context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val loadFonts: (onLoaded: (List<String>) -> Unit) -> Unit,
    private val panels: PanelHost
) {

    private val density = context.resources.displayMetrics.density
    private var dialog: PanelHost.Panel? = null
    private var format = CellFormat()
    private var fonts: List<String> = emptyList()
    private val updaters = mutableListOf<(CellFormat) -> Unit>()

    fun show() {
        loadFonts { fonts = it }
        updaters.clear()
        val content = content()
        dialog = panels.show(context.getString(Resources.string.editors_format), content) { dialog = null }
        update(format)
    }

    fun update(newFormat: CellFormat) {
        format = newFormat
        if (dialog != null) updaters.forEach { it(newFormat) }
    }

    private fun content(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(8), dp(16), dp(24))

        heading(Resources.string.editors_cells_number_format)
        scrollRow {
            for ((label, type) in NUMBER_FORMATS) {
                toggle(context.getString(label), { it.numFormatType == type }) { edit { setNumberFormatType(type) } }
            }
        }
        row {
            button(context.getString(Resources.string.editors_cells_fewer_decimals)) { edit { changeDecimals(false) } }
            button(context.getString(Resources.string.editors_cells_more_decimals)) { edit { changeDecimals(true) } }
        }

        heading(Resources.string.editors_format_text)
        row {
            val fontButton = button("") { chooseFont() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            updaters += { fontButton.text = it.fontName.ifEmpty { "—" } }
            button("−") { edit { setFontSize(maxOf(1.0, format.fontSize - 1)) } }.contentDescription = context.getString(Resources.string.editors_format_size_down)
            val size = TextView(context).apply { gravity = Gravity.CENTER; minWidth = dp(40) }.also { addView(it) }
            updaters += {
                size.text = if (it.fontSize > 0) it.fontSize.toInt().toString() else "—"
                size.contentDescription = context.getString(Resources.string.editors_format_size) + " " + size.text
            }
            button("+") { edit { setFontSize(format.fontSize + 1) } }.contentDescription = context.getString(Resources.string.editors_format_size_up)
            toggle(context.getString(Resources.string.editors_format_strikeout), { it.strikeout }) { edit { setStrikeout(!format.strikeout) } }
                .paint.flags = Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
        }
        caption(Resources.string.editors_format_color)
        colors(TEXT_COLORS, { it.color }) { color -> edit { setTextColor(color) } }
        caption(Resources.string.editors_cells_fill_color)
        colors(FILLS, { it.fill }) { color -> edit { setCellFill(color) } }

        heading(Resources.string.editors_cells_cells)
        caption(Resources.string.editors_cells_borders)
        row {
            for ((label, kind) in BORDERS) button(context.getString(label)) { edit { setBorders(kind) } }
        }
        row {
            toggle(context.getString(Resources.string.editors_cells_wrap), { it.wrap }) { edit { setWrap(!format.wrap) } }
            toggle(context.getString(Resources.string.editors_cells_merge), { it.merged }) { edit { setMerge(!format.merged) } }
        }
        caption(Resources.string.editors_cells_vertical_align)
        row {
            for ((label, value) in VERTICAL_ALIGNS) {
                toggle(context.getString(label), { it.verticalAlign == value }) { edit { setVerticalAlign(value) } }
            }
        }
    }

    private fun chooseFont() {
        if (fonts.isEmpty()) return
        val names = fonts.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_format_font)
            .setItems(names) { _, index -> edit { setFontName(names[index]) } }
            .show()
    }

    // ---------------------------------------------------------------------------------
    // Views (as in FormatPanel)

    private fun dp(value: Int) = (value * density).toInt()

    private fun LinearLayout.heading(text: Int) {
        addView(TextView(context).apply {
            setText(text)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPaddingRelative(0, dp(12), 0, dp(4))
        })
    }

    private fun LinearLayout.caption(text: Int) {
        addView(TextView(context).apply {
            setText(text)
            setPaddingRelative(0, dp(8), 0, dp(2))
        })
    }

    private fun LinearLayout.row(build: LinearLayout.() -> Unit) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            build()
        })
    }

    private fun LinearLayout.scrollRow(build: LinearLayout.() -> Unit) {
        addView(HorizontalScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                build()
            })
        })
    }

    private fun LinearLayout.button(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 15f
        minWidth = dp(48)
        minHeight = dp(48)
        setPaddingRelative(dp(10), 0, dp(10), 0)
        setBackgroundResource(R.drawable.editor_tool_background)
        setOnClickListener { onClick() }
    }.also { addView(it) }

    private fun LinearLayout.toggle(text: String, isOn: (CellFormat) -> Boolean, onClick: () -> Unit): TextView =
        button(text, onClick).also { view ->
            Accessibility.toggle(view)
            updaters += { view.isSelected = isOn(it) }
        }

    private fun LinearLayout.colors(colors: List<String>, current: (CellFormat) -> String, onColor: (String?) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (color in colors) {
            val swatch = View(context).apply {
                // 48 dp to touch, 36 dp to see
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setOnClickListener { onColor(color.ifEmpty { null }) }
                contentDescription = Accessibility.colorName(context, color)
            }
            updaters += {
                swatch.isSelected = current(it).equals(color, ignoreCase = true)
                swatch.background = android.graphics.drawable.InsetDrawable(swatchDrawable(color, swatch.isSelected), dp(6))
            }
            row.addView(swatch)
        }
        addView(HorizontalScrollView(context).apply { addView(row) })
    }

    private fun swatchDrawable(color: String, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (color.isEmpty()) Color.TRANSPARENT else Color.parseColor("#$color"))
        val border = ContextCompat.getColor(context, if (selected) Resources.color.editors_selection_handle else Resources.color.editors_sheet_header_line)
        setStroke(if (selected) dp(3) else dp(1), border)
    }

    private companion object {
        // Asc.c_oAscNumFormatType
        val NUMBER_FORMATS = listOf(
            Resources.string.editors_cells_format_general to 0,
            Resources.string.editors_cells_format_number to 1,
            Resources.string.editors_cells_format_currency to 4,
            Resources.string.editors_cells_format_percent to 8,
            Resources.string.editors_cells_format_date to 5,
            Resources.string.editors_cells_format_text to 10
        )
        val BORDERS = listOf(
            Resources.string.editors_cells_borders_all to "all",
            Resources.string.editors_cells_borders_outer to "outer",
            Resources.string.editors_cells_borders_bottom to "bottom",
            Resources.string.editors_cells_borders_none to "none"
        )
        // Asc.c_oAscVAlign
        val VERTICAL_ALIGNS = listOf(
            Resources.string.editors_cells_align_top to 4,
            Resources.string.editors_cells_align_middle to 1,
            Resources.string.editors_cells_align_bottom to 0
        )
        val TEXT_COLORS = listOf("000000", "595959", "C00000", "FF0000", "FFC000", "00B050", "0070C0", "002060", "7030A0")
        val FILLS = listOf("", "FFFFFF", "F2F2F2", "FFF2CC", "FCE4D6", "E2EFDA", "DDEBF7", "FFFF00", "FFC000", "92D050")
    }
}
