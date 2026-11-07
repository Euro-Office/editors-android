package lib.editors.gbase.ui.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.TooltipCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import lib.editors.base.engine.ThemeColors
import lib.editors.gbase.R
import lib.editors.gbase.ui.commands.SettingsCaption
import lib.editors.gbase.ui.commands.composePanel
import lib.editors.gbase.ui.commands.showComposeDialog
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
        fun pick(value: String?) {
            onColor(value)
            onBack()
        }
        // The first swatch of the current color is marked (theme colors may repeat it)
        val marked = (if (palette.theme.isEmpty()) emptyList() else (0 until ThemeColors.THEME_SHADES).flatMap { shade ->
            (0 until ThemeColors.THEME_COLUMNS).map { ThemeColors.themeColor(it, shade) }
        } + palette.standard + recent).firstOrNull { current != null && palette.hexOf(it).orEmpty().equals(current, ignoreCase = true) }
        val content = composePanel(context) {
            Column(Modifier.padding(bottom = 24.dp)) {
                if (none != null) {
                    ListItem(
                        headlineContent = { Text(stringResource(none)) },
                        leadingContent = { Swatch("", current == "", 28.dp) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { pick(null) }
                    )
                }
                @Composable
                fun SwatchRow(values: List<String>) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        for (value in values) {
                            val description = describe(value, palette, none)
                            Box(
                                Modifier.weight(1f).height(40.dp).clickable {
                                    if (!value.startsWith(THEME_PREFIX)) remember(value)
                                    pick(value)
                                }.semantics { contentDescription = description },
                                contentAlignment = Alignment.Center
                            ) { Swatch(palette.hexOf(value).orEmpty(), value == marked, 30.dp) }
                        }
                        repeat(ThemeColors.THEME_COLUMNS - values.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (palette.theme.isNotEmpty()) {
                    SettingsCaption(Resources.string.editors_color_theme)
                    for (shade in 0 until ThemeColors.THEME_SHADES) {
                        SwatchRow((0 until ThemeColors.THEME_COLUMNS).map { ThemeColors.themeColor(it, shade) })
                    }
                }
                SettingsCaption(Resources.string.editors_color_standard)
                SwatchRow(palette.standard.take(ThemeColors.THEME_COLUMNS))
                if (recent.isNotEmpty()) {
                    SettingsCaption(Resources.string.editors_color_recent)
                    SwatchRow(recent)
                }
                ListItem(
                    headlineContent = { Text(stringResource(Resources.string.editors_color_custom)) },
                    leadingContent = { Icon(painterResource(Resources.drawable.ic_editor_palette), null) },
                    trailingContent = { Icon(painterResource(Resources.drawable.ic_editor_chevron_right), null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.padding(top = 8.dp).clickable {
                        customColor(current) { hex ->
                            remember(hex)
                            pick(hex)
                        }
                    }
                )
            }
        }
        panels.show(title, content, onBack) {}
    }

    // A color by its hue, saturation and brightness, or its hex code
    private fun customColor(current: String?, onChosen: (String) -> Unit) {
        val activity = context as? Activity ?: return
        val start = current?.takeIf { it.length == 6 }?.let { runCatching { Color.parseColor("#$it") }.getOrNull() } ?: Color.parseColor("#0070C0")
        showComposeDialog(activity) { dismiss ->
            var color by remember { mutableIntStateOf(start) }
            var code by remember { mutableStateOf(hexOf(start)) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(Resources.string.editors_color_custom)) },
                text = {
                    Column {
                        AndroidView(
                            factory = { SpectrumView(it).apply { this.color = start; onChange = { picked -> color = picked; code = hexOf(picked) } } },
                            update = { spectrum -> if (spectrum.color != color) spectrum.color = color },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = { typed ->
                                    code = typed.uppercase().filter { it.isLetterOrDigit() }.take(6)
                                    code.takeIf { it.length == 6 }?.let { runCatching { Color.parseColor("#$it") }.getOrNull() }?.let { color = it }
                                },
                                label = { Text(stringResource(Resources.string.editors_color_hex)) },
                                prefix = { Text("#") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                                modifier = Modifier.weight(1f)
                            )
                            Box(Modifier.padding(start = 12.dp)) { Swatch(hexOf(color), false, 36.dp) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dismiss()
                        onChosen(hexOf(color))
                    }) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } }
            )
        }
    }

    // A color "RRGGBB" as a circle, [selected] ringed in the accent; "" crossed out
    @Composable
    private fun Swatch(hex: String, selected: Boolean, size: Dp) {
        val outline = MaterialTheme.colorScheme.outlineVariant
        val ring = MaterialTheme.colorScheme.primary
        val mark = MaterialTheme.colorScheme.onSurfaceVariant
        val fill = runCatching { androidx.compose.ui.graphics.Color(Color.parseColor("#$hex")) }.getOrNull()
        Canvas(Modifier.size(size)) {
            val radius = this.size.minDimension / 2
            val inner = if (selected) radius - 4.dp.toPx() else radius
            if (fill != null) drawCircle(fill, inner)
            drawCircle(outline, inner, style = Stroke(1.dp.toPx()))
            if (selected) drawCircle(ring, radius - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            if (fill == null) {
                val inset = this.size.minDimension * 0.25f
                drawLine(mark, Offset(this.size.width - inset, inset), Offset(inset, this.size.height - inset), strokeWidth = 1.5.dp.toPx())
            }
        }
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

/** A color swatch "RRGGBB", a circle; "" (automatic or none) is crossed out. */
internal fun swatchDrawable(context: Context, color: String, selected: Boolean): Drawable {
    val density = context.resources.displayMetrics.density
    val swatch = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (color.isEmpty()) Color.TRANSPARENT else Color.parseColor("#$color"))
        val border = ContextCompat.getColor(context, if (selected) Resources.color.editors_selection_handle else Resources.color.editors_sheet_header_line)
        setStroke(((if (selected) 3 else 1) * density).toInt(), border)
    }
    return Round(if (color.isNotEmpty()) swatch else LayerDrawable(arrayOf(swatch, NoneMark(ContextCompat.getColor(context, Resources.color.editors_icon), density))))
}

// [inner] in the middle, as wide as high: a circle stays one in cells wider or higher than that
private class Round(private val inner: Drawable) : Drawable() {
    override fun onBoundsChange(bounds: Rect) {
        val side = minOf(bounds.width(), bounds.height())
        val left = bounds.centerX() - side / 2
        val top = bounds.centerY() - side / 2
        inner.setBounds(left, top, left + side, top + side)
    }

    override fun draw(canvas: Canvas) = inner.draw(canvas)
    override fun setAlpha(alpha: Int) { inner.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { inner.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
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
