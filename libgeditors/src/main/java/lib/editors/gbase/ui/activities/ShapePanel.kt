package lib.editors.gbase.ui.activities

import android.content.Context
import android.widget.LinearLayout
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
     * Adds the section for [selected] to [container]; the settings opened from it go [back] to
     * the format panel. Its color rows go to [rows] (for the theme's colors).
     */
    fun addTo(container: LinearLayout, selected: Selected, back: () -> Unit, rows: MutableList<ColorPicker.Row>) {
        updaters.clear()
        var colors = selected.colors
        val chart = selected.chart
        container.apply {
            heading(if (chart != null) Resources.string.editors_chart_insert else Resources.string.editors_shape)

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
            caption(Resources.string.editors_shape_outline)
            rows += colorPicker.Row(Resources.string.editors_shape_outline, Resources.string.editors_color_none, back) { color -> edit { setShapeOutline(color) } }
                .also { addView(it.view); it.mark(colors.outline) }

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

    private companion object {
        val TEXT_DIRECTIONS = listOf(
            Resources.string.editors_text_horizontal to ShapeTextDirection.HORIZONTAL,
            Resources.string.editors_text_rotate_up to ShapeTextDirection.ROTATED_UP,
            Resources.string.editors_text_rotate_down to ShapeTextDirection.ROTATED_DOWN
        )
    }
}
