package lib.editors.gbase.ui.activities

import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import lib.editors.base.engine.ChartAxis
import lib.editors.base.engine.ChartLayout
import lib.editors.base.engine.ChartState
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * A chart's style (pictures of the type in its styles), layout (title, legend, axes' titles,
 * gridlines, value labels) and axes (minimum, maximum, units, where the other axis crosses, tick
 * marks, labels), in panels (see [PanelHost]) as web-apps' mobile chart settings, with a back
 * button to the chart's menu. A setting is applied as it is chosen; a list of choices opens in
 * the panel's place and goes back to it.
 */
internal class ChartSettingsPanel(
    private val context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val panels: PanelHost,
    private val previews: PreviewPicker
) {

    private val density = context.resources.displayMetrics.density

    fun chooseStyle(chart: ChartState, onBack: () -> Unit) =
        previews.show(
            context.getString(Resources.string.editors_chart_style), "chartStyles", chart.style?.toString(), columnWidthDp = 88,
            keepOpen = true, onBack = onBack
        ) {
            it.id.toIntOrNull()?.let { style -> edit { setChartStyle(style) } }
        }

    fun showLayout(chart: ChartState, onBack: () -> Unit) {
        var layout = chart.layout ?: return
        fun open() {
            fun set(name: String, value: Int, changed: ChartLayout) {
                layout = changed
                edit { setChartLayout(mapOf(name to value)) }
            }
            val content = column {
                choice(Resources.string.editors_chart_title, TITLES, layout.title, ::open) { set("title", it, layout.copy(title = it)) }
                choice(Resources.string.editors_chart_legend, LEGENDS, layout.legend, ::open) { set("legend", it, layout.copy(legend = it)) }
                layout.horizontalTitle?.let { current ->
                    choice(Resources.string.editors_chart_axis_horizontal, HORIZONTAL_TITLES, current, ::open) {
                        set("horizontalTitle", it, layout.copy(horizontalTitle = it))
                    }
                }
                layout.verticalTitle?.let { current ->
                    choice(Resources.string.editors_chart_axis_vertical, VERTICAL_TITLES, current, ::open) {
                        set("verticalTitle", it, layout.copy(verticalTitle = it))
                    }
                }
                layout.horizontalGridlines?.let { current ->
                    choice(Resources.string.editors_chart_gridlines_horizontal, GRIDLINES, current, ::open) {
                        set("horizontalGridlines", it, layout.copy(horizontalGridlines = it))
                    }
                }
                layout.verticalGridlines?.let { current ->
                    choice(Resources.string.editors_chart_gridlines_vertical, GRIDLINES, current, ::open) {
                        set("verticalGridlines", it, layout.copy(verticalGridlines = it))
                    }
                }
                choice(Resources.string.editors_chart_labels, dataLabels(chart.type), layout.dataLabels, ::open) {
                    set("dataLabels", it, layout.copy(dataLabels = it))
                }
            }
            panels.show(context.getString(Resources.string.editors_chart_layout), content, onBack) {}
        }
        open()
    }

    /** The value axis' settings ([value]), or the category axis'. */
    fun showAxis(chart: ChartState, value: Boolean, onBack: () -> Unit) {
        var axis = (if (value) chart.valueAxis else chart.categoryAxis) ?: return
        fun open() {
            fun set(name: String, v: Any?, changed: ChartAxis) {
                axis = changed
                edit { setChartAxis(value, mapOf(name to v)) }
            }
            val content = column {
                if (value) {
                    number(Resources.string.editors_chart_minimum, axis.min, allowAuto = true) { set("min", it, axis.copy(min = it)) }
                    number(Resources.string.editors_chart_maximum, axis.max, allowAuto = true) { set("max", it, axis.copy(max = it)) }
                    axis.units?.let { current -> choice(Resources.string.editors_chart_units, UNITS, current, ::open) { set("units", it, axis.copy(units = it)) } }
                }
                // Where the other axis crosses: automatic, at the minimum or maximum, or at a value
                choice(Resources.string.editors_chart_crosses, CROSSES, axis.crosses, ::open) { rule ->
                    if (rule == CROSSES_AT_VALUE) {
                        val at = axis.crossesValue ?: 0.0
                        set("crossesValue", at, axis.copy(crosses = rule, crossesValue = at))
                    } else {
                        set("crosses", rule, axis.copy(crosses = rule))
                    }
                }
                if (axis.crosses == CROSSES_AT_VALUE) {
                    number(Resources.string.editors_chart_crossing_value, axis.crossesValue, allowAuto = false) {
                        set("crossesValue", it, axis.copy(crossesValue = it))
                    }
                }
                if (!value) axis.position?.let { current ->
                    choice(Resources.string.editors_chart_axis_position, POSITIONS, current, ::open) { set("position", it, axis.copy(position = it)) }
                }
                choice(Resources.string.editors_chart_ticks_major, TICKS, axis.majorTicks, ::open) { set("majorTicks", it, axis.copy(majorTicks = it)) }
                choice(Resources.string.editors_chart_ticks_minor, TICKS, axis.minorTicks, ::open) { set("minorTicks", it, axis.copy(minorTicks = it)) }
                choice(Resources.string.editors_chart_axis_labels, LABELS, axis.labels, ::open) { set("labels", it, axis.copy(labels = it)) }
                addView(CheckBox(context).apply {
                    setText(if (value) Resources.string.editors_chart_reverse_values else Resources.string.editors_chart_reverse_categories)
                    isChecked = axis.reverse
                    minHeight = dp(48)
                    setOnCheckedChangeListener { _, checked -> set("reverse", checked, axis.copy(reverse = checked)) }
                })
            }
            panels.show(context.getString(axisName(chart, value)), content, onBack) {}
        }
        open()
    }

    /** The name of the value axis ([value]) or the category axis: vertical, horizontal for horizontal bars. */
    fun axisName(chart: ChartState, value: Boolean): Int {
        val horizontalBars = chart.type in 16..21
        return if (value != horizontalBars) Resources.string.editors_chart_axis_vertical_settings else Resources.string.editors_chart_axis_horizontal_settings
    }

    // ---------------------------------------------------------------------------------
    // Rows: a setting's name and its value

    private fun column(build: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(4), dp(16), dp(24))
        build()
    }

    private fun LinearLayout.row(name: Int, build: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPaddingRelative(dp(4), 0, dp(4), 0)
        addView(TextView(context).apply {
            setText(name)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        build()
    }.also { addView(it) }

    // The chosen one's name; a tap lists the others in the panel's place, then [reopen]s it
    private fun LinearLayout.choice(name: Int, options: List<Pair<Int, Int>>, current: Int, reopen: () -> Unit, onChoose: (Int) -> Unit) {
        val labels = options.map { context.getString(it.first) }
        val selected = options.indexOfFirst { it.second == current }
        row(name) {
            setBackgroundResource(R.drawable.editor_tool_background)
            addView(TextView(context).apply {
                text = labels.getOrElse(selected) { "" }
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, Resources.color.editors_icon))
            })
            addView(ImageView(context).apply { setImageResource(Resources.drawable.ic_editor_chevron_right) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            setOnClickListener {
                panels.choose(context.getString(name), labels, selected, onBack = reopen) { index ->
                    if (index != selected) onChoose(options[index].second)
                }
            }
        }
    }

    // A number, typed in place; empty for automatic if [allowAuto]. Applied when typing ends.
    private fun LinearLayout.number(name: Int, current: Double?, allowAuto: Boolean, onValue: (Double?) -> Unit) {
        var value = current
        row(name) {
            val input = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                imeOptions = EditorInfo.IME_ACTION_DONE
                setText(current?.let { format(it) }.orEmpty())
                if (allowAuto) hint = context.getString(Resources.string.editors_chart_auto)
                isSingleLine = true
                gravity = Gravity.END
                contentDescription = context.getString(name)
            }
            fun apply() {
                val text = input.text.toString().trim().replace(',', '.')
                val typed = if (text.isEmpty()) null else text.toDoubleOrNull() ?: return
                if (typed == null && !allowAuto || typed == value) return
                value = typed
                onValue(typed)
            }
            input.setOnEditorActionListener { _, _, _ ->
                apply()
                false
            }
            input.setOnFocusChangeListener { _, focused -> if (!focused) apply() }
            addView(input, LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
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
