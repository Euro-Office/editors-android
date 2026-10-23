package lib.editors.gbase.ui.activities

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import lib.editors.base.engine.DrawingColors
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.TextFormat
import lib.editors.resources.R as Resources

/**
 * The formatting panel: font, size, styles, colors and paragraph settings of the text at the
 * cursor or the selection, in a panel (see [PanelHost]); the fill and outline of selected
 * pictures and shapes; in presentations without a selected one, the slide's background and
 * layout; with the cursor in a table, the table's rows, columns, cells and style. It shows the
 * current formatting (see [update]) and sends changes through [edit].
 */
internal class FormatPanel(
    context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    /** Fonts and paragraph styles, from the engine (on its thread). */
    private val load: (onLoaded: (fonts: List<String>, styles: List<String>) -> Unit) -> Unit,
    /** Paragraph settings: documents and presentations; styles only for documents. */
    private val withParagraphs: Boolean,
    private val withStyles: Boolean,
    private val panels: PanelHost,
    /** The fill and outline of the selected drawing (on its thread). */
    private val loadShapeColors: (onLoaded: (DrawingColors?) -> Unit) -> Unit,
    /** Presentations: the slide's settings. */
    private val slide: SlideSettings? = null,
    /** Documents and presentations: the table's style (pictures of the styles, back to this panel); null without tables. */
    private val chooseTableStyle: ((onBack: () -> Unit) -> Unit)? = null
) : PanelBuilder<TextFormat>(context) {

    /** The current slide's background color (null: another kind), its layout and the design (back to this panel). */
    class SlideSettings(
        val loadBackground: (onLoaded: (String?) -> Unit) -> Unit,
        val chooseLayout: (onBack: () -> Unit) -> Unit,
        val chooseDesign: (onBack: () -> Unit) -> Unit
    )

    private var dialog: PanelHost.Panel? = null
    private var format = TextFormat()
    private var fonts: List<String> = emptyList()
    private var styles: List<String> = emptyList()

    // A picture, shape or chart is selected (not text in it): highlights and paragraph settings
    // don't apply to it (sdkjs would apply them to the paragraph it is anchored in)
    private var drawingSelected = false
    private val textOnly = mutableListOf<View>()
    // Text at the cursor or selected (not only a slide)
    private var hasText = true
    private val textSections = mutableListOf<View>()
    private var shapeSection: View? = null
    private var slideSection: View? = null
    private var tableSection: View? = null
    private var inTable = false
    private var markFill: (String?) -> Unit = {}
    private var markOutline: (String?) -> Unit = {}

    fun show() {
        load { loadedFonts, loadedStyles ->
            fonts = loadedFonts
            styles = loadedStyles
        }
        updaters.clear()
        textOnly.clear()
        textSections.clear()
        val content = content()
        dialog = panels.show(context.getString(Resources.string.editors_format), content) { dialog = null }
        update(format, drawingSelected, hasText, inTable)
        loadColors()
    }

    /**
     * The formatting after a command, [drawing] if a drawing is selected, [text] if there is
     * text at the cursor or selected, [table] if the cursor is in a table; updates the panel if
     * it is open.
     */
    fun update(newFormat: TextFormat, drawing: Boolean = false, text: Boolean = true, table: Boolean = false) {
        val drawingChanged = drawing != drawingSelected
        format = newFormat
        drawingSelected = drawing
        hasText = text
        inTable = table
        if (dialog != null) {
            tableSection?.isVisible = table
            updaters.forEach { it(newFormat) }
            textOnly.forEach { it.isVisible = !drawing }
            textSections.forEach { it.isVisible = drawing || text }
            shapeSection?.isVisible = drawing
            slideSection?.isVisible = !drawing
            if (drawingChanged) loadColors()
        }
    }

    // The current fill and outline, or background
    private fun loadColors() {
        if (drawingSelected) {
            loadShapeColors { colors ->
                markFill(colors?.fill)
                markOutline(colors?.outline)
            }
        } else {
            slide?.loadBackground { markFill(it) }
        }
    }

    private fun content(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(8), dp(16), dp(24))

        // A selected picture or shape
        shapeSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            heading(Resources.string.editors_shape)
            caption(Resources.string.editors_shape_fill)
            val fill = colorsOf(SHAPE_COLORS) { color -> edit { setShapeFill(color) } }
            caption(Resources.string.editors_shape_outline)
            val outline = colorsOf(SHAPE_COLORS) { color -> edit { setShapeOutline(color) } }
            markFill = fill
            markOutline = outline
        }.also { addView(it) }

        // The slide, in presentations when no picture or shape is selected
        if (slide != null) {
            slideSection = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                heading(Resources.string.editors_slide)
                caption(Resources.string.editors_slide_background)
                val background = colorsOf(SHAPE_COLORS.filter { it.isNotEmpty() }) { color -> color?.let { edit { setSlideBackground(it) } } }
                action(Resources.string.editors_slide_design) { slide.chooseDesign(::show) }
                action(Resources.string.editors_slides_layout) { slide.chooseLayout(::show) }
                // Marked as the background, while no shape is selected
                val markShape = markFill
                markFill = { color -> if (drawingSelected) markShape(color) else background(color) }
            }.also { addView(it) }
        }

        // The table at the cursor: what one opens the panel for there, so first
        if (chooseTableStyle != null) tableSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tableSettings(chooseTableStyle)
        }.also { addView(it) }

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(text)
        textSections += text
        text.textSettings()
    }

    private fun LinearLayout.tableSettings(chooseStyle: (onBack: () -> Unit) -> Unit) {
        heading(Resources.string.editors_doc_table_menu)
        fun table(name: String) = edit { tableAction(name) }
        scrollRow {
            group {
                icon(Resources.drawable.ic_editor_table_row_above, Resources.string.editors_doc_row_above) { table("rowAbove") }
                icon(Resources.drawable.ic_editor_table_row_below, Resources.string.editors_doc_row_below) { table("rowBelow") }
                icon(Resources.drawable.ic_editor_table_column_left, Resources.string.editors_doc_column_left) { table("columnLeft") }
                icon(Resources.drawable.ic_editor_table_column_right, Resources.string.editors_doc_column_right) { table("columnRight") }
            }
            group {
                icon(Resources.drawable.ic_editor_table_delete_row, Resources.string.editors_doc_delete_row) { table("deleteRow") }
                icon(Resources.drawable.ic_editor_table_delete_column, Resources.string.editors_doc_delete_column) { table("deleteColumn") }
            }
        }
        action(Resources.string.editors_table_style) { chooseStyle(::show) }
        scrollRow {
            button(context.getString(Resources.string.editors_doc_merge_cells)) { table("merge") }
            button(context.getString(Resources.string.editors_doc_split_cell)) { table("split") }
            button(context.getString(Resources.string.editors_doc_delete_table)) { table("deleteTable") }
        }
    }

    private fun LinearLayout.textSettings() {
        heading(Resources.string.editors_format_text)
        row {
            selector(Resources.string.editors_format_font, { it.fontName.ifEmpty { "—" } }) { chooseFont() }
            // Not without a size (text of different sizes)
            button("−") { if (format.fontSize > 1) edit { setFontSize(format.fontSize - 1) } }.contentDescription = context.getString(Resources.string.editors_format_size_down)
            val size = TextView(context).apply { gravity = Gravity.CENTER; minWidth = dp(40) }.also { addView(it) }
            updaters += {
                size.text = if (it.fontSize > 0) trimNumber(it.fontSize) else "—"
                size.contentDescription = context.getString(Resources.string.editors_format_size) + " " + size.text
            }
            button("+") { if (format.fontSize > 0) edit { setFontSize(format.fontSize + 1) } }.contentDescription = context.getString(Resources.string.editors_format_size_up)
        }
        scrollRow {
            group {
                icon(Resources.drawable.ic_editor_strikeout, Resources.string.editors_format_strikeout, { it.strikeout }) { edit { setStrikeout(!format.strikeout) } }
                icon(Resources.drawable.ic_editor_subscript, Resources.string.editors_format_subscript, { it.baseline == 1 }) {
                    edit { setBaseline(if (format.baseline == 1) 0 else 1) }
                }
                icon(Resources.drawable.ic_editor_superscript, Resources.string.editors_format_superscript, { it.baseline == 2 }) {
                    edit { setBaseline(if (format.baseline == 2) 0 else 2) }
                }
            }
            group {
                icon(Resources.drawable.ic_editor_clear_format, Resources.string.editors_format_clear) { edit { clearFormatting() } }
            }
        }
        caption(Resources.string.editors_format_color)
        colors(TEXT_COLORS, { it.color }) { color -> edit { setTextColor(color) } }
        val highlights = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            caption(Resources.string.editors_format_highlight)
            colors(HIGHLIGHTS, { it.highlight }) { color -> edit { setHighlight(color) } }
        }
        addView(highlights)
        textOnly += highlights

        if (!withParagraphs) return
        val paragraphs = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(paragraphs)
        textOnly += paragraphs
        paragraphs.paragraphSettings()
    }

    private fun LinearLayout.paragraphSettings() {
        heading(Resources.string.editors_format_paragraph)
        if (withStyles) {
            row {
                selector(Resources.string.editors_format_style, { it.style.ifEmpty { "—" }.let { name -> EditorTranslations.styleName(context, name) } }) {
                    chooseStyle()
                }
            }
        }
        scrollRow {
            group {
                icon(Resources.drawable.ic_editor_bullets, Resources.string.editors_format_bullets, { it.list == 0 }) {
                    edit { setList(0, if (format.list == 0) -1 else 1) }
                }
                icon(Resources.drawable.ic_editor_numbering, Resources.string.editors_format_numbers, { it.list == 1 }) {
                    edit { setList(1, if (format.list == 1) -1 else 1) }
                }
            }
            group {
                icon(Resources.drawable.ic_editor_indent_less, Resources.string.editors_format_indent_less) { edit { indent(false) } }
                icon(Resources.drawable.ic_editor_indent_more, Resources.string.editors_format_indent_more) { edit { indent(true) } }
            }
        }
        caption(Resources.string.editors_format_line_spacing)
        scrollRow {
            for (value in LINE_SPACINGS) {
                choice(trimNumber(value), { it.lineRule == 1 && kotlin.math.abs(it.line - value) < 0.01 }) { edit { setLineSpacing(1, value) } }
            }
        }
        caption(Resources.string.editors_format_space_after)
        scrollRow {
            for (points in SPACE_AFTER_POINTS) {
                val mm = points * MM_PER_POINT
                choice(context.getString(Resources.string.editors_format_points, points), { kotlin.math.abs(it.spacingAfter - mm) < 0.1 }) {
                    edit { setParagraphSpacing(maxOf(0.0, format.spacingBefore), mm) }
                }
            }
        }
    }

    // In the panel's place, then back to it
    private fun chooseFont() {
        val names = fonts.takeIf { it.isNotEmpty() } ?: return
        panels.choose(context.getString(Resources.string.editors_format_font), names, names.indexOf(format.fontName), onBack = ::show) { index ->
            edit { setFontName(names[index]) }
        }
    }

    private fun chooseStyle() {
        val names = styles.takeIf { it.isNotEmpty() } ?: return
        panels.choose(
            context.getString(Resources.string.editors_format_style), names.map { EditorTranslations.styleName(context, it) },
            names.indexOf(format.style), onBack = ::show
        ) { index -> edit { setStyle(names[index]) } }
    }

    // ---------------------------------------------------------------------------------
    // Views (the others in PanelBuilder)

    private fun LinearLayout.colorsOf(colors: List<String>, onColor: (String?) -> Unit): (String?) -> Unit {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val swatches = colors.map { color ->
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                contentDescription = Accessibility.colorName(context, color)
                background = android.graphics.drawable.InsetDrawable(swatchDrawable(color, false), dp(6))
            }.also { row.addView(it) }
        }
        fun mark(current: String?) = swatches.forEachIndexed { i, swatch ->
            swatch.isSelected = current != null && current.equals(colors[i], ignoreCase = true)
            swatch.background = android.graphics.drawable.InsetDrawable(swatchDrawable(colors[i], swatch.isSelected), dp(6))
        }
        swatches.forEachIndexed { i, swatch ->
            swatch.setOnClickListener {
                mark(colors[i])
                onColor(colors[i].ifEmpty { null })
            }
        }
        addView(HorizontalScrollView(context).apply { addView(row) })
        return ::mark
    }

    private fun trimNumber(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')

    private companion object {
        const val MM_PER_POINT = 25.4 / 72
        val TEXT_COLORS = listOf("", "000000", "595959", "C00000", "FF0000", "FFC000", "00B050", "0070C0", "002060", "7030A0")
        val HIGHLIGHTS = listOf("", "FFFF00", "00FF00", "00FFFF", "FF00FF", "0000FF", "FF0000", "C0C0C0")
        val LINE_SPACINGS = listOf(1.0, 1.15, 1.5, 2.0)
        val SPACE_AFTER_POINTS = listOf(0, 6, 12)
        // "" is none
        val SHAPE_COLORS = listOf("", "FFFFFF", "000000", "808080", "C00000", "FF0000", "FFC000", "FFFF00", "00B050", "0070C0", "002060", "7030A0")
    }
}
