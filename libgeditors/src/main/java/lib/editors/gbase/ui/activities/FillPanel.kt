package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.FillResources
import lib.editors.base.engine.FillTarget
import lib.editors.base.engine.GradientStop
import lib.editors.base.engine.ShapeFill
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * The fill of the selected shapes or the slide's background, in a panel (see [PanelHost]) as
 * web-apps' shape settings have it: a color, a gradient (linear at an angle or radial, its stops'
 * colors and positions), a pattern with its two colors, a texture or a picture (stretched or
 * tiled). Each change applies at once.
 */
internal class FillPanel(
    private val context: Context,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val panels: PanelHost,
    private val colorPicker: ColorPicker,
    /** Lets the user pick a picture, copied into the document's images: its name and size in pixels. */
    private val pickPicture: (onPicked: (name: String, width: Int, height: Int) -> Unit) -> Unit
) {

    private enum class Kind(val label: Int) {
        COLOR(Resources.string.editors_fill_color),
        GRADIENT(Resources.string.editors_fill_gradient),
        PATTERN(Resources.string.editors_fill_pattern),
        TEXTURE(Resources.string.editors_fill_texture),
        PICTURE(Resources.string.editors_fill_picture)
    }

    private val density = context.resources.displayMetrics.density
    private var fillResources: FillResources? = null
    private var textures: List<Bitmap?> = emptyList()

    private lateinit var target: FillTarget
    private lateinit var onBack: () -> Unit
    private var fill: ShapeFill = ShapeFill.None
    private var kind = Kind.COLOR
    // The last picture chosen here: tiling it again needs no new one
    private var picture: ShapeFill.Picture? = null

    /** Shows the fill of [target], then goes back with [onBack]. */
    fun show(target: FillTarget, onBack: () -> Unit) {
        val document = document() ?: return
        this.target = target
        this.onBack = onBack
        scope.launch {
            val (current, loaded) = withContext(EditorEngine.dispatcher) {
                runCatching { document.fill(target) }.getOrNull() to (fillResources ?: runCatching { document.fillResources() }.getOrNull())
            }
            if (fillResources == null && loaded != null) {
                fillResources = loaded
                textures = loaded.textures.map(::decodeDataUrl)
            }
            fill = current ?: ShapeFill.None
            kind = when (fill) {
                is ShapeFill.Gradient -> Kind.GRADIENT
                is ShapeFill.Pattern -> Kind.PATTERN
                is ShapeFill.Texture -> Kind.TEXTURE
                is ShapeFill.Picture -> Kind.PICTURE
                else -> Kind.COLOR
            }
            colorPicker.load { render() }
        }
    }

    // The panel again, for the current fill and kind
    private fun render() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), dp(24))
            addView(pills(Kind.entries.map { context.getString(it.label) }, Kind.entries.indexOf(kind)) { index ->
                kind = Kind.entries[index]
                render()
            })
            when (kind) {
                Kind.COLOR -> colorSettings()
                Kind.GRADIENT -> gradientSettings()
                Kind.PATTERN -> patternSettings()
                Kind.TEXTURE -> textureSettings()
                Kind.PICTURE -> pictureSettings()
            }
        }
        panels.show(context.getString(Resources.string.editors_fill_more), content, onBack) {}
    }

    private fun apply(newFill: ShapeFill) {
        fill = newFill
        edit { setFill(target, newFill) }
    }

    // ---------------------------------------------------------------------------------

    private fun LinearLayout.colorSettings() {
        val none = if (target == FillTarget.SHAPE) Resources.string.editors_color_none else null
        val row = colorPicker.Row(Resources.string.editors_fill_color, none, ::render) { color ->
            apply(if (color == null) ShapeFill.None else ShapeFill.Solid(color))
        }
        row.mark(when (val current = fill) {
            is ShapeFill.Solid -> current.color
            ShapeFill.None -> ""
            else -> null
        })
        addView(row.view)
    }

    private fun LinearLayout.gradientSettings() {
        val gradient = fill as? ShapeFill.Gradient ?: defaultGradient().also { apply(it) }
        addView(View(context).apply { background = gradientDrawable(gradient) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
            topMargin = dp(12)
            bottomMargin = dp(4)
        })
        addView(pills(listOf(context.getString(Resources.string.editors_fill_linear), context.getString(Resources.string.editors_fill_radial)), if (gradient.radial) 1 else 0) {
            apply(gradient.copy(radial = it == 1))
            render()
        })
        if (!gradient.radial) {
            caption(Resources.string.editors_fill_angle)
            stepper(Resources.string.editors_fill_angle, context.getString(Resources.string.editors_fill_degrees, gradient.angle)) { up ->
                apply(gradient.copy(angle = Math.floorMod(gradient.angle + if (up) ANGLE_STEP else -ANGLE_STEP, 360)))
                render()
            }
        }
        caption(Resources.string.editors_fill_stops)
        gradient.stops.forEachIndexed { index, stop ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // The stop's color: all colors in the panel's place
                addView(View(context).apply {
                    background = InsetDrawable(swatchDrawable(context, colorPicker.colors.hexOf(stop.color).orEmpty(), false), dp(6))
                    contentDescription = context.getString(Resources.string.editors_fill_stop, index + 1) + ", " +
                        Accessibility.colorName(context, colorPicker.colors.hexOf(stop.color).orEmpty())
                    setOnClickListener {
                        colorPicker.choose(context.getString(Resources.string.editors_fill_stop, index + 1), colorPicker.colors.hexOf(stop.color), null, ::render) { color ->
                            if (color != null) apply(gradient.copy(stops = gradient.stops.toMutableList().also { it[index] = stop.copy(color = color) }))
                        }
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                stepper(Resources.string.editors_fill_stop_position, context.getString(Resources.string.editors_fill_percent, stop.position)) { up ->
                    val position = (stop.position + if (up) POSITION_STEP else -POSITION_STEP).coerceIn(0, 100)
                    apply(gradient.copy(stops = gradient.stops.toMutableList().also { it[index] = stop.copy(position = position) }.sortedBy { it.position }))
                    render()
                }
                if (gradient.stops.size > 2) {
                    addView(TextView(context).apply {
                        text = "×"
                        textSize = 20f
                        gravity = Gravity.CENTER
                        contentDescription = context.getString(Resources.string.editors_fill_remove_stop)
                        setBackgroundResource(R.drawable.editor_tool_background)
                        setOnClickListener {
                            apply(gradient.copy(stops = gradient.stops.filterIndexed { i, _ -> i != index }))
                            render()
                        }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                }
            })
        }
        if (gradient.stops.size < MAX_STOPS) {
            addView(button(Resources.string.editors_fill_add_stop) {
                // Between the two stops furthest apart, in the color of the first of them
                val pairs = gradient.stops.zipWithNext()
                val (first, second) = pairs.maxByOrNull { (a, b) -> b.position - a.position } ?: return@button
                val stop = GradientStop(first.color, (first.position + second.position) / 2)
                apply(gradient.copy(stops = (gradient.stops + stop).sortedBy { it.position }))
                render()
            })
        }
    }

    private fun LinearLayout.patternSettings() {
        val patterns = fillResources?.patterns.orEmpty()
        val pattern = fill as? ShapeFill.Pattern ?: ShapeFill.Pattern(0, "theme:1", "theme:0").also { apply(it) }
        val foreground = colorPicker.colors.hexOf(pattern.foreground) ?: "000000"
        val background = colorPicker.colors.hexOf(pattern.background) ?: "FFFFFF"
        addView(grid(patterns.size, CELL_DP) { index, cell ->
            cell.background = LayerDrawable(arrayOf(patternDrawable(patterns[index], foreground, background), selection(index == pattern.pattern)))
            cell.contentDescription = context.getString(Resources.string.editors_fill_pattern) + " " + (index + 1)
            cell.setOnClickListener {
                apply(pattern.copy(pattern = index))
                render()
            }
        })
        caption(Resources.string.editors_fill_foreground)
        addView(colorPicker.Row(Resources.string.editors_fill_foreground, null, ::render) { color ->
            if (color != null) {
                apply(pattern.copy(foreground = color))
                render()
            }
        }.also { it.mark(foreground) }.view)
        caption(Resources.string.editors_fill_background)
        addView(colorPicker.Row(Resources.string.editors_fill_background, null, ::render) { color ->
            if (color != null) {
                apply(pattern.copy(background = color))
                render()
            }
        }.also { it.mark(background) }.view)
    }

    private fun LinearLayout.textureSettings() {
        val names = context.resources.getStringArray(Resources.array.editors_fill_textures)
        val current = (fill as? ShapeFill.Texture)?.texture ?: -1
        addView(grid(textures.size, TEXTURE_DP) { index, cell ->
            val texture = textures[index]
            cell.background = LayerDrawable(arrayOf(
                texture?.let { BitmapDrawable(context.resources, it).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) } }
                    ?: GradientDrawable(), selection(index == current)
            ))
            cell.contentDescription = names.getOrNull(index) ?: (context.getString(Resources.string.editors_fill_texture) + " " + (index + 1))
            androidx.appcompat.widget.TooltipCompat.setTooltipText(cell, cell.contentDescription)
            cell.setOnClickListener {
                apply(ShapeFill.Texture(index))
                render()
            }
        })
    }

    private fun LinearLayout.pictureSettings() {
        val current = fill as? ShapeFill.Picture
        addView(button(Resources.string.editors_fill_choose_picture) {
            pickPicture { name, width, height ->
                val chosen = ShapeFill.Picture(name, width, height, tile = current?.tile == true)
                picture = chosen
                apply(chosen)
                render()
            }
        })
        if (current != null) {
            addView(pills(listOf(context.getString(Resources.string.editors_fill_stretch), context.getString(Resources.string.editors_fill_tile)), if (current.tile) 1 else 0) {
                // The engine keeps the shape's picture when given none
                val tiled = (picture ?: ShapeFill.Picture()).copy(tile = it == 1)
                picture = tiled
                apply(tiled)
                render()
            })
        }
    }

    // ---------------------------------------------------------------------------------
    // Views

    // From the current solid color (or the theme's first accent) to white
    private fun defaultGradient(): ShapeFill.Gradient {
        val from = (fill as? ShapeFill.Solid)?.color ?: "theme:24"
        return ShapeFill.Gradient(listOf(GradientStop(from, 0), GradientStop("FFFFFF", 100)), angle = 90)
    }

    private fun gradientDrawable(gradient: ShapeFill.Gradient): Drawable {
        val colors = gradient.stops.map { Color.parseColor("#" + (colorPicker.colors.hexOf(it.color) ?: "FFFFFF")) }.toIntArray()
        val positions = gradient.stops.map { it.position / 100f }.toFloatArray()
        return ShapeDrawable(RectShape()).apply {
            shaderFactory = object : ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): Shader = if (gradient.radial) {
                    android.graphics.RadialGradient(width / 2f, height / 2f, maxOf(width, height) / 2f, colors, positions, Shader.TileMode.CLAMP)
                } else {
                    // The direction at the angle, across the bar
                    val radians = Math.toRadians(gradient.angle.toDouble())
                    val dx = (Math.cos(radians) * width / 2).toFloat()
                    val dy = (Math.sin(radians) * height / 2).toFloat()
                    android.graphics.LinearGradient(width / 2f - dx, height / 2f - dy, width / 2f + dx, height / 2f + dy, colors, positions, Shader.TileMode.CLAMP)
                }
            }
        }
    }

    // The 8 x 8 pattern ("0" foreground, "1" background) in its colors, repeated in steps of 3 dp
    private fun patternDrawable(bits: String, foreground: String, background: String): Drawable {
        val fg = Color.parseColor("#$foreground")
        val bg = Color.parseColor("#$background")
        val tile = Bitmap.createBitmap(PATTERN_SIZE, PATTERN_SIZE, Bitmap.Config.ARGB_8888)
        for (i in 0 until PATTERN_SIZE * PATTERN_SIZE) tile.setPixel(i % PATTERN_SIZE, i / PATTERN_SIZE, if (bits.getOrNull(i) == '0') fg else bg)
        val scaled = Bitmap.createScaledBitmap(tile, PATTERN_SIZE * dp(3).coerceAtLeast(1), PATTERN_SIZE * dp(3).coerceAtLeast(1), false)
        return ShapeDrawable(RectShape()).apply {
            paint.shader = BitmapShader(scaled, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            paint.style = Paint.Style.FILL
        }
    }

    private fun selection(selected: Boolean) = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        val color = ContextCompat.getColor(context, if (selected) Resources.color.editors_selection_handle else Resources.color.editors_sheet_header_line)
        setStroke(dp(if (selected) 3 else 1), color)
    }

    // count cells of size dp, as many in a row as fit
    private fun grid(count: Int, size: Int, bind: (Int, View) -> Unit): View {
        val cell = dp(size)
        val columns = ((panels.width - dp(32)) / (cell + dp(8))).coerceAtLeast(3)
        return GridLayout(context).apply {
            columnCount = columns
            setPaddingRelative(0, dp(8), 0, dp(8))
            for (index in 0 until count) {
                addView(View(context).apply {
                    Accessibility.toggle(this)
                    bind(index, this)
                }, GridLayout.LayoutParams().apply {
                    width = cell
                    height = cell
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
        }
    }

    // One of several choices: buttons, the chosen one marked
    private fun pills(labels: List<String>, selected: Int, onChoose: (Int) -> Unit): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { index, label ->
            row.addView(TextView(context).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPaddingRelative(dp(12), 0, dp(12), 0)
                setBackgroundResource(R.drawable.editor_tool_background)
                Accessibility.toggle(this)
                isSelected = index == selected
                setOnClickListener { if (index != selected) onChoose(index) }
            })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun LinearLayout.caption(text: Int) {
        addView(TextView(context).apply {
            setText(text)
            setPaddingRelative(0, dp(12), 0, dp(4))
        })
    }

    private fun button(text: Int, onClick: () -> Unit) = TextView(context).apply {
        setText(text)
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        setPaddingRelative(dp(4), 0, dp(4), 0)
        setTextColor(ContextCompat.getColor(context, Resources.color.editors_accent))
        setBackgroundResource(R.drawable.editor_tool_background)
        setOnClickListener { onClick() }
    }

    // A value between buttons that lower and raise it, named [name] to TalkBack
    private fun LinearLayout.stepper(name: Int, value: String, change: (up: Boolean) -> Unit) {
        val label = context.getString(name)
        fun button(sign: String, up: Boolean, description: Int) = TextView(context).apply {
            text = sign
            textSize = 18f
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setBackgroundResource(R.drawable.editor_tool_background)
            contentDescription = label + ", " + context.getString(description)
            setOnClickListener { change(up) }
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("−", false, Resources.string.editors_format_less))
            addView(TextView(context).apply {
                text = value
                gravity = Gravity.CENTER
                textSize = 15f
                minWidth = dp(64)
                contentDescription = "$label $value"
            })
            addView(button("+", true, Resources.string.editors_format_more))
        })
    }

    private fun decodeDataUrl(url: String): Bitmap? = runCatching {
        val bytes = Base64.decode(url.substring(url.indexOf(',') + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val ANGLE_STEP = 15
        const val POSITION_STEP = 5
        const val MAX_STOPS = 10
        const val PATTERN_SIZE = 8
        const val CELL_DP = 40
        const val TEXTURE_DP = 64
    }
}
