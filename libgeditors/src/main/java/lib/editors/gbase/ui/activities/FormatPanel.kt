package lib.editors.gbase.ui.activities

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.FillTarget
import lib.editors.base.engine.ListKind
import lib.editors.base.engine.TextFormat
import lib.editors.resources.R as Resources

/**
 * The formatting panel: font, size, styles, colors and paragraph settings of the text at the
 * cursor or the selection, in a panel (see [PanelHost]); in presentations without a selected
 * picture or shape, the slide's background, design, layout and transition; with the cursor in a
 * table, the table's rows, columns, cells and style. Selected shapes have their own panel
 * ([ShapePanel]), which opens this one for their text. It shows the current formatting (see
 * [update]) and sends changes through [edit].
 */
internal class FormatPanel(
    context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    /** Fonts (all and the document's) and paragraph styles, from the engine (on its thread). */
    private val load: (onLoaded: (fonts: List<String>, documentFonts: List<String>, styles: List<String>) -> Unit) -> Unit,
    /** Paragraph settings: documents and presentations; styles only for documents. */
    private val withParagraphs: Boolean,
    private val withStyles: Boolean,
    /** Highlighting text: not in PDFs, whose editor marks text up instead. */
    private val withHighlights: Boolean,
    private val panels: PanelHost,
    private val colorPicker: ColorPicker,
    /** Presentations: the slide's settings. */
    private val slide: SlideSettings? = null,
    /** Documents and presentations: the table's style (pictures of the styles, back to this panel); null without tables. */
    private val chooseTableStyle: ((onBack: () -> Unit) -> Unit)? = null,
    /** All kinds of fills (see [FillPanel]) of slide backgrounds; null without them. */
    private val chooseFill: ((target: FillTarget, onBack: () -> Unit) -> Unit)? = null,
    /** List presets by their pictures; null without them. */
    private val chooseList: ((kind: ListKind, onBack: () -> Unit) -> Unit)? = null,
    /** Text documents: multilevel lists, and numbering restarted or continued. */
    private val withMultilevelLists: Boolean = false
) : PanelBuilder<TextFormat>(context) {

    /**
     * The current slide's background color (null: another kind), its layout and the design (back
     * to this panel), its transition.
     */
    class SlideSettings(
        val loadBackground: (onLoaded: (String?) -> Unit) -> Unit,
        val chooseLayout: (onBack: () -> Unit) -> Unit,
        val chooseDesign: (onBack: () -> Unit) -> Unit,
        val chooseTransition: () -> Unit
    )

    private var dialog: PanelHost.Panel? = null
    // The panel this one was opened from (a shape's, for its text)
    private var back: (() -> Unit)? = null
    private var format = TextFormat()
    private var fonts: List<String> = emptyList()
    private var documentFonts: List<String> = emptyList()
    private var styles: List<String> = emptyList()
    private val fontChooser = FontChooser(context, panels)

    // A picture, shape or chart is selected (not text in it): highlights and paragraph settings
    // don't apply to it (sdkjs would apply them to the paragraph it is anchored in)
    private var drawingSelected = false
    private val textOnly = mutableListOf<View>()
    // Text at the cursor or selected (not only a slide)
    private var hasText = true
    private val textSections = mutableListOf<View>()
    private var slideSection: View? = null
    private var tableSection: View? = null
    private var inTable = false
    private var markBackground: (String?) -> Unit = {}
    private val colorRows = mutableListOf<ColorPicker.Row>()

    /** Shows the panel; with [onBack] (the panel it was opened from) the text's settings only. */
    fun show(onBack: (() -> Unit)? = null) {
        back = onBack
        open()
    }

    // Again after the panels opened from it
    private fun open() {
        load { loadedFonts, loadedDocumentFonts, loadedStyles ->
            fonts = loadedFonts
            documentFonts = loadedDocumentFonts
            styles = loadedStyles
        }
        updaters.clear()
        textOnly.clear()
        textSections.clear()
        colorRows.clear()
        val content = content()
        val title = if (back != null) Resources.string.editors_format_text else Resources.string.editors_format
        dialog = panels.show(context.getString(title), content, back) { dialog = null }
        update(format, drawingSelected, hasText, inTable)
        loadBackground()
        // The theme's colors with the current color scheme
        colorPicker.load { colors -> colorRows.forEach { it.setColors(colors) } }
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
            slideSection?.isVisible = !drawing
            if (drawingChanged) loadBackground()
        }
    }

    // The current slide's background
    private fun loadBackground() {
        if (!drawingSelected) slide?.loadBackground { markBackground(it) }
    }

    private fun content(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(8), dp(16), dp(24))

        // The slide, in presentations when no picture or shape is selected
        if (slide != null) {
            slideSection = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                heading(Resources.string.editors_slide)
                caption(Resources.string.editors_slide_background)
                val background = colorRow(Resources.string.editors_slide_background, none = null) { color -> color?.let { edit { setSlideBackground(it) } } }
                if (chooseFill != null) action(Resources.string.editors_fill_more) { chooseFill.invoke(FillTarget.SLIDE, ::open) }
                action(Resources.string.editors_slide_design) { slide.chooseDesign(::open) }
                action(Resources.string.editors_slides_layout) { slide.chooseLayout(::open) }
                action(Resources.string.editors_slides_transition) { slide.chooseTransition() }
                markBackground = background::mark
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
        action(Resources.string.editors_table_style) { chooseStyle(::open) }
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
        val textColor = colorRow(Resources.string.editors_format_color, Resources.string.editors_color_automatic) { color -> edit { setTextColor(color) } }
        updaters += { textColor.mark(it.color) }
        if (withHighlights) {
            textOnly += action(Resources.string.editors_format_highlight, { if (it.highlight.isEmpty()) "" else Accessibility.colorName(context, it.highlight) }) {
                openSettings(Resources.string.editors_format_highlight) {
                    colors(HIGHLIGHTS, { it.highlight }) { color -> edit { setHighlight(color) } }
                }
            }
        }

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
        if (chooseList != null) action(Resources.string.editors_format_lists, ::listText) { openLists() }
        action(Resources.string.editors_format_spacing, ::lineSpacingText) {
            openSettings(Resources.string.editors_format_spacing) { spacingSettings() }
        }
        action(Resources.string.editors_format_direction, { context.getString(if (it.rtl) Resources.string.editors_format_rtl else Resources.string.editors_format_ltr) }) {
            panels.choose(
                context.getString(Resources.string.editors_format_direction),
                listOf(context.getString(Resources.string.editors_format_ltr), context.getString(Resources.string.editors_format_rtl)),
                if (format.rtl) 1 else 0, onBack = ::open
            ) { index -> edit { setRtl(index == 1) } }
        }
    }

    // The list presets by kind; in a numbered list, the numbering restarted or continued
    private fun openLists(): Unit = openSettings(Resources.string.editors_format_lists) {
        val choose = chooseList ?: return@openSettings
        action(Resources.string.editors_list_bullets) { choose(ListKind.BULLETS, ::openLists) }
        action(Resources.string.editors_list_numbering) { choose(ListKind.NUMBERS, ::openLists) }
        if (withMultilevelLists) {
            action(Resources.string.editors_list_multilevel) { choose(ListKind.MULTILEVEL, ::openLists) }
            val numbering = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            numbering.scrollRow {
                button(context.getString(Resources.string.editors_list_restart)) { edit { restartNumbering() } }
                button(context.getString(Resources.string.editors_list_continue)) { edit { continueNumbering() } }
            }
            addView(numbering)
            updaters += { numbering.isVisible = it.list == 1 }
        }
    }

    private fun listText(format: TextFormat) = when (format.list) {
        0 -> context.getString(Resources.string.editors_list_bullets)
        1 -> context.getString(Resources.string.editors_list_numbering)
        else -> context.getString(Resources.string.editors_list_none)
    }

    // Line spacing: a multiple of the line, or at least or exactly a height in points; the space
    // before and after paragraphs
    private fun LinearLayout.spacingSettings() {
        caption(Resources.string.editors_format_line_spacing)
        scrollRow {
            choice(context.getString(Resources.string.editors_format_line_multiple), { it.lineRule == LINE_MULTIPLE }) {
                if (format.lineRule != LINE_MULTIPLE) edit { setLineSpacing(LINE_MULTIPLE, 1.0) }
            }
            for (rule in listOf(LINE_AT_LEAST, LINE_EXACTLY)) {
                val label = if (rule == LINE_AT_LEAST) Resources.string.editors_format_line_at_least else Resources.string.editors_format_line_exactly
                choice(context.getString(label), { it.lineRule == rule }) {
                    if (format.lineRule != rule) edit { setLineSpacing(rule, DEFAULT_LINE_POINTS * MM_PER_POINT) }
                }
            }
        }
        stepper(Resources.string.editors_format_line_spacing, ::lineSpacingText, { stepLineSpacing(up = false) }, { stepLineSpacing(up = true) })
        scrollRow {
            for (value in LINE_SPACINGS) {
                choice(trimNumber(value), { it.lineRule == LINE_MULTIPLE && kotlin.math.abs(it.line - value) < 0.01 }) { edit { setLineSpacing(LINE_MULTIPLE, value) } }
            }
        }
        caption(Resources.string.editors_format_space_before)
        stepper(Resources.string.editors_format_space_before, { pointsText(it.spacingBefore) }, { stepSpacing(before = true, up = false) }, { stepSpacing(before = true, up = true) })
        caption(Resources.string.editors_format_space_after)
        stepper(Resources.string.editors_format_space_after, { pointsText(it.spacingAfter) }, { stepSpacing(before = false, up = false) }, { stepSpacing(before = false, up = true) })
    }

    // Settings of the text in the panel's place ([build]), back to the panel; they show the
    // current formatting as the panel does
    private fun openSettings(title: Int, build: LinearLayout.() -> Unit) {
        updaters.clear()
        textOnly.clear()
        textSections.clear()
        colorRows.clear()
        slideSection = null
        tableSection = null
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), dp(24))
            build()
        }
        dialog = panels.show(context.getString(title), content, ::open) { dialog = null }
        updaters.forEach { it(format) }
    }

    private fun lineSpacingText(format: TextFormat) =
        if (format.lineRule == LINE_MULTIPLE) trimNumber(format.line) else pointsText(format.line)

    // To the next step: multiples by a quarter line, heights by a point
    private fun stepLineSpacing(up: Boolean) {
        if (format.lineRule == LINE_MULTIPLE) {
            val value = step(format.line, LINE_STEP, up).coerceIn(LINE_STEP, MAX_LINE_MULTIPLE)
            edit { setLineSpacing(LINE_MULTIPLE, value) }
        } else {
            val points = step(format.line / MM_PER_POINT, 1.0, up).coerceIn(1.0, MAX_POINTS)
            edit { setLineSpacing(format.lineRule, points * MM_PER_POINT) }
        }
    }

    // Space before or after paragraphs, by points (automatic, -1, counts as none)
    private fun stepSpacing(before: Boolean, up: Boolean) {
        val current = maxOf(0.0, if (before) format.spacingBefore else format.spacingAfter) / MM_PER_POINT
        val mm = step(current, 1.0, up).coerceIn(0.0, MAX_POINTS) * MM_PER_POINT
        val spacingBefore = if (before) mm else maxOf(0.0, format.spacingBefore)
        val spacingAfter = if (before) maxOf(0.0, format.spacingAfter) else mm
        edit { setParagraphSpacing(spacingBefore, spacingAfter) }
    }

    // The next multiple of size above or below value
    private fun step(value: Double, size: Double, up: Boolean): Double =
        if (up) (Math.floor(value / size + 1e-6) + 1) * size else (Math.ceil(value / size - 1e-6) - 1) * size

    private fun pointsText(mm: Double): String {
        val points = Math.round(maxOf(0.0, mm) / MM_PER_POINT * 2) / 2.0
        return context.getString(Resources.string.editors_format_points_value, trimNumber(points))
    }

    // In the panel's place, then back to it
    private fun chooseFont() = fontChooser.choose(fonts, documentFonts, format.fontName, ::open) { name -> edit { setFontName(name) } }

    private fun chooseStyle() {
        val names = styles.takeIf { it.isNotEmpty() } ?: return
        panels.choose(
            context.getString(Resources.string.editors_format_style), names.map { EditorTranslations.styleName(context, it) },
            names.indexOf(format.style), onBack = ::open, searchHint = context.getString(Resources.string.editors_format_style_find)
        ) { index -> edit { setStyle(names[index]) } }
    }

    // ---------------------------------------------------------------------------------
    // Views (the others in PanelBuilder)

    // A row of colors, all of them in the panel's place (see ColorPicker)
    private fun LinearLayout.colorRow(title: Int, none: Int?, onColor: (String?) -> Unit): ColorPicker.Row =
        colorPicker.Row(title, none, ::open, onColor).also {
            addView(it.view)
            colorRows += it
        }

    private fun trimNumber(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')

    private companion object {
        const val MM_PER_POINT = 25.4 / 72
        // Word's highlight colors, as web-apps offers them; "" is none
        val HIGHLIGHTS = listOf(
            "", "FFFF00", "00FF00", "00FFFF", "FF00FF", "0000FF", "FF0000", "000080", "008080",
            "008000", "800080", "800000", "808000", "808080", "C0C0C0", "000000"
        )
        // EditorDocument.setLineSpacing's rules
        const val LINE_AT_LEAST = 0
        const val LINE_MULTIPLE = 1
        const val LINE_EXACTLY = 2
        const val LINE_STEP = 0.25
        const val MAX_LINE_MULTIPLE = 10.0
        const val DEFAULT_LINE_POINTS = 12.0
        const val MAX_POINTS = 1584.0
        val LINE_SPACINGS = listOf(1.0, 1.15, 1.5, 2.0, 2.5, 3.0)
    }
}
