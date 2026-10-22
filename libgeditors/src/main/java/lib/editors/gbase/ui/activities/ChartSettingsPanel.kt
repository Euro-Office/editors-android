package lib.editors.gbase.ui.activities

import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import lib.editors.base.engine.ChartAxis
import lib.editors.base.engine.ChartState
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * A chart's style (pictures of the type in its styles), layout (title, legend, axes' titles,
 * gridlines, value labels) and axes (minimum, maximum, units, where the other axis crosses, tick
 * marks, labels), in panels (see [PanelHost]) as web-apps' mobile chart settings. A setting is
 * applied as it is chosen.
 */
internal class ChartSettingsPanel(
    private val context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val panels: PanelHost,
    private val previews: PreviewPicker
) {

    private val density = context.resources.displayMetrics.density

    fun chooseStyle(chart: ChartState) =
        previews.show(context.getString(Resources.string.editors_chart_style), "chartStyles", chart.style?.toString(), columnWidthDp = 88, keepOpen = true) {
            it.id.toIntOrNull()?.let { style -> edit { setChartStyle(style) } }
        }

    fun showLayout(chart: ChartState) {
        val layout = chart.layout ?: return
        fun set(name: String, value: Int) = edit { setChartLayout(mapOf(name to value)) }
        val content = column {
            choice(Resources.string.editors_chart_title, TITLES, layout.title) { set("title", it) }
            choice(Resources.string.editors_chart_legend, LEGENDS, layout.legend) { set("legend", it) }
            layout.horizontalTitle?.let { current -> choice(Resources.string.editors_chart_axis_horizontal, HORIZONTAL_TITLES, current) { set("horizontalTitle", it) } }
            layout.verticalTitle?.let { current -> choice(Resources.string.editors_chart_axis_vertical, VERTICAL_TITLES, current) { set("verticalTitle", it) } }
            layout.horizontalGridlines?.let { current -> choice(Resources.string.editors_chart_gridlines_horizontal, GRIDLINES, current) { set("horizontalGridlines", it) } }
            layout.verticalGridlines?.let { current -> choice(Resources.string.editors_chart_gridlines_vertical, GRIDLINES, current) { set("verticalGridlines", it) } }
            choice(Resources.string.editors_chart_labels, dataLabels(chart.type), layout.dataLabels) { set("dataLabels", it) }
        }
        panels.show(context.getString(Resources.string.editors_chart_layout), content) {}
    }

    /** The value axis' settings ([value]), or the category axis'. */
    fun showAxis(chart: ChartState, value: Boolean) {
        val axis = (if (value) chart.valueAxis else chart.categoryAxis) ?: return
        fun set(name: String, v: Any?) = edit { setChartAxis(value, mapOf(name to v)) }
        val content = column {
            if (value) {
                number(Resources.string.editors_chart_minimum, axis.min) { set("min", it) }
                number(Resources.string.editors_chart_maximum, axis.max) { set("max", it) }
                axis.units?.let { current -> choice(Resources.string.editors_chart_units, UNITS, current) { set("units", it) } }
            }
            crosses(axis) { name, v -> set(name, v) }
            if (!value) axis.position?.let { current -> choice(Resources.string.editors_chart_axis_position, POSITIONS, current) { set("position", it) } }
            choice(Resources.string.editors_chart_ticks_major, TICKS, axis.majorTicks) { set("majorTicks", it) }
            choice(Resources.string.editors_chart_ticks_minor, TICKS, axis.minorTicks) { set("minorTicks", it) }
            choice(Resources.string.editors_chart_axis_labels, LABELS, axis.labels) { set("labels", it) }
            addView(CheckBox(context).apply {
                setText(if (value) Resources.string.editors_chart_reverse_values else Resources.string.editors_chart_reverse_categories)
                isChecked = axis.reverse
                minHeight = dp(48)
                setOnCheckedChangeListener { _, checked -> set("reverse", checked) }
            })
        }
        panels.show(context.getString(axisName(chart, value)), content) {}
    }

    /** The name of the value axis ([value]) or the category axis: vertical, horizontal for horizontal bars. */
    fun axisName(chart: ChartState, value: Boolean): Int {
        val horizontalBars = chart.type in 16..21
        return if (value != horizontalBars) Resources.string.editors_chart_axis_vertical_settings else Resources.string.editors_chart_axis_horizontal_settings
    }

    // Where the other axis crosses: automatic, at the minimum or maximum, or at a value
    private fun LinearLayout.crosses(axis: ChartAxis, set: (String, Any?) -> Unit) {
        lateinit var valueText: TextView
        choice(Resources.string.editors_chart_crosses, CROSSES, axis.crosses) { rule ->
            if (rule == CROSSES_AT_VALUE) {
                askNumber(Resources.string.editors_chart_crosses, axis.crossesValue ?: 0.0, allowAuto = false) { v ->
                    valueText.text = format(v)
                    set("crossesValue", v)
                }
            } else {
                set("crosses", rule)
            }
        }.also { valueText = it }
        if (axis.crosses == CROSSES_AT_VALUE) valueText.text = format(axis.crossesValue)
    }

    // ---------------------------------------------------------------------------------
    // Rows: a setting's name and its value; a tap chooses another

    private fun column(build: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(4), dp(16), dp(24))
        build()
    }

    // Returns the view showing the value
    private fun LinearLayout.row(name: Int, value: String, onClick: (TextView) -> Unit): TextView {
        val valueView = TextView(context).apply {
            text = value
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, Resources.color.editors_icon))
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPaddingRelative(dp(4), 0, dp(4), 0)
            setBackgroundResource(R.drawable.editor_tool_background)
            addView(TextView(context).apply {
                setText(name)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueView)
            setOnClickListener { onClick(valueView) }
        })
        return valueView
    }

    private fun LinearLayout.choice(name: Int, options: List<Pair<Int, Int>>, current: Int, onChoose: (Int) -> Unit): TextView {
        val label = { v: Int -> options.firstOrNull { it.second == v }?.let { context.getString(it.first) } ?: "" }
        var selected = current
        return row(name, label(current)) { valueView ->
            AlertDialog.Builder(context)
                .setTitle(name)
                .setSingleChoiceItems(options.map { context.getString(it.first) }.toTypedArray(), options.indexOfFirst { it.second == selected }) { dialog, index ->
                    dialog.dismiss()
                    selected = options[index].second
                    valueView.text = label(selected)
                    onChoose(selected)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // A number; empty for automatic
    private fun LinearLayout.number(name: Int, current: Double?, onValue: (Double?) -> Unit) {
        var value = current
        row(name, format(current)) { valueView ->
            askNumber(name, value, allowAuto = true) {
                value = it
                valueView.text = format(it)
                onValue(it)
            }
        }
    }

    private fun askNumber(title: Int, current: Double?, allowAuto: Boolean, onValue: (Double?) -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(current?.let { format(it) }.orEmpty())
            if (allowAuto) hint = context.getString(Resources.string.editors_chart_auto)
            isSingleLine = true
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(FrameLayout(context).apply {
                setPaddingRelative(dp(24), dp(8), dp(24), 0)
                addView(input)
            })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim().replace(',', '.')
                val number = text.toDoubleOrNull()
                when {
                    text.isEmpty() && allowAuto -> onValue(null)
                    number != null -> onValue(number)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun format(value: Double?): String = when {
        value == null -> context.getString(Resources.string.editors_chart_auto)
        value == Math.floor(value) && kotlin.math.abs(value) < 1e15 -> value.toLong().toString()
        else -> value.toString()
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val CROSSES_AT_VALUE = 2

        // sdkjs' values (see ChartLayout, ChartAxis)
        val TITLES = listOf(
            Resources.string.editors_chart_none to 0,
            Resources.string.editors_chart_overlay to 1,
            Resources.string.editors_chart_no_overlay to 2
        )
        val LEGENDS = listOf(
            Resources.string.editors_chart_none to 0,
            Resources.string.editors_chart_legend_left to 1,
            Resources.string.editors_chart_legend_top to 2,
            Resources.string.editors_chart_legend_right to 3,
            Resources.string.editors_chart_legend_bottom to 4,
            Resources.string.editors_chart_left_overlay to 5,
            Resources.string.editors_chart_right_overlay to 6
        )
        val HORIZONTAL_TITLES = listOf(
            Resources.string.editors_chart_none to 0,
            Resources.string.editors_chart_no_overlay to 1
        )
        val VERTICAL_TITLES = listOf(
            Resources.string.editors_chart_none to 0,
            Resources.string.editors_chart_rotated to 1,
            Resources.string.editors_chart_horizontal to 3
        )
        val GRIDLINES = listOf(
            Resources.string.editors_chart_none to 0,
            Resources.string.editors_chart_major to 1,
            Resources.string.editors_chart_minor to 2,
            Resources.string.editors_chart_major_minor to 3
        )
        val UNITS = listOf(
            Resources.string.editors_chart_none to 0,
            Resources.string.editors_chart_hundreds to 3,
            Resources.string.editors_chart_thousands to 10,
            Resources.string.editors_chart_ten_thousands to 7,
            Resources.string.editors_chart_hundred_thousands to 4,
            Resources.string.editors_chart_millions to 5,
            Resources.string.editors_chart_ten_millions to 6,
            Resources.string.editors_chart_hundred_millions to 2,
            Resources.string.editors_chart_billions to 1,
            Resources.string.editors_chart_trillions to 8
        )
        val CROSSES = listOf(
            Resources.string.editors_chart_auto to 0,
            Resources.string.editors_chart_crosses_value to CROSSES_AT_VALUE,
            Resources.string.editors_chart_crosses_minimum to 3,
            Resources.string.editors_chart_crosses_maximum to 1
        )
        val TICKS = listOf(
            Resources.string.editors_chart_none to 2,
            Resources.string.editors_chart_ticks_cross to 0,
            Resources.string.editors_chart_ticks_in to 1,
            Resources.string.editors_chart_ticks_out to 3
        )
        val LABELS = listOf(
            Resources.string.editors_chart_none to 3,
            Resources.string.editors_chart_labels_low to 1,
            Resources.string.editors_chart_labels_high to 0,
            Resources.string.editors_chart_labels_next to 2
        )
        val POSITIONS = listOf(
            Resources.string.editors_chart_on_ticks to 0,
            Resources.string.editors_chart_between_ticks to 1
        )

        // The value labels' positions the chart type has, as web-apps offers them
        fun dataLabels(type: Int): List<Pair<Int, Int>> {
            val none = Resources.string.editors_chart_none to 0
            val center = Resources.string.editors_chart_center to 3
            return when (type) {
                0, 16 -> listOf(none, center, Resources.string.editors_chart_inner_bottom to 4, Resources.string.editors_chart_inner_top to 5,
                    Resources.string.editors_chart_outer_top to 7)
                1, 2, 17, 18 -> listOf(none, center, Resources.string.editors_chart_inner_bottom to 4, Resources.string.editors_chart_inner_top to 5)
                in 7..12, in 27..33 -> listOf(none, center, Resources.string.editors_chart_legend_left to 6, Resources.string.editors_chart_legend_right to 8,
                    Resources.string.editors_chart_legend_top to 9, Resources.string.editors_chart_legend_bottom to 1)
                14, 15 -> listOf(none, center, Resources.string.editors_chart_best_fit to 2, Resources.string.editors_chart_inner_top to 5,
                    Resources.string.editors_chart_outer_top to 7)
                else -> listOf(none, center)
            }
        }
    }
}
