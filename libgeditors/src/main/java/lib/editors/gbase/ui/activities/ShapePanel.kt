package lib.editors.gbase.ui.activities

import android.content.Context
import android.widget.LinearLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import lib.editors.base.engine.FillTarget
import lib.editors.base.engine.OutlineDash
import lib.editors.base.engine.ShapeShadow
import lib.editors.base.engine.ShapeTextDirection
import lib.editors.gbase.ui.commands.Command
import lib.editors.gbase.ui.commands.CommandGroup
import lib.editors.gbase.ui.commands.composePanel
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
    private val chooseFill: ((onBack: () -> Unit) -> Unit)? = null,
    /** All kinds of fills as commands, for the command sheet; null without them. */
    private val fills: FillPanel? = null
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
            fun LinearLayout.fillAndOutline() {
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
            }

            // Charts: the part they go to, a tab each (the panel anew with its colors)
            val part = colors.chartPart
            if (part != null) {
                tabbed(CHART_PARTS.map { context.getString(it.second) }, CHART_PARTS.indexOfFirst { it.first == part }, { index ->
                    edit { setChartPart(CHART_PARTS[index].first) }
                    back()
                }) { fillAndOutline() }
            } else {
                fillAndOutline()
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

    /**
     * The selected drawings' commands, for their tab in the command sheet: a chart's settings,
     * the fill, opacity, outline and shadow, the text's direction, arranging. They show
     * [current] (the drawings loaded anew after each [change]); pages opened from them go [back].
     */
    fun commands(current: () -> Selected?, change: (EditorDocument.() -> EditorState) -> Unit, back: () -> Unit): List<CommandGroup> {
        val selected = current() ?: return emptyList()
        val colors = { current()?.colors ?: selected.colors }
        fun chooseFillColor() = colorPicker.choose(context.getString(Resources.string.editors_shape_fill), colors().fill, Resources.string.editors_color_none, back) { color ->
            change { setShapeFill(color) }
        }
        val fill = listOfNotNull(
            // Charts: the part the fill and outline go to
            selected.colors.chartPart?.let {
                Command.Segments(Resources.string.editors_chart_area, CHART_PARTS.map { (part, label) ->
                    Command.Toggle(label, null, { colors().chartPart == part }) { change { setChartPart(part) } }
                })
            },
            Command.Split(Resources.string.editors_shape_fill, Resources.drawable.ic_editor_shading, { colors().fill?.ifEmpty { null }?.let { "#$it" } },
                apply = ::chooseFillColor, more = ::chooseFillColor),
            fills?.let { panel ->
                Command.Page(Resources.string.editors_fill_more, null, commands = { panel.commands(back) }, open = { panel.load(FillTarget.SHAPE) })
            },
            Command.Stepper(Resources.string.editors_shape_opacity, value = { context.getString(Resources.string.editors_fill_percent, colors().opacity) },
                down = { change { setShapeOpacity(((colors().opacity + OPACITY_STEP - 1) / OPACITY_STEP * OPACITY_STEP - OPACITY_STEP).coerceIn(0, 100)) } },
                up = { change { setShapeOpacity((colors().opacity / OPACITY_STEP * OPACITY_STEP + OPACITY_STEP).coerceIn(0, 100)) } }),
            Command.Page(Resources.string.editors_shape_outline, Resources.drawable.ic_editor_border_color, commands = { outlineCommands(colors(), change, back) }),
            // Not of charts (sdkjs gives them none)
            selected.colors.shadow?.let {
                Command.Value(Resources.string.editors_shape_shadow, value = { colors().shadow?.let { context.getString(SHADOWS.getValue(it)) }.orEmpty() }) {
                    chooseShadow(colors().shadow, back) { shadow -> change { setShapeShadow(shadow) } }
                }
            }
        )
        val text = if (selected.hasText) listOf(
            Command.Segments(Resources.string.editors_text_orientation, TEXT_DIRECTIONS.map { (label, direction) ->
                Command.Toggle(label, null, { colors().textDirection == direction }) { change { setTextDirection(direction) } }
            })
        ) else emptyList()
        val arrange = listOf(
            Command.Action(Resources.string.editors_shape_front, null) { change { arrange(DrawingOrder.TO_FRONT) } },
            Command.Action(Resources.string.editors_shape_back, null) { change { arrange(DrawingOrder.TO_BACK) } }
        ) + if (withGroups) listOf(
            Command.Action(Resources.string.editors_shape_group, null) { change { group(true) } },
            Command.Action(Resources.string.editors_shape_ungroup, null) { change { group(false) } }
        ) else emptyList()
        return listOfNotNull(
            selected.chart?.let { chart -> CommandGroup(chartCommands(chart, { current()?.chart }, change, back)) },
            CommandGroup(fill),
            text.takeIf { it.isNotEmpty() }?.let { CommandGroup(it) },
            CommandGroup(arrange)
        )
    }

    // A chart's type, style, titles, layout, axes (the vertical one first) and data
    private fun chartCommands(chart: ChartState, live: () -> ChartState?, change: (EditorDocument.() -> EditorState) -> Unit, back: () -> Unit): List<Command> {
        val type = InsertCatalog.CHARTS.flatMap { it.items }.firstOrNull { it.value == chart.type }
        val axes = listOf(true, false).sortedBy { chartSettings.axisName(chart, it) != Resources.string.editors_chart_axis_vertical_settings }
            .filter { if (it) chart.valueAxis != null else chart.categoryAxis != null }
            .map { value ->
                Command.Page(chartSettings.axisName(chart, value), null, commands = { live()?.let { chartSettings.axisCommands(it, value, change) }.orEmpty() })
            }
        return listOfNotNull(
            Command.Value(Resources.string.editors_chart_type, type?.icon, value = { type?.let { context.getString(it.label) }.orEmpty() }) {
                chartUi.chooseType(Resources.string.editors_chart_type, chart.type, onBack = back) { change { setChartType(it) } }
            },
            if (chart.styles > 0) Command.Value(Resources.string.editors_chart_style, value = { chart.style?.toString().orEmpty() }) {
                chartSettings.chooseStyle(chart, back)
            } else null,
            Command.Page(Resources.string.editors_chart_titles, null, commands = { live()?.let { chartUi.titleCommands(it, change) }.orEmpty() }),
            if (chart.layout != null) {
                Command.Page(Resources.string.editors_chart_layout, null, commands = { live()?.let { chartSettings.layoutCommands(it, change) }.orEmpty() })
            } else null
        ) + axes + Command.Value(Resources.string.editors_chart_data, value = { chart.range.removePrefix("=") }) {
            if (chart.dataInCells) chartUi.editRange(chart, back) else chartUi.editData(back)
        }
    }

    // The outline's color ("none" without one), size and dashes, each keeping the others
    private fun outlineCommands(colors: DrawingColors, change: (EditorDocument.() -> EditorState) -> Unit, back: () -> Unit): List<CommandGroup> {
        val width = colors.outlineWidthMm?.takeIf { it > 0 } ?: DEFAULT_OUTLINE_POINTS * MM_PER_POINT
        val color = colors.outline?.ifEmpty { null } ?: DEFAULT_OUTLINE_COLOR
        fun chooseColor() = colorPicker.choose(context.getString(Resources.string.editors_shape_outline_color), colors.outline, Resources.string.editors_color_none, back) { chosen ->
            change { setShapeOutline(chosen, width, colors.outlineDash) }
        }
        return listOf(CommandGroup(listOf(
            Command.Split(Resources.string.editors_shape_outline_color, Resources.drawable.ic_editor_border_color, { colors.outline?.ifEmpty { null }?.let { "#$it" } },
                apply = ::chooseColor, more = ::chooseColor),
            Command.Stepper(Resources.string.editors_shape_outline_size, value = { points(width) }, down = {
                val points = OUTLINE_POINTS.lastOrNull { it < width / MM_PER_POINT - 0.01 } ?: OUTLINE_POINTS.first()
                change { setShapeOutline(color, points * MM_PER_POINT, colors.outlineDash) }
            }, up = {
                val points = OUTLINE_POINTS.firstOrNull { it > width / MM_PER_POINT + 0.01 } ?: OUTLINE_POINTS.last()
                change { setShapeOutline(color, points * MM_PER_POINT, colors.outlineDash) }
            }),
            Command.Value(Resources.string.editors_shape_border_type, value = { context.getString(DASHES.getValue(colors.outlineDash)) }) {
                chooseDash(colors.outlineDash, back) { dash -> change { setShapeOutline(color, width, dash) } }
            }
        )))
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
        val content = composePanel(context) {
            Column(Modifier.padding(vertical = 8.dp)) {
                for ((dash, name) in DASHES) {
                    val description = stringResource(name)
                    Row(
                        Modifier.fillMaxWidth().height(48.dp)
                            .selectable(selected = dash == current, role = Role.RadioButton) {
                                onDash(dash)
                                back()
                            }
                            .semantics { contentDescription = description }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = dash == current, onClick = null)
                        DashLine(dash, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f).height(48.dp).padding(start = 16.dp))
                    }
                }
            }
        }
        panels.show(context.getString(Resources.string.editors_shape_border_type), content, back) {}
    }

    // The shadows by pictures of them (where they fall, as a 3 x 3 grid) and "none", the current
    // one marked; a tap takes one and goes [back]
    private fun chooseShadow(current: ShapeShadow?, back: () -> Unit, onShadow: (ShapeShadow) -> Unit) {
        fun choose(shadow: ShapeShadow) {
            onShadow(shadow)
            back()
        }
        val content = composePanel(context) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ListItem(
                    headlineContent = { Text(stringResource(Resources.string.editors_shadow_none)) },
                    leadingContent = { RadioButton(selected = current == ShapeShadow.NONE, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.selectable(selected = current == ShapeShadow.NONE, role = Role.RadioButton) { choose(ShapeShadow.NONE) }
                )
                for (row in SHADOW_GRID.chunked(3)) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (shadow in row) {
                            val selected = shadow == current
                            val description = stringResource(SHADOWS.getValue(shadow))
                            Surface(
                                selected = selected,
                                onClick = { choose(shadow) },
                                shape = MaterialTheme.shapes.medium,
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.size(72.dp).padding(vertical = 4.dp).semantics { contentDescription = description }
                            ) {
                                ShadowPicture(shadow, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        panels.show(context.getString(Resources.string.editors_shape_shadow), content, back) {}
    }

    private fun trimPoints(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')

    internal companion object {
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

// A square with its shadow falling to a side (or around it, centered)
@Composable
private fun ShadowPicture(shadow: ShapeShadow, shape: Color, shade: Color) {
    val (dx, dy) = ShapePanel.SHADOW_OFFSETS.getValue(shadow)
    Canvas(Modifier.fillMaxSize()) {
        val size = 28.dp.toPx()
        val left = (this.size.width - size) / 2f
        val top = (this.size.height - size) / 2f
        val step = 5.dp.toPx()
        // Centered: a little larger all around
        val spread = if (dx == 0 && dy == 0) 3.dp.toPx() else 0f
        drawRect(shade.copy(alpha = 0.45f), Offset(left + dx * step - spread, top + dy * step - spread), Size(size + 2 * spread, size + 2 * spread))
        drawRect(shape, Offset(left, top), Size(size, size))
    }
}

// A dash as a line across, its pattern in multiples of the line's width (OOXML's)
@Composable
private fun DashLine(dash: OutlineDash, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val width = 3.dp.toPx()
        val effect = ShapePanel.DASH_PATTERNS[dash]?.let { pattern -> PathEffect.dashPathEffect(pattern.map { it * width }.toFloatArray()) }
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = width, pathEffect = effect)
    }
}
