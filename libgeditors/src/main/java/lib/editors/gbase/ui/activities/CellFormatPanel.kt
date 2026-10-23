package lib.editors.gbase.ui.activities

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import lib.editors.base.engine.CellFormat
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.resources.R as Resources

/**
 * Spreadsheets: the format of the selected cells (number format, font, colors, borders, wrap,
 * merge, alignment) in a panel (see [PanelHost]). It shows the active cell's format (see [update]).
 */
internal class CellFormatPanel(
    context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val loadFonts: (onLoaded: (List<String>) -> Unit) -> Unit,
    private val panels: PanelHost
) : PanelBuilder<CellFormat>(context) {

    private var dialog: PanelHost.Panel? = null
    private var format = CellFormat()
    private var fonts: List<String> = emptyList()

    // A chart, picture or shape is selected: only its text's font applies to it (sdkjs would
    // apply the cells' settings to the cells under it)
    private var drawingSelected = false
    private val cellsOnly = mutableListOf<View>()

    fun show() {
        loadFonts { fonts = it }
        updaters.clear()
        cellsOnly.clear()
        val content = content()
        dialog = panels.show(context.getString(Resources.string.editors_format), content) { dialog = null }
        update(format, drawingSelected)
    }

    /** The format of the active cell, or of the selected drawing's text ([drawing]). */
    fun update(newFormat: CellFormat, drawing: Boolean = false) {
        format = newFormat
        drawingSelected = drawing
        if (dialog != null) {
            updaters.forEach { it(newFormat) }
            cellsOnly.forEach { it.isVisible = !drawing }
        }
    }

    private fun content(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(8), dp(16), dp(24))

        cellSection {
            heading(Resources.string.editors_cells_number_format)
            scrollRow {
                for ((label, type, icon) in NUMBER_FORMATS) {
                    choice(context.getString(label), { it.numFormatType == type }, icon) { edit { setNumberFormatType(type) } }
                }
            }
            row {
                group {
                    icon(Resources.drawable.ic_editor_decimals_less, Resources.string.editors_cells_fewer_decimals) { edit { changeDecimals(false) } }
                    icon(Resources.drawable.ic_editor_decimals_more, Resources.string.editors_cells_more_decimals) { edit { changeDecimals(true) } }
                }
            }
        }

        heading(Resources.string.editors_format_text)
        row {
            val fontButton = button("") { chooseFont() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
            updaters += { fontButton.text = it.fontName.ifEmpty { "—" } }
            // Not without a size (text of different sizes)
            button("−") { if (format.fontSize > 1) edit { setFontSize(format.fontSize - 1) } }.contentDescription = context.getString(Resources.string.editors_format_size_down)
            val size = TextView(context).apply { gravity = Gravity.CENTER; minWidth = dp(40) }.also { addView(it) }
            updaters += {
                size.text = if (it.fontSize > 0) it.fontSize.toInt().toString() else "—"
                size.contentDescription = context.getString(Resources.string.editors_format_size) + " " + size.text
            }
            button("+") { if (format.fontSize > 0) edit { setFontSize(format.fontSize + 1) } }.contentDescription = context.getString(Resources.string.editors_format_size_up)
        }
        row {
            group {
                icon(Resources.drawable.ic_editor_strikeout, Resources.string.editors_format_strikeout, { it.strikeout }) { edit { setStrikeout(!format.strikeout) } }
            }
        }
        caption(Resources.string.editors_format_color)
        colors(TEXT_COLORS, { it.color }) { color -> edit { setTextColor(color) } }
        cellSection {
            caption(Resources.string.editors_cells_fill_color)
            colors(FILLS, { it.fill }) { color -> edit { setCellFill(color) } }

            heading(Resources.string.editors_cells_cells)
            caption(Resources.string.editors_cells_borders)
            scrollRow {
                group {
                    for ((icon, label, kind) in BORDERS) icon(icon, label) { edit { setBorders(kind) } }
                }
            }
            caption(Resources.string.editors_cells_vertical_align)
            scrollRow {
                group {
                    for ((icon, label, value) in VERTICAL_ALIGNS) {
                        icon(icon, label, { it.verticalAlign == value }) { edit { setVerticalAlign(value) } }
                    }
                }
                group {
                    icon(Resources.drawable.ic_editor_wrap_text, Resources.string.editors_cells_wrap, { it.wrap }) { edit { setWrap(!format.wrap) } }
                    icon(Resources.drawable.ic_editor_merge_cells, Resources.string.editors_cells_merge, { it.merged }) { edit { setMerge(!format.merged) } }
                }
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

    // Settings of cells only, hidden while a drawing is selected
    private fun LinearLayout.cellSection(build: LinearLayout.() -> Unit) {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            build()
        }
        addView(section)
        cellsOnly += section
    }

    private companion object {
        // Asc.c_oAscNumFormatType
        val NUMBER_FORMATS = listOf(
            Triple(Resources.string.editors_cells_format_general, 0, Resources.drawable.ic_editor_format_general),
            Triple(Resources.string.editors_cells_format_number, 1, Resources.drawable.ic_editor_format_number),
            Triple(Resources.string.editors_cells_format_currency, 4, Resources.drawable.ic_editor_format_currency),
            Triple(Resources.string.editors_cells_format_percent, 8, Resources.drawable.ic_editor_format_percent),
            Triple(Resources.string.editors_cells_format_date, 5, Resources.drawable.ic_editor_format_date),
            Triple(Resources.string.editors_cells_format_text, 10, Resources.drawable.ic_editor_format_text)
        )
        val BORDERS = listOf(
            Triple(Resources.drawable.ic_editor_border_all, Resources.string.editors_cells_borders_all, "all"),
            Triple(Resources.drawable.ic_editor_border_outer, Resources.string.editors_cells_borders_outer, "outer"),
            Triple(Resources.drawable.ic_editor_border_inner, Resources.string.editors_cells_borders_inner, "inner"),
            Triple(Resources.drawable.ic_editor_border_top, Resources.string.editors_cells_borders_top, "top"),
            Triple(Resources.drawable.ic_editor_border_bottom, Resources.string.editors_cells_borders_bottom, "bottom"),
            Triple(Resources.drawable.ic_editor_border_left, Resources.string.editors_cells_borders_left, "left"),
            Triple(Resources.drawable.ic_editor_border_right, Resources.string.editors_cells_borders_right, "right"),
            Triple(Resources.drawable.ic_editor_border_none, Resources.string.editors_cells_borders_none, "none")
        )
        // Asc.c_oAscVAlign
        val VERTICAL_ALIGNS = listOf(
            Triple(Resources.drawable.ic_editor_valign_top, Resources.string.editors_cells_align_top, 4),
            Triple(Resources.drawable.ic_editor_valign_middle, Resources.string.editors_cells_align_middle, 1),
            Triple(Resources.drawable.ic_editor_valign_bottom, Resources.string.editors_cells_align_bottom, 0)
        )
        val TEXT_COLORS = listOf("000000", "595959", "C00000", "FF0000", "FFC000", "00B050", "0070C0", "002060", "7030A0")
        val FILLS = listOf("", "FFFFFF", "F2F2F2", "FFF2CC", "FCE4D6", "E2EFDA", "DDEBF7", "FFFF00", "FFC000", "92D050")
    }
}
