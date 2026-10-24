package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import lib.editors.base.engine.OutlineDash
import lib.editors.base.engine.ShapeTextDirection
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * The selected pictures, shapes and charts, as the first section of the format panels
 * ([FormatPanel], [CellFormatPanel]): a chart's type, style, titles, elements and data; their
 * fill and outline, the current ones marked; their text's direction; arranging and grouping.
 * The format panel shows their text's settings below.
 */
internal class ShapePanel(
    context: Context,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val chartUi: ChartUi,
    private val chartSettings: ChartSettingsPanel,
    private val colorPicker: ColorPicker,
    /** Where the dashes to choose from open. */
    private val panels: PanelHost,
    /** The selected chart (null for other drawings), after the commands so far. */
    private val chart: () -> ChartState?,
    /** Grouping and ungrouping (not in PDFs). */
    private val withGroups: Boolean = true,
    /** All kinds of fills (see [FillPanel]), back to the format panel; null without them. */
    private val chooseFill: ((onBack: () -> Unit) -> Unit)? = null
) : PanelBuilder<DrawingColors>(context) {

    /** The selected drawings: a [chart] or others with [colors]. */
    class Selected(val chart: ChartState?, val colors: DrawingColors) {
        /** Shapes have text (pictures and charts don't). */
        val hasText get() = chart == null && colors.textDirection != null

        /** The section's title: chart, shape or picture. */
        val title get() = when {
            chart != null -> Resources.string.editors_chart_insert
            hasText -> Resources.string.editors_shape
            else -> Resources.string.editors_insert_picture
        }
    }

    /** Reads the selected drawings (on the engine's thread), after the commands before. */
    fun load(onLoaded: (Selected) -> Unit) {
        val document = document() ?: return
        scope.launch {
            val colors = withContext(EditorEngine.dispatcher) { runCatching { document.drawingColors() }.getOrNull() }
            onLoaded(Selected(chart(), colors ?: DrawingColors(null, null)))
        }
    }

    /**
     * Adds the section for [selected] to [container] (under the format panel's heading, see
     * [Selected.title]); the settings opened from it go [back] to the format panel. Its color
     * rows go to [rows] (for the theme's colors).
     */
    fun addTo(container: LinearLayout, selected: Selected, back: () -> Unit, rows: MutableList<ColorPicker.Row>) {
        updaters.clear()
        var colors = selected.colors
        val chart = selected.chart
        container.apply {
            // Charts' own settings first
            if (chart != null) {
                val type = InsertCatalog.CHARTS.flatMap { it.items }.firstOrNull { it.value == chart.type }
                action(Resources.string.editors_chart_type, { type?.let { context.getString(it.label) }.orEmpty() }, type?.icon) {
                    chartUi.chooseType(Resources.string.editors_chart_type, chart.type, onBack = back) { edit { setChartType(it) } }
                }
                if (chart.styles > 0) {
                    action(Resources.string.editors_chart_style, { chart.style?.toString().orEmpty() }) { chartSettings.chooseStyle(chart, back) }
                }
                action(Resources.string.editors_chart_titles, { chart.titleText }) { chartUi.showTitles(chart, back) }
                if (chart.layout != null) action(Resources.string.editors_chart_layout) { chartSettings.showLayout(chart, back) }
                // The vertical axis first
                listOf(true, false).sortedBy { chartSettings.axisName(chart, it) != Resources.string.editors_chart_axis_vertical_settings }
                    .filter { if (it) chart.valueAxis != null else chart.categoryAxis != null }
                    .forEach { value -> action(chartSettings.axisName(chart, value)) { chartSettings.showAxis(chart, value, back) } }
                action(Resources.string.editors_chart_data, { chart.range.removePrefix("=") }) {
                    if (chart.dataInCells) chartUi.editRange(chart, back) else chartUi.editData(back)
                }
            }

            // The current ones marked; tapping one marks it
            caption(Resources.string.editors_shape_fill)
            rows += colorPicker.Row(Resources.string.editors_shape_fill, Resources.string.editors_color_none, back) { color -> edit { setShapeFill(color) } }
                .also { addView(it.view); it.mark(colors.fill) }
            if (chooseFill != null) action(Resources.string.editors_fill_more) { chooseFill.invoke(back) }
            // The outline: its color ("none" without one), size and dash; each keeps the others
            fun outline(color: String?, widthMm: Double, dash: OutlineDash) {
                colors = colors.copy(outline = color, outlineWidthMm = widthMm, outlineDash = dash)
                updaters.forEach { it(colors) }
                edit { setShapeOutline(color, widthMm, dash) }
            }
            fun width() = colors.outlineWidthMm?.takeIf { it > 0 } ?: DEFAULT_OUTLINE_POINTS * MM_PER_POINT
            caption(Resources.string.editors_shape_outline)
            val outlineRow = colorPicker.Row(Resources.string.editors_shape_outline, Resources.string.editors_color_none, back) { color ->
                outline(color, width(), colors.outlineDash)
            }.also { addView(it.view); it.mark(colors.outline) }
            rows += outlineRow
            caption(Resources.string.editors_shape_outline_size)
            stepper(Resources.string.editors_shape_outline_size, { context.getString(Resources.string.editors_format_points_value, trimPoints(width() / MM_PER_POINT)) }, {
                val points = OUTLINE_POINTS.lastOrNull { it < width() / MM_PER_POINT - 0.01 } ?: OUTLINE_POINTS.first()
                outline(colors.outline?.ifEmpty { null } ?: DEFAULT_OUTLINE_COLOR, points * MM_PER_POINT, colors.outlineDash)
                outlineRow.mark(colors.outline)
            }, {
                val points = OUTLINE_POINTS.firstOrNull { it > width() / MM_PER_POINT + 0.01 } ?: OUTLINE_POINTS.last()
                outline(colors.outline?.ifEmpty { null } ?: DEFAULT_OUTLINE_COLOR, points * MM_PER_POINT, colors.outlineDash)
                outlineRow.mark(colors.outline)
            })
            action(Resources.string.editors_shape_border_type, { context.getString(DASHES.getValue(it.outlineDash)) }) {
                chooseDash(colors.outlineDash, back) { dash ->
                    outline(colors.outline?.ifEmpty { null } ?: DEFAULT_OUTLINE_COLOR, width(), dash)
                }
            }

            // The shapes' text: horizontal or rotated
            if (selected.hasText) {
                caption(Resources.string.editors_text_orientation)
                scrollRow {
                    for ((label, value) in TEXT_DIRECTIONS) {
                        choice(context.getString(label), { it.textDirection == value }) {
                            colors = colors.copy(textDirection = value)
                            updaters.forEach { it(colors) }
                            edit { setTextDirection(value) }
                        }
                    }
                }
            }

            caption(Resources.string.editors_shape_arrange)
            scrollRow {
                button(context.getString(Resources.string.editors_shape_front)) { edit { arrange(DrawingOrder.TO_FRONT) } }
                button(context.getString(Resources.string.editors_shape_back)) { edit { arrange(DrawingOrder.TO_BACK) } }
                if (withGroups) {
                    button(context.getString(Resources.string.editors_shape_group)) { edit { group(true) } }
                    button(context.getString(Resources.string.editors_shape_ungroup)) { edit { group(false) } }
                }
            }
        }
        updaters.forEach { it(colors) }
    }

    // The dashes by pictures of them, the current one marked; a tap takes one and goes [back]
    private fun chooseDash(current: OutlineDash, back: () -> Unit, onDash: (OutlineDash) -> Unit) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), dp(24))
            for ((dash, name) in DASHES) {
                addView(DashView(context, dash).apply {
                    contentDescription = context.getString(name)
                    isSelected = dash == current
                    Accessibility.toggle(this)
                    setBackgroundResource(R.drawable.editor_tool_background)
                    setOnClickListener {
                        onDash(dash)
                        back()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            }
        }
        panels.show(context.getString(Resources.string.editors_shape_border_type), list, back) {}
    }

    // A dash as a line across the view, its pattern in multiples of the line's width (OOXML's)
    private class DashView(context: Context, dash: OutlineDash) : View(context) {
        private val lineWidth = 3 * context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            color = ContextCompat.getColor(context, Resources.color.editors_icon)
            DASH_PATTERNS[dash]?.let { pattern -> pathEffect = DashPathEffect(pattern.map { it * lineWidth }.toFloatArray(), 0f) }
        }

        override fun onDraw(canvas: Canvas) {
            val margin = 16 * resources.displayMetrics.density
            canvas.drawLine(margin, height / 2f, width - margin, height / 2f, paint)
        }
    }

    private fun trimPoints(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')

    private companion object {
        const val MM_PER_POINT = 25.4 / 72
        const val DEFAULT_OUTLINE_POINTS = 0.75
        const val DEFAULT_OUTLINE_COLOR = "000000"
        // The sizes web-apps offers, and a few in between
        val OUTLINE_POINTS = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.25, 3.0, 4.5, 6.0, 8.0, 10.0, 12.0)
        val DASHES = linkedMapOf(
            OutlineDash.SOLID to Resources.string.editors_dash_solid,
            OutlineDash.SYS_DOT to Resources.string.editors_dash_round_dot,
            OutlineDash.SYS_DASH to Resources.string.editors_dash_square_dot,
            OutlineDash.DASH to Resources.string.editors_dash_dash,
            OutlineDash.DASH_DOT to Resources.string.editors_dash_dash_dot,
            OutlineDash.LONG_DASH to Resources.string.editors_dash_long_dash,
            OutlineDash.LONG_DASH_DOT to Resources.string.editors_dash_long_dash_dot,
            OutlineDash.LONG_DASH_DOT_DOT to Resources.string.editors_dash_long_dash_dot_dot
        )
        val DASH_PATTERNS = mapOf(
            OutlineDash.SYS_DOT to listOf(1f, 1f),
            OutlineDash.SYS_DASH to listOf(3f, 1f),
            OutlineDash.DASH to listOf(4f, 3f),
            OutlineDash.DASH_DOT to listOf(4f, 3f, 1f, 3f),
            OutlineDash.LONG_DASH to listOf(8f, 3f),
            OutlineDash.LONG_DASH_DOT to listOf(8f, 3f, 1f, 3f),
            OutlineDash.LONG_DASH_DOT_DOT to listOf(8f, 3f, 1f, 3f, 1f, 3f)
        )
        val TEXT_DIRECTIONS = listOf(
            Resources.string.editors_text_horizontal to ShapeTextDirection.HORIZONTAL,
            Resources.string.editors_text_rotate_up to ShapeTextDirection.ROTATED_UP,
            Resources.string.editors_text_rotate_down to ShapeTextDirection.ROTATED_DOWN
        )
    }
}
