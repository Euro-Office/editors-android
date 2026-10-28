package lib.editors.gbase.ui.activities

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import lib.editors.base.engine.ThemeColors
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * Choosing colors as web-apps' palettes offer them: the theme's colors (which follow the
 * document's color scheme), lighter and darker; the standard colors; the ones chosen last (in
 * any document) and a custom one. A panel shows a [Row] of the theme's colors with a button for
 * all of them ([choose], in the panel's place). Colors are "RRGGBB" or theme colors (see
 * [ThemeColors]); null is automatic or none.
 */
internal class ColorPicker(
    private val context: Context,
    private val panels: PanelHost,
    /** The theme's and the standard colors, from the engine (on its thread). */
    private val loadColors: (onLoaded: (ThemeColors) -> Unit) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** The colors loaded last: rows show them until [load] brings the current ones. */
    var colors = ThemeColors(emptyList(), STANDARD)
        private set

    /** Loads the colors again (the color scheme may have changed), then [onLoaded]. */
    fun load(onLoaded: (ThemeColors) -> Unit) = loadColors { loaded ->
        colors = loaded
        onLoaded(loaded)
    }

    /**
     * Swatches in a panel: [none] first if given (its name: automatic or none), the theme's
     * colors (the standard ones without a theme), then a button for all colors, under [title],
     * which come back to the panel with [onBack].
     */
    inner class Row(private val title: Int, private val none: Int?, private val onBack: () -> Unit, private val onColor: (String?) -> Unit) {

        private val swatches = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val view: View = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(swatches)
        }
        private var palette = colors
        private var items: List<Pair<String, View>> = emptyList()
        private var current: String? = null

        init {
            setColors(colors)
        }

        fun setColors(loaded: ThemeColors) {
            palette = loaded
            swatches.removeAllViews()
            val values = listOfNotNull("".takeIf { none != null }) +
                if (loaded.theme.isEmpty()) loaded.standard else (0 until ThemeColors.THEME_COLUMNS).map { ThemeColors.themeColor(it) }
            items = values.map { value ->
                value to View(context).apply {
                    // 48 dp to touch, 36 dp to see
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    contentDescription = describe(value, loaded, none)
                    setOnClickListener {
                        mark(loaded.hexOf(value).orEmpty())
                        onColor(value.ifEmpty { null })
                    }
                }.also { swatches.addView(it) }
            }
            swatches.addView(ImageView(context).apply {
                setImageResource(Resources.drawable.ic_editor_palette)
                contentDescription = context.getString(Resources.string.editors_color_more)
                TooltipCompat.setTooltipText(this, contentDescription)
                scaleType = ImageView.ScaleType.CENTER
                setBackgroundResource(R.drawable.editor_tool_background)
                setOnClickListener { choose(context.getString(title), current, none, onBack, onColor) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            mark(current)
        }

        /** Marks the current color "RRGGBB" ("" automatic or none, null not known). */
        fun mark(hex: String?) {
            current = hex
            items.forEach { (value, swatch) ->
                swatch.isSelected = hex != null && hex.equals(palette.hexOf(value).orEmpty(), ignoreCase = true)
                swatch.background = InsetDrawable(swatchDrawable(context, palette.hexOf(value).orEmpty(), swatch.isSelected), dp(6))
            }
        }
    }

    /**
     * All colors in a panel under [title], the [current] one ("RRGGBB") marked, [none] first if
     * given. A choice goes back to [onBack].
     */
    fun choose(title: String, current: String?, none: Int?, onBack: () -> Unit, onColor: (String?) -> Unit) {
        val palette = colors
        val recent = recent()
        var marked = false
        fun pick(value: String?) {
            onColor(value)
            onBack()
        }
        // A row of square swatches, as many as the theme's columns
        fun LinearLayout.swatchRow(values: List<String>) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (value in values) {
                    val hex = palette.hexOf(value).orEmpty()
                    val selected = !marked && current != null && hex.equals(current, ignoreCase = true)
                    marked = marked || selected
                    addView(View(context).apply {
                        background = InsetDrawable(swatchDrawable(context, hex, selected, round = false), dp(2))
                        contentDescription = describe(value, palette, none)
                        isSelected = selected
                        setOnClickListener {
                            if (!value.startsWith(THEME_PREFIX)) remember(value)
                            pick(value)
                        }
                    }, LinearLayout.LayoutParams(0, dp(34), 1f))
                }
                repeat(ThemeColors.THEME_COLUMNS - values.size) { addView(Space(context), LinearLayout.LayoutParams(0, dp(34), 1f)) }
            })
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), dp(24))
            if (none != null) {
                addView(TextView(context).apply {
                    setText(none)
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(48)
                    compoundDrawablePadding = dp(12)
                    setCompoundDrawablesRelative(swatchDrawable(context, "", current == "").apply { setBounds(0, 0, dp(28), dp(28)) }, null, null, null)
                    setBackgroundResource(R.drawable.editor_tool_background)
                    setOnClickListener { pick(null) }
                })
            }
            if (palette.theme.isNotEmpty()) {
                caption(Resources.string.editors_color_theme)
                for (shade in 0 until ThemeColors.THEME_SHADES) {
                    swatchRow((0 until ThemeColors.THEME_COLUMNS).map { ThemeColors.themeColor(it, shade) })
                }
            }
            caption(Resources.string.editors_color_standard)
            swatchRow(palette.standard.take(ThemeColors.THEME_COLUMNS))
            if (recent.isNotEmpty()) {
                caption(Resources.string.editors_color_recent)
                swatchRow(recent)
            }
            addView(TextView(context).apply {
                setText(Resources.string.editors_color_custom)
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setPaddingRelative(0, dp(8), 0, 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, ContextCompat.getDrawable(context, Resources.drawable.ic_editor_chevron_right), null)
                setBackgroundResource(R.drawable.editor_tool_background)
                setOnClickListener {
                    customColor(current) { hex ->
                        remember(hex)
                        pick(hex)
                    }
                }
            })
        }
        panels.show(title, content, onBack) {}
    }

    // A color by its hue, saturation and brightness, or its hex code
    private fun customColor(current: String?, onChosen: (String) -> Unit) {
        val start = current?.takeIf { it.length == 6 }?.let { runCatching { Color.parseColor("#$it") }.getOrNull() } ?: Color.parseColor("#0070C0")
        val spectrum = SpectrumView(context).apply { color = start }
        val preview = View(context).apply { background = swatchDrawable(context, hexOf(start), false) }
        var typing = false
        val code = EditText(context).apply {
            hint = context.getString(Resources.string.editors_color_hex)
            contentDescription = hint
            setText(hexOf(start))
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(6))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val color = s?.toString()?.takeIf { it.length == 6 }?.let { runCatching { Color.parseColor("#$it") }.getOrNull() } ?: return
                    typing = true
                    spectrum.color = color
                    preview.background = swatchDrawable(context, hexOf(color), false)
                    typing = false
                }
            })
        }
        spectrum.onChange = { color ->
            if (!typing) code.setText(hexOf(color))
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(24), dp(16), dp(24), 0)
            addView(spectrum)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(0, dp(12), 0, 0)
                addView(TextView(context).apply { text = "#" })
                addView(code, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(preview, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(12) })
            })
        }
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_color_custom)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ -> onChosen(hexOf(spectrum.color)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun LinearLayout.caption(text: Int) {
        addView(TextView(context).apply {
            setText(text)
            setPaddingRelative(0, dp(12), 0, dp(4))
        })
    }

    // What TalkBack says: the theme's color's place ("Accent 1") and the color
    private fun describe(value: String, palette: ThemeColors, none: Int?): String {
        if (value.isEmpty()) return context.getString(none ?: Resources.string.editors_color_none)
        val name = Accessibility.colorName(context, palette.hexOf(value).orEmpty())
        val index = value.removePrefix(THEME_PREFIX).takeIf { it != value }?.toIntOrNull() ?: return name
        val column = index / ThemeColors.THEME_SHADES
        val place = when (column) {
            0, 2 -> context.getString(Resources.string.editors_color_theme_background, column / 2 + 1)
            1, 3 -> context.getString(Resources.string.editors_color_theme_text, column / 2 + 1)
            else -> context.getString(Resources.string.editors_color_theme_accent, column - 3)
        }
        return context.getString(Resources.string.editors_color_in_theme, place, name)
    }

    private fun recent(): List<String> = preferences.getString(RECENT, null)?.split("\n")?.filter { it.length == 6 }.orEmpty()

    private fun remember(hex: String) {
        val recent = (listOf(hex.uppercase()) + recent().filter { !it.equals(hex, ignoreCase = true) }).take(ThemeColors.THEME_COLUMNS)
        preferences.edit().putString(RECENT, recent.joinToString("\n")).apply()
    }

    private fun hexOf(color: Int) = "%06X".format(color and 0xFFFFFF)

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val PREFERENCES = "editors_colors"
        const val RECENT = "recent"
        const val THEME_PREFIX = "theme:"
        // sdkjs' standard colors, until the engine's are loaded
        val STANDARD = listOf("C00000", "FF0000", "FFC000", "FFFF00", "92D050", "00B050", "00B0F0", "0070C0", "002060", "7030A0")
    }
}

/** A color swatch "RRGGBB", a circle or a square; "" (automatic or none) is crossed out. */
internal fun swatchDrawable(context: Context, color: String, selected: Boolean, round: Boolean = true): Drawable {
    val density = context.resources.displayMetrics.density
    val swatch = GradientDrawable().apply {
        if (round) shape = GradientDrawable.OVAL else cornerRadius = 4 * density
        setColor(if (color.isEmpty()) Color.TRANSPARENT else Color.parseColor("#$color"))
        val border = ContextCompat.getColor(context, if (selected) Resources.color.editors_selection_handle else Resources.color.editors_sheet_header_line)
        setStroke(((if (selected) 3 else 1) * density).toInt(), border)
    }
    return if (color.isNotEmpty()) swatch else LayerDrawable(arrayOf(swatch, NoneMark(ContextCompat.getColor(context, Resources.color.editors_icon), density)))
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

/**
 * A color picked by touch: its saturation (across) and brightness (down) in a square of its hue,
 * the hue in a bar below. The hex code field beside it is what TalkBack users type into.
 */
private class SpectrumView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val hsv = floatArrayOf(0f, 1f, 1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.WHITE
    }
    private val markerShadow = Paint(marker).apply {
        strokeWidth = 4 * density
        color = 0x66000000
    }
    private val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f)) }
    private val pureHue = floatArrayOf(0f, 1f, 1f)
    private var onBar = false
    // The square's and the bar's gradients, for the size (and the square's for the hue)
    private var squareShader: Shader? = null
    private var squareHue = -1f
    private var barShader: Shader? = null

    var onChange: (Int) -> Unit = {}

    var color: Int
        get() = Color.HSVToColor(hsv)
        set(value) {
            Color.colorToHSV(value, hsv)
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val barHeight get() = 28 * density
    private val squareHeight get() = height - barHeight - 12 * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (220 * density).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        barShader = LinearGradient(0f, 0f, w.toFloat(), 0f, hues, null, Shader.TileMode.CLAMP)
        squareShader = null
    }

    private fun squareShader(): Shader {
        val shader = squareShader
        if (shader != null && squareHue == hsv[0]) return shader
        pureHue[0] = hsv[0]
        squareHue = hsv[0]
        return ComposeShader(
            LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, Color.HSVToColor(pureHue), Shader.TileMode.CLAMP),
            LinearGradient(0f, 0f, 0f, squareHeight, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP),
            PorterDuff.Mode.SRC_OVER
        ).also { squareShader = it }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val square = squareHeight
        paint.shader = squareShader()
        canvas.drawRect(0f, 0f, w, square, paint)
        paint.shader = barShader
        val barTop = height - barHeight
        canvas.drawRect(0f, barTop, w, height.toFloat(), paint)
        paint.shader = null

        val x = hsv[1] * w
        val y = (1 - hsv[2]) * square
        canvas.drawCircle(x, y, 8 * density, markerShadow)
        canvas.drawCircle(x, y, 8 * density, marker)
        val hueX = hsv[0] / 360f * w
        canvas.drawRect(hueX - 3 * density, barTop, hueX + 3 * density, height.toFloat(), markerShadow)
        canvas.drawRect(hueX - 3 * density, barTop, hueX + 3 * density, height.toFloat(), marker)
    }

    @SuppressLint("ClickableViewAccessibility") // Not for TalkBack: see the class
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onBar = event.y > squareHeight
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> Unit
            else -> return super.onTouchEvent(event)
        }
        val across = (event.x / width).coerceIn(0f, 1f)
        if (onBar) {
            hsv[0] = minOf(across * 360f, 359.9f)
        } else {
            hsv[1] = across
            hsv[2] = 1 - (event.y / squareHeight).coerceIn(0f, 1f)
        }
        invalidate()
        onChange(color)
        return true
    }
}
