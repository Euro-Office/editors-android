package lib.editors.gbase.ui.activities

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.ChartState
import lib.editors.base.engine.DrawingColors
import lib.editors.base.engine.DrawingOrder
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.ShapeTextDirection
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * The selected pictures, shapes and charts, in a panel (see [PanelHost]): their fill and outline,
 * the current ones marked; a chart's type, titles, elements and data; their text's settings;
 * arranging and grouping.
 */
internal class ShapePanel(
    private val context: Context,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val chartUi: ChartUi,
    private val chartSettings: ChartSettingsPanel,
    private val panels: PanelHost,
    private val colorPicker: ColorPicker,
    /** The settings of the selected shapes' text, back to this panel. */
    private val formatText: (onBack: () -> Unit) -> Unit = {},
    /** Grouping and ungrouping (not in PDFs). */
    private val withGroups: Boolean = true,
    /** All kinds of fills (see [FillPanel]), back to this panel; null without them. */
    private val chooseFill: ((onBack: () -> Unit) -> Unit)? = null
) {

    private val density = context.resources.displayMetrics.density

    /**
     * Shows the panel for the selected chart ([chart], read after the changes so far) or other
     * drawings. The chart's settings open in the panel's place and come back to it.
     */
    fun show(chart: () -> ChartState?) {
        val document = document() ?: return
        scope.launch {
            // After the commands before it, the chart's state as they left it
            val colors = withContext(EditorEngine.dispatcher) { runCatching { document.drawingColors() }.getOrNull() }
            val current = chart()
            val rows = mutableListOf<ColorPicker.Row>()
            val content = content(current, colors ?: DrawingColors(null, null), rows) { show(chart) }
            val title = if (current != null) Resources.string.editors_chart_insert else Resources.string.editors_insert_shape
            panels.show(context.getString(title), content) {}
            // The theme's colors with the current color scheme
            colorPicker.load { loaded -> rows.forEach { it.setColors(loaded) } }
        }
    }

    private fun content(chart: ChartState?, colors: DrawingColors, rows: MutableList<ColorPicker.Row>, back: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(8), dp(16), dp(24))

        // Charts' own settings first (the panel's title says it is a chart)
        if (chart != null) {
            val type = InsertCatalog.CHARTS.flatMap { it.items }.firstOrNull { it.value == chart.type }
            action(context.getString(Resources.string.editors_chart_type), type?.let { context.getString(it.label) }, type?.icon) {
                chartUi.chooseType(Resources.string.editors_chart_type, chart.type, onBack = back) { edit { setChartType(it) } }
            }
            if (chart.styles > 0) {
                action(context.getString(Resources.string.editors_chart_style), chart.style?.toString()) {
                    chartSettings.chooseStyle(chart, back)
                }
            }
            action(context.getString(Resources.string.editors_chart_titles), chart.titleText.ifEmpty { null }) {
                chartUi.showTitles(chart, back)
            }
            if (chart.layout != null) {
                action(context.getString(Resources.string.editors_chart_layout)) {
                    chartSettings.showLayout(chart, back)
                }
            }
            // The vertical axis first
            listOf(true, false).sortedBy { chartSettings.axisName(chart, it) != Resources.string.editors_chart_axis_vertical_settings }
                .filter { if (it) chart.valueAxis != null else chart.categoryAxis != null }
                .forEach { value ->
                    action(context.getString(chartSettings.axisName(chart, value))) {
                        chartSettings.showAxis(chart, value, back)
                    }
                }
            action(context.getString(Resources.string.editors_chart_data), chart.range.removePrefix("=").ifEmpty { null }) {
                if (chart.dataInCells) chartUi.editRange(chart, back) else chartUi.editData(back)
            }
        }

        // The shapes' text: font, paragraph, ... (charts have their titles)
        if (chart == null) {
            action(context.getString(Resources.string.editors_shape_text)) { formatText(back) }
        }

        // The current ones marked; tapping one marks it
        heading(Resources.string.editors_shape_fill)
        rows += colorPicker.Row(Resources.string.editors_shape_fill, Resources.string.editors_color_none, back) { color -> edit { setShapeFill(color) } }
            .also { addView(it.view); it.mark(colors.fill) }
        if (chooseFill != null) action(context.getString(Resources.string.editors_fill_more)) { chooseFill.invoke(back) }
        heading(Resources.string.editors_shape_outline)
        rows += colorPicker.Row(Resources.string.editors_shape_outline, Resources.string.editors_color_none, back) { color -> edit { setShapeOutline(color) } }
            .also { addView(it.view); it.mark(colors.outline) }

        // The shapes' text: horizontal or rotated, the current one marked
        val direction = colors.textDirection
        if (chart == null && direction != null) {
            heading(Resources.string.editors_text_orientation)
            addView(HorizontalScrollView(context).apply {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val buttons = mutableListOf<TextView>()
                    TEXT_DIRECTIONS.forEachIndexed { index, (label, value) ->
                        buttons += button(label) {
                            buttons.forEachIndexed { i, button -> button.isSelected = i == index }
                            edit { setTextDirection(value) }
                        }.also {
                            Accessibility.toggle(it)
                            it.isSelected = value == direction
                        }
                    }
                })
            })
        }

        heading(Resources.string.editors_shape_arrange)
        addView(HorizontalScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                button(Resources.string.editors_shape_front) { edit { arrange(DrawingOrder.TO_FRONT) } }
                button(Resources.string.editors_shape_back) { edit { arrange(DrawingOrder.TO_BACK) } }
                if (withGroups) {
                    button(Resources.string.editors_shape_group) { edit { group(true) } }
                    button(Resources.string.editors_shape_ungroup) { edit { group(false) } }
                }
            })
        })
    }

    private fun LinearLayout.heading(text: Int) {
        addView(TextView(context).apply {
            setText(text)
            setTextColor(ContextCompat.getColor(context, Resources.color.editors_accent))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            setPaddingRelative(0, dp(16), 0, dp(4))
        })
    }

    // A row that opens more settings: its name, what is set now and its picture
    private fun LinearLayout.action(name: String, value: String? = null, icon: Int? = null, onClick: () -> Unit) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPaddingRelative(dp(4), 0, dp(4), 0)
            setBackgroundResource(R.drawable.editor_tool_background)
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = name
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (value != null) {
                addView(TextView(context).apply {
                    text = value
                    textSize = 14f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(180)
                    setTextColor(ContextCompat.getColor(context, Resources.color.editors_icon))
                })
            }
            if (icon != null) {
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, Resources.color.editors_icon))
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) }
                })
            }
            addView(ImageView(context).apply {
                setImageResource(Resources.drawable.ic_editor_chevron_right)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(4) }
            })
        })
    }

    private fun LinearLayout.button(text: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            setText(text)
            gravity = Gravity.CENTER
            textSize = 15f
            minHeight = dp(48)
            setPaddingRelative(dp(12), 0, dp(12), 0)
            setBackgroundResource(R.drawable.editor_tool_background)
            setOnClickListener { onClick() }
        }.also { addView(it) }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        val TEXT_DIRECTIONS = listOf(
            Resources.string.editors_text_horizontal to ShapeTextDirection.HORIZONTAL,
            Resources.string.editors_text_rotate_up to ShapeTextDirection.ROTATED_UP,
            Resources.string.editors_text_rotate_down to ShapeTextDirection.ROTATED_DOWN
        )
    }
}
