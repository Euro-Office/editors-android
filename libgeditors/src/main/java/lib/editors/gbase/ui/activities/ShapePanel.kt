package lib.editors.gbase.ui.activities

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
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
    /** The text's settings (of the selected shapes' text). */
    private val formatText: () -> Unit = {},
    /** Grouping and ungrouping (not in PDFs). */
    private val withGroups: Boolean = true
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
            val content = content(current, colors ?: DrawingColors(null, null)) { show(chart) }
            val title = if (current != null) Resources.string.editors_chart_insert else Resources.string.editors_insert_shape
            panels.show(context.getString(title), content) {}
        }
    }

    private fun content(chart: ChartState?, colors: DrawingColors, back: () -> Unit): View = LinearLayout(context).apply {
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
            action(context.getString(Resources.string.editors_shape_text)) { formatText() }
        }

        heading(Resources.string.editors_shape_fill)
        colors(FILLS, colors.fill) { color -> edit { setShapeFill(color) } }
        heading(Resources.string.editors_shape_outline)
        colors(FILLS, colors.outline) { color -> edit { setShapeOutline(color) } }

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

    private fun LinearLayout.button(text: Int, onClick: () -> Unit) {
        addView(TextView(context).apply {
            setText(text)
            gravity = Gravity.CENTER
            textSize = 15f
            minHeight = dp(48)
            setPaddingRelative(dp(12), 0, dp(12), 0)
            setBackgroundResource(R.drawable.editor_tool_background)
            setOnClickListener { onClick() }
        })
    }

    // A row of color swatches, [current] marked; "" is none. Tapping one marks it.
    private fun LinearLayout.colors(colors: List<String>, current: String?, onColor: (String?) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val swatches = mutableListOf<Pair<String, View>>()
        fun mark(selected: String?) = swatches.forEach { (color, swatch) ->
            swatch.isSelected = selected != null && selected.equals(color, ignoreCase = true)
            swatch.background = InsetDrawable(swatchDrawable(color, swatch.isSelected), dp(6))
        }
        for (color in colors) {
            val swatch = View(context).apply {
                // 48 dp to touch, 36 dp to see
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                contentDescription = Accessibility.colorName(context, color)
                setOnClickListener {
                    mark(color)
                    onColor(color.ifEmpty { null })
                }
            }
            swatches += color to swatch
            row.addView(swatch)
        }
        mark(current)
        addView(HorizontalScrollView(context).apply { addView(row) })
    }

    // "None" is crossed out
    private fun swatchDrawable(color: String, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (color.isEmpty()) Color.TRANSPARENT else Color.parseColor("#$color"))
        val border = ContextCompat.getColor(context, if (selected) Resources.color.editors_selection_handle else Resources.color.editors_sheet_header_line)
        setStroke(if (selected) dp(3) else dp(1), border)
    }.let { circle ->
        if (color.isNotEmpty()) circle else LayerDrawable(arrayOf(circle, NoneMark(ContextCompat.getColor(context, Resources.color.editors_icon), density)))
    }

    // A diagonal line across the swatch
    private class NoneMark(color: Int, density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 1.5f * density
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val inset = b.width() * 0.2f
            canvas.drawLine(b.right - inset, b.top + inset, b.left + inset, b.bottom - inset, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        // "" is none
        val FILLS = listOf("", "FFFFFF", "000000", "808080", "C00000", "FF0000", "FFC000", "FFFF00", "00B050", "0070C0", "002060", "7030A0")
    }
}
