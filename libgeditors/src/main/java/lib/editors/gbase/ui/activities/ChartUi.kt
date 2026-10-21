package lib.editors.gbase.ui.activities

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
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
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.ChartData
import lib.editors.base.engine.ChartState
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.resources.R as Resources

/**
 * Charts: choosing a type (to insert or change), the chart's elements (title, legend, value
 * labels, gridlines) and, in documents and presentations, its data in a table.
 */
internal class ChartUi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val edit: (EditorDocument.() -> EditorState) -> Unit
) {

    private val density = context.resources.displayMetrics.density

    fun chooseType(title: Int, onType: (Int) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(TYPES.map { context.getString(it.first) }.toTypedArray()) { _, index -> onType(TYPES[index].second) }
            .show()
    }

    fun chooseSmartArt() {
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_smartart_insert)
            .setItems(SMART_ARTS.map { context.getString(it.first) }.toTypedArray()) { _, index ->
                val (_, type, fileName) = SMART_ARTS[index]
                edit { insertSmartArt(type, fileName) }
            }
            .show()
    }

    fun showElements(chart: ChartState) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(24), dp(8), dp(24), 0)
        }
        val title = CheckBox(context).apply { setText(Resources.string.editors_chart_title); isChecked = chart.title }
        val labels = CheckBox(context).apply { setText(Resources.string.editors_chart_labels); isChecked = chart.labels }
        val gridlines = CheckBox(context).apply { setText(Resources.string.editors_chart_gridlines); isChecked = chart.gridlines }
        content.addView(title)
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
                edit { setChartElements(title.isChecked, position, labels.isChecked, gridlines.isChecked) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        // Asc.c_oAscChartTypeSettings
        val TYPES = listOf(
            Resources.string.editors_chart_column to 0,
            Resources.string.editors_chart_column_stacked to 1,
            Resources.string.editors_chart_bar to 16,
            Resources.string.editors_chart_line to 7,
            Resources.string.editors_chart_line_markers to 10,
            Resources.string.editors_chart_pie to 14,
            Resources.string.editors_chart_doughnut to 25,
            Resources.string.editors_chart_area to 22,
            Resources.string.editors_chart_scatter to 30,
            Resources.string.editors_chart_radar to 42
        )
        // Asc.c_oAscSmartArtTypes and their definition files, packaged with the engine (build/scripts/sdkjs.sh)
        val SMART_ARTS = listOf(
            Triple(Resources.string.editors_smartart_list, 77, "Basic_Block_List"),
            Triple(Resources.string.editors_smartart_process, 79, "Basic_Process"),
            Triple(Resources.string.editors_smartart_cycle, 81, "Basic_Cycle"),
            Triple(Resources.string.editors_smartart_hierarchy, 59, "Organization_Chart"),
            Triple(Resources.string.editors_smartart_venn, 70, "Basic_Venn"),
            Triple(Resources.string.editors_smartart_pyramid, 74, "Basic_Pyramid"),
            Triple(Resources.string.editors_smartart_bullets, 9, "Vertical_Bullet_List"),
            Triple(Resources.string.editors_smartart_timeline, 71, "Basic_Timeline")
        )
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
