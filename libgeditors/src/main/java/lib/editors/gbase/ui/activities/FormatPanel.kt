package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import lib.editors.base.engine.DrawingColors
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.TextFormat
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * The formatting panel: font, size, styles, colors and paragraph settings of the text at the
 * cursor or the selection, in a panel (see [PanelHost]); the fill and outline of selected
 * pictures and shapes; in presentations without a selected one, the slide's background and
 * layout. It shows the current formatting (see [update]) and sends changes through [edit].
 */
internal class FormatPanel(
    private val context: Context,
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
    private val slide: SlideSettings? = null
) {

    /** The current slide's background color (null: another kind) and its layout. */
    class SlideSettings(val loadBackground: (onLoaded: (String?) -> Unit) -> Unit, val chooseLayout: () -> Unit)

    private val density = context.resources.displayMetrics.density
    private var dialog: PanelHost.Panel? = null
    private var format = TextFormat()
    private var fonts: List<String> = emptyList()
    private var styles: List<String> = emptyList()

    // Views that show the current format
    private val updaters = mutableListOf<(TextFormat) -> Unit>()

    // A picture, shape or chart is selected (not text in it): highlights and paragraph settings
    // don't apply to it (sdkjs would apply them to the paragraph it is anchored in)
    private var drawingSelected = false
    private val textOnly = mutableListOf<View>()
    // Text at the cursor or selected (not only a slide)
    private var hasText = true
    private val textSections = mutableListOf<View>()
    private var shapeSection: View? = null
    private var slideSection: View? = null
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
        update(format, drawingSelected, hasText)
        loadColors()
    }

    /**
     * The formatting after a command, [drawing] if a drawing is selected, [text] if there is
     * text at the cursor or selected; updates the panel if it is open.
     */
    fun update(newFormat: TextFormat, drawing: Boolean = false, text: Boolean = true) {
        val drawingChanged = drawing != drawingSelected
        format = newFormat
        drawingSelected = drawing
        hasText = text
        if (dialog != null) {
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
                row {
                    button(context.getString(Resources.string.editors_slides_layout)) { slide.chooseLayout() }
                }
                // Marked as the background, while no shape is selected
                val markShape = markFill
                markFill = { color -> if (drawingSelected) markShape(color) else background(color) }
            }.also { addView(it) }
        }

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(text)
        textSections += text
        text.textSettings()
    }

    private fun LinearLayout.textSettings() {
        heading(Resources.string.editors_format_text)
        row {
            val fontButton = button("") { chooseFont() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            updaters += { fontButton.text = it.fontName.ifEmpty { "—" } }
            // Not without a size (text of different sizes)
            button("−") { if (format.fontSize > 1) edit { setFontSize(format.fontSize - 1) } }.contentDescription = context.getString(Resources.string.editors_format_size_down)
            val size = label("")
            updaters += {
                size.text = if (it.fontSize > 0) trimNumber(it.fontSize) else "—"
                size.contentDescription = context.getString(Resources.string.editors_format_size) + " " + size.text
            }
            button("+") { if (format.fontSize > 0) edit { setFontSize(format.fontSize + 1) } }.contentDescription = context.getString(Resources.string.editors_format_size_up)
        }
        row {
            toggle(Resources.string.editors_format_strikeout, { it.strikeout }) { edit { setStrikeout(!format.strikeout) } }
                .paint.flags = Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
            toggle(Resources.string.editors_format_subscript, { it.baseline == 1 }) { edit { setBaseline(if (format.baseline == 1) 0 else 1) } }
            toggle(Resources.string.editors_format_superscript, { it.baseline == 2 }) { edit { setBaseline(if (format.baseline == 2) 0 else 2) } }
            button(context.getString(Resources.string.editors_format_clear)) { edit { clearFormatting() } }
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
                val styleButton = button("") { chooseStyle() }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                updaters += { styleButton.text = it.style.ifEmpty { "—" }.let { name -> EditorTranslations.styleName(context, name) } }
            }
        }
        row {
            toggle(Resources.string.editors_format_bullets, { it.list == 0 }) { edit { setList(0, if (format.list == 0) -1 else 1) } }
            toggle(Resources.string.editors_format_numbers, { it.list == 1 }) { edit { setList(1, if (format.list == 1) -1 else 1) } }
            button("⇤") { edit { indent(false) } }.contentDescription = context.getString(Resources.string.editors_format_indent_less)
            button("⇥") { edit { indent(true) } }.contentDescription = context.getString(Resources.string.editors_format_indent_more)
        }
        caption(Resources.string.editors_format_line_spacing)
        row {
            for (value in LINE_SPACINGS) {
                toggle(trimNumber(value), { it.lineRule == 1 && kotlin.math.abs(it.line - value) < 0.01 }) { edit { setLineSpacing(1, value) } }
            }
        }
        caption(Resources.string.editors_format_space_after)
        row {
            for (points in SPACE_AFTER_POINTS) {
                val mm = points * MM_PER_POINT
                toggle(context.getString(Resources.string.editors_format_points, points), { kotlin.math.abs(it.spacingAfter - mm) < 0.1 }) {
                    edit { setParagraphSpacing(maxOf(0.0, format.spacingBefore), mm) }
                }
            }
        }
    }

    private fun chooseFont() {
        if (fonts.isEmpty()) return
        val names = fonts.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_format_font)
            .setItems(names) { _, index -> edit { setFontName(names[index]) } }
            .show()
    }

    private fun chooseStyle() {
        if (styles.isEmpty()) return
        val names = styles.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_format_style)
            .setItems(names.map { EditorTranslations.styleName(context, it) }.toTypedArray()) { _, index -> edit { setStyle(names[index]) } }
            .show()
    }

    // ---------------------------------------------------------------------------------
    // Views

    private fun dp(value: Int) = (value * density).toInt()

    private fun LinearLayout.heading(text: Int) {
        addView(TextView(context).apply {
            setText(text)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPaddingRelative(0, dp(12), 0, dp(4))
        })
    }

    private fun LinearLayout.caption(text: Int) {
        addView(TextView(context).apply {
            setText(text)
            setPaddingRelative(0, dp(8), 0, dp(2))
        })
    }

    private fun LinearLayout.row(build: LinearLayout.() -> Unit) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            build()
        })
    }

    private fun LinearLayout.label(text: String): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        minWidth = dp(40)
    }.also { addView(it) }

    private fun LinearLayout.button(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 15f
        minWidth = dp(48)
        minHeight = dp(48)
        setPaddingRelative(dp(10), 0, dp(10), 0)
        setBackgroundResource(R.drawable.editor_tool_background)
        setOnClickListener { onClick() }
    }.also { addView(it) }

    private fun LinearLayout.toggle(text: Int, isOn: (TextFormat) -> Boolean, onClick: () -> Unit): TextView =
        toggle(context.getString(text), isOn, onClick)

    private fun LinearLayout.toggle(text: String, isOn: (TextFormat) -> Boolean, onClick: () -> Unit): TextView =
        button(text, onClick).also { view ->
            Accessibility.toggle(view)
            updaters += { view.isSelected = isOn(it) }
        }

    // A row of color swatches; "" is automatic or none
    private fun LinearLayout.colors(colors: List<String>, current: (TextFormat) -> String, onColor: (String?) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (color in colors) {
            val swatch = View(context).apply {
                // 48 dp to touch, 36 dp to see
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setOnClickListener { onColor(color.ifEmpty { null }) }
                contentDescription = Accessibility.colorName(context, color)
            }
            updaters += {
                swatch.isSelected = current(it).equals(color, ignoreCase = true)
                swatch.background = android.graphics.drawable.InsetDrawable(swatchDrawable(color, swatch.isSelected), dp(6))
            }
            row.addView(swatch)
        }
        addView(HorizontalScrollView(context).apply { addView(row) })
    }

    // A row of color swatches for colors from elsewhere ("" is none); returns what marks the current one
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

    private fun swatchDrawable(color: String, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (color.isEmpty()) {
            setColor(Color.TRANSPARENT)
        } else {
            setColor(Color.parseColor("#$color"))
        }
        val border = ContextCompat.getColor(context, if (selected) Resources.color.editors_selection_handle else Resources.color.editors_sheet_header_line)
        setStroke(if (selected) dp(3) else dp(1), border)
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
