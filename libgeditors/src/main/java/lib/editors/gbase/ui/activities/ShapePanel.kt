package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.ChartPart
import lib.editors.base.engine.ChartState
import lib.editors.base.engine.DrawingColors
import lib.editors.base.engine.DrawingOrder
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.OutlineDash
import lib.editors.base.engine.ShapeShadow
import lib.editors.base.engine.ShapeTextDirection
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * The selected pictures, shapes and charts, as the first section of the format panels
 * ([FormatPanel], [CellFormatPanel]): a chart's type, style, titles, elements and data; their
 * fill, its opacity and outline, the current ones marked; their shadow and text's direction;
 * arranging and grouping.
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

            // Charts: which part fills, outlines and opacity go to (the panel anew with its colors)
            val part = colors.chartPart
            if (part != null) {
                scrollRow {
                    for ((value, label) in CHART_PARTS) {
                        choice(context.getString(label), { it.chartPart == value }) {
                            if (value != part) {
                                edit { setChartPart(value) }
                                back()
                            }
                        }
                    }
                }
            }

            // The current ones marked; tapping one marks it
            caption(Resources.string.editors_shape_fill)
            rows += colorPicker.Row(Resources.string.editors_shape_fill, Resources.string.editors_color_none, back) { color -> edit { setShapeFill(color) } }
                .also { addView(it.view); it.mark(colors.fill) }
            if (chooseFill != null) action(Resources.string.editors_fill_more) { chooseFill.invoke(back) }
            caption(Resources.string.editors_shape_opacity)
            fun opacity(percent: Int) {
                colors = colors.copy(opacity = percent.coerceIn(0, 100))
                updaters.forEach { it(colors) }
                edit { setShapeOpacity(colors.opacity) }
            }
            stepper(Resources.string.editors_shape_opacity, { context.getString(Resources.string.editors_fill_percent, it.opacity) }, {
                opacity((colors.opacity + OPACITY_STEP - 1) / OPACITY_STEP * OPACITY_STEP - OPACITY_STEP)
            }, {
                opacity(colors.opacity / OPACITY_STEP * OPACITY_STEP + OPACITY_STEP)
            })
            // The outline in its own panel, with what it is now
            action(Resources.string.editors_shape_outline, ::outlineText) {
                outline = colors
                showOutline(back)
            }

            // Shadows: not of charts (sdkjs gives them none)
            if (colors.shadow != null) {
                action(Resources.string.editors_shape_shadow, { shadow -> shadow.shadow?.let { context.getString(SHADOWS.getValue(it)) }.orEmpty() }) {
                    chooseShadow(colors.shadow, back) { shadow ->
                        colors = colors.copy(shadow = shadow)
                        updaters.forEach { it(colors) }
                        edit { setShapeShadow(shadow) }
                    }
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

    // The outline being set in its panel (the drawings' colors, changed there)
    private var outline = DrawingColors(null, null)

    private fun outlineWidth() = outline.outlineWidthMm?.takeIf { it > 0 } ?: DEFAULT_OUTLINE_POINTS * MM_PER_POINT

    private fun points(mm: Double) = context.getString(Resources.string.editors_format_points_value, trimPoints(mm / MM_PER_POINT))

    // Its color and size, "none" without one
    private fun outlineText(colors: DrawingColors): String {
        val color = colors.outline?.ifEmpty { null } ?: return context.getString(Resources.string.editors_color_none)
        val width = colors.outlineWidthMm?.takeIf { it > 0 } ?: DEFAULT_OUTLINE_POINTS * MM_PER_POINT
        return Accessibility.colorName(context, color) + ", " + points(width)
    }

    // The outline's color ("none" without one), size and border type, each keeping the others;
    // [back] to the format panel
    private fun showOutline(back: () -> Unit) {
        updaters.clear()
        val reopen = { showOutline(back) }
        fun apply(color: String?, widthMm: Double, dash: OutlineDash) {
            outline = outline.copy(outline = color, outlineWidthMm = widthMm, outlineDash = dash)
            updaters.forEach { it(outline) }
            edit { setShapeOutline(color, widthMm, dash) }
        }
        fun colorOrDefault() = outline.outline?.ifEmpty { null } ?: DEFAULT_OUTLINE_COLOR
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), dp(24))
            caption(Resources.string.editors_shape_outline_color)
            val colorRow = colorPicker.Row(Resources.string.editors_shape_outline, Resources.string.editors_color_none, reopen) { color ->
                apply(color, outlineWidth(), outline.outlineDash)
            }.also { addView(it.view); it.mark(outline.outline) }
            caption(Resources.string.editors_shape_outline_size)
            stepper(Resources.string.editors_shape_outline_size, { points(outlineWidth()) }, {
                val points = OUTLINE_POINTS.lastOrNull { it < outlineWidth() / MM_PER_POINT - 0.01 } ?: OUTLINE_POINTS.first()
                apply(colorOrDefault(), points * MM_PER_POINT, outline.outlineDash)
                colorRow.mark(outline.outline)
            }, {
                val points = OUTLINE_POINTS.firstOrNull { it > outlineWidth() / MM_PER_POINT + 0.01 } ?: OUTLINE_POINTS.last()
                apply(colorOrDefault(), points * MM_PER_POINT, outline.outlineDash)
                colorRow.mark(outline.outline)
            })
            action(Resources.string.editors_shape_border_type, { context.getString(DASHES.getValue(it.outlineDash)) }) {
                chooseDash(outline.outlineDash, reopen) { dash -> apply(colorOrDefault(), outlineWidth(), dash) }
            }
            colorPicker.load { loaded -> colorRow.setColors(loaded) }
        }
        panels.show(context.getString(Resources.string.editors_shape_outline), content, back, modal = false) {}
        updaters.forEach { it(outline) }
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

    // The shadows by pictures of them (where they fall, as a 3 x 3 grid) and "none", the current
    // one marked; a tap takes one and goes [back]
    private fun chooseShadow(current: ShapeShadow?, back: () -> Unit, onShadow: (ShapeShadow) -> Unit) {
        fun choose(shadow: ShapeShadow) {
            onShadow(shadow)
            back()
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), dp(24))
            addView(TextView(context).apply {
                setText(Resources.string.editors_shadow_none)
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setPaddingRelative(dp(8), 0, 0, 0)
                isSelected = current == ShapeShadow.NONE
                Accessibility.toggle(this)
                setBackgroundResource(R.drawable.editor_tool_background)
                setOnClickListener { choose(ShapeShadow.NONE) }
            })
            addView(GridLayout(context).apply {
                columnCount = 3
                for (shadow in SHADOW_GRID) {
                    addView(ShadowView(context, shadow).apply {
                        contentDescription = context.getString(SHADOWS.getValue(shadow))
                        TooltipCompat.setTooltipText(this, contentDescription)
                        isSelected = shadow == current
                        Accessibility.toggle(this)
                        setBackgroundResource(R.drawable.editor_tool_background)
                        setOnClickListener { choose(shadow) }
                    }, GridLayout.LayoutParams().apply {
                        width = dp(72)
                        height = dp(72)
                    })
                }
            })
        }
        panels.show(context.getString(Resources.string.editors_shape_shadow), content, back) {}
    }

    // A square with its shadow falling to a side (or around it, centered)
    private class ShadowView(context: Context, shadow: ShapeShadow) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val shape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, Resources.color.editors_accent) }
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, Resources.color.editors_icon); alpha = 110 }
        private val offset = SHADOW_OFFSETS.getValue(shadow)
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            val size = 28 * density
            val left = (width - size) / 2f
            val top = (height - size) / 2f
            val step = 5 * density
            // Centered: a little larger all around
            val spread = if (offset.first == 0 && offset.second == 0) 3 * density else 0f
            box.set(left + offset.first * step - spread, top + offset.second * step - spread, left + size + offset.first * step + spread, top + size + offset.second * step + spread)
            canvas.drawRect(box, shade)
            box.set(left, top, left + size, top + size)
            canvas.drawRect(box, shape)
        }
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
        const val OPACITY_STEP = 10
        val CHART_PARTS = listOf(ChartPart.CHART to Resources.string.editors_chart_area, ChartPart.PLOT_AREA to Resources.string.editors_chart_plot_area)
        val SHADOWS = linkedMapOf(
            ShapeShadow.NONE to Resources.string.editors_shadow_none,
            ShapeShadow.TOP_LEFT to Resources.string.editors_shadow_top_left,
            ShapeShadow.TOP to Resources.string.editors_shadow_top,
            ShapeShadow.TOP_RIGHT to Resources.string.editors_shadow_top_right,
            ShapeShadow.LEFT to Resources.string.editors_shadow_left,
            ShapeShadow.CENTER to Resources.string.editors_shadow_center,
            ShapeShadow.RIGHT to Resources.string.editors_shadow_right,
            ShapeShadow.BOTTOM_LEFT to Resources.string.editors_shadow_bottom_left,
            ShapeShadow.BOTTOM to Resources.string.editors_shadow_bottom,
            ShapeShadow.BOTTOM_RIGHT to Resources.string.editors_shadow_bottom_right,
            ShapeShadow.CUSTOM to Resources.string.editors_shadow_custom
        )
        val SHADOW_GRID = SHADOWS.keys.filter { it != ShapeShadow.NONE && it != ShapeShadow.CUSTOM }
        // Where each falls: -1, 0 or 1 to the right and down
        val SHADOW_OFFSETS = mapOf(
            ShapeShadow.TOP_LEFT to (-1 to -1), ShapeShadow.TOP to (0 to -1), ShapeShadow.TOP_RIGHT to (1 to -1),
            ShapeShadow.LEFT to (-1 to 0), ShapeShadow.CENTER to (0 to 0), ShapeShadow.RIGHT to (1 to 0),
            ShapeShadow.BOTTOM_LEFT to (-1 to 1), ShapeShadow.BOTTOM to (0 to 1), ShapeShadow.BOTTOM_RIGHT to (1 to 1)
        )
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
