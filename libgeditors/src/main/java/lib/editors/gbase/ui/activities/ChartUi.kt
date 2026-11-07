package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.ChartData
import lib.editors.base.engine.ChartState
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.gbase.R
import lib.editors.gbase.ui.commands.Command
import lib.editors.gbase.ui.commands.CommandGroup
import lib.editors.gbase.ui.commands.composePanel
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
    private val panels: PanelHost,
    /** Shows a bar under the top bar (the one while the chart's cells are selected on the sheet), null hides it. */
    private val showBar: (View?) -> Unit,
    private val edit: (EditorDocument.() -> EditorState) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Chart types with their pictures, [selected] (the chart's) highlighted; then [onBack], if any. */
    fun chooseType(title: Int, selected: Int? = null, onBack: (() -> Unit)? = null, onType: (Int) -> Unit) =
        picker.show(title, InsertCatalog.CHARTS, selected, onBack = onBack, onPick = onType)

    /** SmartArt layouts with their pictures, in web-apps' groups. */
    fun chooseSmartArt() =
        picker.show(Resources.string.editors_smartart_insert, InsertCatalog.SMART_ARTS, pictures = true) { layout ->
            edit { insertSmartArt(layout.type, layout.file) }
        }

    /** The texts of the chart's title and, if it has axes, their titles; an empty one is removed. */
    fun showTitles(chart: ChartState, onBack: () -> Unit) {
        val content = column()
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
        content.done {
            edit {
                setChartTitles(
                    title.text.toString().trim(),
                    horizontal?.text?.toString()?.trim() ?: chart.horizontalTitle,
                    vertical?.text?.toString()?.trim() ?: chart.verticalTitle
                )
            }
            onBack()
        }
        panels.show(context.getString(Resources.string.editors_chart_titles), content, onBack) {}
    }

    /** The titles as fields, for the command sheet; [change] sets them (an empty one is removed). */
    fun titleCommands(chart: ChartState, change: (EditorDocument.() -> EditorState) -> Unit): List<CommandGroup> = listOf(CommandGroup(listOfNotNull(
        Command.Field(Resources.string.editors_chart_title, { chart.titleText }) { title ->
            change { setChartTitles(title.trim(), chart.horizontalTitle, chart.verticalTitle) }
        },
        if (chart.axes) Command.Field(Resources.string.editors_chart_axis_horizontal, { chart.horizontalTitle }) { title ->
            change { setChartTitles(chart.titleText, title.trim(), chart.verticalTitle) }
        } else null,
        if (chart.axes) Command.Field(Resources.string.editors_chart_axis_vertical, { chart.verticalTitle }) { title ->
            change { setChartTitles(chart.titleText, chart.horizontalTitle, title.trim()) }
        } else null
    )))

    // ---------------------------------------------------------------------------------
    // Spreadsheets: the cells the chart shows

    fun editRange(chart: ChartState, onBack: () -> Unit) {
        lateinit var panel: PanelHost.Panel
        val range = EditText(context).apply {
            setText(chart.range.removePrefix("="))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val content = column().apply {
            addView(TextView(context).apply { setText(Resources.string.editors_chart_range) })
            addView(range)
            link(Resources.string.editors_chart_range_select) {
                panel.dismiss()
                pickRange()
            }
            link(Resources.string.editors_chart_switch) {
                edit { switchChartRowCol() }
                onBack()
            }
            done {
                val typed = range.text.toString().trim()
                if (typed.isNotEmpty() && typed != chart.range.removePrefix("=")) edit { setChartRange(typed) { rangeError() } }
                onBack()
            }
        }
        panel = panels.show(context.getString(Resources.string.editors_chart_data), content, onBack) {}
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

    fun editData(onBack: () -> Unit) {
        val document = document() ?: return
        scope.launch {
            val data = withContext(EditorEngine.dispatcher) { runCatching { document.chartData() }.getOrNull() } ?: return@launch
            showDataTable(data, onBack)
        }
    }

    private fun showDataTable(initial: ChartData, onBack: () -> Unit) {
        // Cells as text: [row][column], row 0 the series names, column 0 the categories
        val cells = mutableStateListOf<SnapshotStateList<String>>()
        cells += (listOf("") + initial.series).toMutableStateList()
        initial.categories.forEachIndexed { row, category ->
            cells += (listOf(category) + initial.values.map { series -> series.getOrNull(row)?.let(::number) ?: "" }).toMutableStateList()
        }
        fun done() {
            val series = cells[0].drop(1)
            val categories = cells.drop(1).map { it[0] }
            val values = series.indices.map { s -> cells.drop(1).map { row -> row[s + 1].replace(',', '.').toDoubleOrNull() } }
            edit { setChartData(ChartData(series, categories, values)) }
            onBack()
        }
        val content = composePanel(context) {
            Column(Modifier.padding(vertical = 8.dp)) {
                // Rows (categories) and columns (series) added or removed at the end
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DataChip(Resources.string.editors_chart_add_category, Resources.drawable.ic_editor_add) { cells += MutableList(cells[0].size) { "" }.toMutableStateList() }
                    DataChip(Resources.string.editors_chart_remove_category, Resources.drawable.ic_editor_remove, cells.size > 2) { cells.removeAt(cells.size - 1) }
                    DataChip(Resources.string.editors_chart_add_series, Resources.drawable.ic_editor_add) { cells.forEach { it += "" } }
                    DataChip(Resources.string.editors_chart_remove_series, Resources.drawable.ic_editor_remove, cells[0].size > 2) { cells.forEach { it.removeAt(it.size - 1) } }
                }
                Column(Modifier.padding(16.dp).horizontalScroll(rememberScrollState()).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
                    cells.forEachIndexed { r, row ->
                        Row {
                            row.forEachIndexed { c, value ->
                                DataCell(value, header = r == 0 || c == 0, number = r > 0 && c > 0, width = if (c == 0) 112.dp else 88.dp, enabled = !(r == 0 && c == 0)) {
                                    row[c] = it
                                }
                            }
                        }
                    }
                }
                Button(onClick = ::done, modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp)) { Text(stringResource(Resources.string.editors_done)) }
            }
        }
        panels.show(context.getString(Resources.string.editors_chart_data), content, onBack) {}
    }

    @Composable
    private fun DataChip(label: Int, icon: Int, enabled: Boolean = true, onClick: () -> Unit) {
        AssistChip(onClick = onClick, enabled = enabled, label = { Text(stringResource(label)) }, leadingIcon = { Icon(painterResource(icon), null, Modifier.size(18.dp)) })
    }

    // A cell of the table: the series' names and the categories set apart
    @Composable
    private fun DataCell(value: String, header: Boolean, number: Boolean, width: Dp, enabled: Boolean, onValue: (String) -> Unit) {
        val colors = MaterialTheme.colorScheme
        BasicTextField(
            value = value,
            onValueChange = onValue,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = colors.onSurface,
                textAlign = if (number) TextAlign.End else TextAlign.Start,
                fontWeight = if (header) FontWeight.Medium else FontWeight.Normal
            ),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
            modifier = Modifier.width(width).height(44.dp)
                .background(if (header) colors.surfaceContainerHighest else colors.surface)
                .border(0.5.dp, colors.outlineVariant)
                .padding(horizontal = 8.dp, vertical = 12.dp)
        )
    }

    // ---------------------------------------------------------------------------------
    // Panels' views

    private fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(4), dp(16), dp(24))
    }

    // An action in a panel, as a link
    private fun LinearLayout.link(label: Int, action: () -> Unit) = addView(TextView(context).apply {
        setText(label)
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(4), 0, dp(4), 0)
        setTextColor(context.getColor(Resources.color.editors_selection_handle))
        setBackgroundResource(R.drawable.editor_tool_background)
        setOnClickListener { action() }
    })

    // Applies what the panel's fields say, at its end
    private fun LinearLayout.done(action: () -> Unit) = addView(TextView(context).apply {
        setText(Resources.string.editors_done)
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPaddingRelative(dp(20), 0, dp(20), 0)
        setTextColor(context.getColor(Resources.color.editors_selection_handle))
        setBackgroundResource(R.drawable.editor_tool_background)
        setOnClickListener { action() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.END
        topMargin = dp(12)
    })

    private fun number(value: Double): String =
        if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private fun dp(value: Int) = (value * density).toInt()
}
