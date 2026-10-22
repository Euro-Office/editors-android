package lib.editors.gbase.ui.activities

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.ChartData
import lib.editors.base.engine.ChartState
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * Charts: choosing a type (to insert or change), the chart's titles and elements (legend, value
 * labels, gridlines) and its data: in documents and presentations in a table, in spreadsheets
 * the cells it shows, typed or selected on the sheet.
 */
internal class ChartUi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val picker: IconPicker,
    /** Shows a bar under the top bar (the one while the chart's cells are selected on the sheet), null hides it. */
    private val showBar: (View?) -> Unit,
    private val edit: (EditorDocument.() -> EditorState) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Chart types with their pictures, [selected] (the chart's) highlighted. */
    fun chooseType(title: Int, selected: Int? = null, onType: (Int) -> Unit) =
        picker.show(title, InsertCatalog.CHARTS, selected, onPick = onType)

    /** SmartArt layouts with their pictures, in web-apps' groups. */
    fun chooseSmartArt() =
        picker.show(Resources.string.editors_smartart_insert, InsertCatalog.SMART_ARTS, pictures = true) { layout ->
            edit { insertSmartArt(layout.type, layout.file) }
        }

    /** The texts of the chart's title and, if it has axes, their titles; an empty one is removed. */
    fun showTitles(chart: ChartState) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(24), dp(8), dp(24), 0)
        }
        fun field(label: Int, value: String) = EditText(context).apply {
            setText(value)
            hint = context.getString(label)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }.also { field ->
            content.addView(TextView(context).apply {
                setText(label)
                setPaddingRelative(0, dp(8), 0, 0)
            })
            content.addView(field)
        }
        val title = field(Resources.string.editors_chart_title, chart.titleText)
        val horizontal = if (chart.axes) field(Resources.string.editors_chart_axis_horizontal, chart.horizontalTitle) else null
        val vertical = if (chart.axes) field(Resources.string.editors_chart_axis_vertical, chart.verticalTitle) else null
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_chart_titles)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                edit {
                    setChartTitles(
                        title.text.toString().trim(),
                        horizontal?.text?.toString()?.trim() ?: chart.horizontalTitle,
                        vertical?.text?.toString()?.trim() ?: chart.verticalTitle
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Legend, value labels and gridlines; the title is set in [showTitles]. */
    fun showElements(chart: ChartState) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(24), dp(8), dp(24), 0)
        }
        val labels = CheckBox(context).apply { setText(Resources.string.editors_chart_labels); isChecked = chart.labels }
        val gridlines = CheckBox(context).apply { setText(Resources.string.editors_chart_gridlines); isChecked = chart.gridlines }
        content.addView(labels)
        content.addView(gridlines)
        content.addView(TextView(context).apply {
            setText(Resources.string.editors_chart_legend)
            setPaddingRelative(0, dp(12), 0, dp(4))
        })
        val legend = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        LEGENDS.forEach { (label, value) ->
            legend.addView(RadioButton(context).apply {
                id = View.generateViewId()
                setText(label)
                isChecked = value == chart.legend
                tag = value
            })
        }
        content.addView(HorizontalScrollView(context).apply { addView(legend) })
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_chart_elements)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val position = legend.findViewById<RadioButton>(legend.checkedRadioButtonId)?.tag as? Int ?: chart.legend
                edit { setChartElements(chart.title, position, labels.isChecked, gridlines.isChecked) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------------------
    // Spreadsheets: the cells the chart shows

    fun editRange(chart: ChartState) {
        val range = EditText(context).apply {
            setText(chart.range.removePrefix("="))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        lateinit var dialog: AlertDialog
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(24), dp(8), dp(24), 0)
            addView(TextView(context).apply { setText(Resources.string.editors_chart_range) })
            addView(range)
            fun button(label: Int, action: () -> Unit) = addView(TextView(context).apply {
                setText(label)
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(context.getColor(Resources.color.editors_selection_handle))
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            })
            button(Resources.string.editors_chart_range_select) { pickRange() }
            button(Resources.string.editors_chart_switch) { edit { switchChartRowCol() } }
        }
        dialog = AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_chart_data)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = range.text.toString().trim()
                if (typed.isNotEmpty() && typed != chart.range.removePrefix("=")) edit { setChartRange(typed) { rangeError() } }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // The cells are selected on the sheet while a bar says so; Done gives them to the chart
    private fun pickRange() {
        edit { startChartRange() }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(16), 0, dp(4), 0)
            minimumHeight = dp(48)
            addView(TextView(context).apply {
                setText(Resources.string.editors_chart_range_hint)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            fun button(label: Int, apply: Boolean) = addView(TextView(context).apply {
                setText(label)
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPaddingRelative(dp(12), 0, dp(12), 0)
                setTextColor(context.getColor(Resources.color.editors_selection_handle))
                setBackgroundResource(R.drawable.editor_tool_background)
                setOnClickListener {
                    showBar(null)
                    edit { endChartRange(apply) { rangeError() } }
                }
            })
            button(android.R.string.cancel, apply = false)
            button(Resources.string.editors_done, apply = true)
        }
        showBar(bar)
    }

    // On the engine's thread
    private fun rangeError() {
        mainHandler.post { Toast.makeText(context, Resources.string.editors_chart_range_invalid, Toast.LENGTH_LONG).show() }
    }

    // ---------------------------------------------------------------------------------
    // Data: categories in the first column, a column per series

    fun editData() {
        val document = document() ?: return
        scope.launch {
            val data = withContext(EditorEngine.dispatcher) { runCatching { document.chartData() }.getOrNull() } ?: return@launch
            showDataTable(data)
        }
    }

    private fun showDataTable(initial: ChartData) {
        // Cells as text: [row][column], row 0 the series names, column 0 the categories
        val cells = mutableListOf<MutableList<String>>()
        cells += (listOf("") + initial.series).toMutableList()
        initial.categories.forEachIndexed { row, category ->
            cells += (listOf(category) + initial.values.map { series -> series.getOrNull(row)?.let(::number) ?: "" }).toMutableList()
        }
        val table = TableLayout(context)
        fun rebuild() {
            table.removeAllViews()
            cells.forEachIndexed { r, row ->
                table.addView(TableRow(context).apply {
                    row.forEachIndexed { c, value ->
                        addView(EditText(context).apply {
                            setText(value)
                            isSingleLine = true
                            minWidth = dp(if (c == 0) 96 else 72)
                            textSize = 14f
                            gravity = if (r > 0 && c > 0) Gravity.END else Gravity.START
                            isEnabled = !(r == 0 && c == 0)
                            inputType = if (r > 0 && c > 0) {
                                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                            } else {
                                InputType.TYPE_CLASS_TEXT
                            }
                            addTextChangedListener(object : android.text.TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                                override fun afterTextChanged(s: android.text.Editable?) {
                                    cells[r][c] = s.toString()
                                }
                            })
                        })
                    }
                })
            }
        }
        rebuild()
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            fun button(label: Int, action: () -> Unit) = addView(TextView(context).apply {
                setText(label)
                setPaddingRelative(dp(8), dp(10), dp(8), dp(10))
                setTextColor(context.getColor(Resources.color.editors_selection_handle))
                setOnClickListener { action(); rebuild() }
            })
            button(Resources.string.editors_chart_add_category) { cells += MutableList(cells[0].size) { "" } }
            button(Resources.string.editors_chart_remove_category) { if (cells.size > 2) cells.removeAt(cells.size - 1) }
            button(Resources.string.editors_chart_add_series) { cells.forEach { it += "" } }
            button(Resources.string.editors_chart_remove_series) { if (cells[0].size > 2) cells.forEach { it.removeAt(it.size - 1) } }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), 0)
            addView(HorizontalScrollView(context).apply { addView(buttons) })
            addView(HorizontalScrollView(context).apply { addView(table) })
        }
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_chart_data)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val series = cells[0].drop(1)
                val categories = cells.drop(1).map { it[0] }
                val values = series.indices.map { s -> cells.drop(1).map { row -> row[s + 1].replace(',', '.').toDoubleOrNull() } }
                edit { setChartData(ChartData(series, categories, values)) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun number(value: Double): String =
        if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        // Asc.c_oAscChartLegendShowSettings
        val LEGENDS = listOf(
            Resources.string.editors_chart_legend_none to 0,
            Resources.string.editors_chart_legend_bottom to 4,
            Resources.string.editors_chart_legend_right to 3,
            Resources.string.editors_chart_legend_top to 2,
            Resources.string.editors_chart_legend_left to 1
        )
    }
}
