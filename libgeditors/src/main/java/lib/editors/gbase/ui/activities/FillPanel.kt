package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.set
import androidx.core.graphics.toColorInt
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
import lib.editors.gbase.ui.commands.Command
import lib.editors.gbase.ui.commands.CommandGroup
import lib.editors.gbase.ui.commands.CommandPanel
import lib.editors.gbase.ui.commands.StepperControls
import lib.editors.gbase.ui.commands.composePanel
import kotlin.math.cos
import kotlin.math.sin
import lib.editors.resources.R as Resources

/**
 * The fill of the selected shapes or the slide's background as commands (see [commands]), as
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
    private var textures: List<ImageBitmap?> = emptyList()

    // The target loaded (the commands show none before)
    private var target by mutableStateOf<FillTarget?>(null)
    // What the commands show: they are drawn anew as they change
    private var fill by mutableStateOf<ShapeFill>(ShapeFill.None)
    private var kind by mutableStateOf(Kind.COLOR)
    // The last picture chosen here: tiling it again needs no new one
    private var picture: ShapeFill.Picture? = null

    /** Loads the fill of [target] (and the patterns, textures and theme colors) for [commands]; then [onLoaded]. */
    fun load(target: FillTarget, onLoaded: () -> Unit = {}) {
        val document = document() ?: return
        this.target = null
        scope.launch {
            val (current, loaded) = withContext(EditorEngine.dispatcher) {
                runCatching { document.fill(target) }.getOrNull() to (fillResources ?: runCatching { document.fillResources() }.getOrNull())
            }
            if (fillResources == null && loaded != null) {
                fillResources = loaded
                textures = loaded.textures.map { decodeDataUrl(it)?.asImageBitmap() }
            }
            fill = current ?: ShapeFill.None
            kind = when (fill) {
                is ShapeFill.Gradient -> Kind.GRADIENT
                is ShapeFill.Pattern -> Kind.PATTERN
                is ShapeFill.Texture -> Kind.TEXTURE
                is ShapeFill.Picture -> Kind.PICTURE
                else -> Kind.COLOR
            }
            colorPicker.load {
                this@FillPanel.target = target
                onLoaded()
            }
        }
    }

    /** Shows the fill of [target] in a panel of its own, then goes back with [onBack]. */
    fun show(target: FillTarget, onBack: () -> Unit) {
        fun render() {
            panels.show(context.getString(Resources.string.editors_fill_more), composePanel(context) { CommandPanel { commands(::render) } }, onBack) {}
        }
        load(target, ::render)
    }

    /** The fill's kind and its settings; pages opened from them (all colors) go [back]. */
    fun commands(back: () -> Unit): List<CommandGroup> {
        val target = target ?: return emptyList()
        val kinds = Command.Choice(Resources.string.editors_shape_fill, Resources.drawable.ic_editor_shading, Kind.entries.map { context.getString(it.label) }, { kind.ordinal }) { index ->
            choose(Kind.entries[index])
        }
        val settings = when (kind) {
            Kind.COLOR -> colorCommands(target, back)
            Kind.GRADIENT -> gradientCommands(back)
            Kind.PATTERN -> patternCommands(back)
            Kind.TEXTURE -> listOf(Command.Custom(Resources.string.editors_fill_texture) { Textures() })
            Kind.PICTURE -> pictureCommands()
        }
        return listOf(CommandGroup(listOf(kinds)), CommandGroup(settings))
    }

    // A kind chosen: gradients and patterns apply one at once, the others once one is chosen
    private fun choose(chosen: Kind) {
        kind = chosen
        if (chosen == Kind.GRADIENT && fill !is ShapeFill.Gradient) apply(defaultGradient())
        if (chosen == Kind.PATTERN && fill !is ShapeFill.Pattern) apply(ShapeFill.Pattern(0, "theme:1", "theme:0"))
    }

    private fun apply(newFill: ShapeFill) {
        fill = newFill
        val target = target ?: return
        edit { setFill(target, newFill) }
    }

    private fun hex(color: String, default: String) = colorPicker.colors.hexOf(color) ?: default

    // A color as the sheet shows one, "#rrggbb"
    private fun shown(color: String?) = color?.let { colorPicker.colors.hexOf(it) }?.ifEmpty { null }?.let { "#$it" }

    // A row with the color, all of them in its page
    private fun colorCommand(label: Int, color: () -> String?, none: Int?, back: () -> Unit, onColor: (String?) -> Unit): Command {
        val choose = {
            colorPicker.choose(context.getString(label), color()?.let { colorPicker.colors.hexOf(it) }, none, back, onColor)
        }
        return Command.Split(label, Resources.drawable.ic_editor_shading, { shown(color()) }, apply = choose, more = choose)
    }

    // ---------------------------------------------------------------------------------

    private fun colorCommands(target: FillTarget, back: () -> Unit): List<Command> {
        val none = if (target == FillTarget.SHAPE) Resources.string.editors_color_none else null
        return listOf(colorCommand(Resources.string.editors_fill_color, { (fill as? ShapeFill.Solid)?.color }, none, back) { color ->
            apply(if (color == null) ShapeFill.None else ShapeFill.Solid(color))
        })
    }

    private fun gradientCommands(back: () -> Unit): List<Command> {
        val gradient = fill as? ShapeFill.Gradient ?: defaultGradient()
        val stops = gradient.stops.mapIndexed { index, stop ->
            Command.Custom(Resources.string.editors_fill_stops) { Stop(gradient, index, stop, back) }
        }
        return listOfNotNull(
            Command.Custom(Resources.string.editors_fill_gradient) { GradientPreview(gradient) },
            Command.Segments(Resources.string.editors_fill_gradient, listOf(
                Command.Toggle(Resources.string.editors_fill_linear, null, { !gradient.radial }) { apply(gradient.copy(radial = false)) },
                Command.Toggle(Resources.string.editors_fill_radial, null, { gradient.radial }) { apply(gradient.copy(radial = true)) }
            )),
            if (gradient.radial) null else Command.Stepper(Resources.string.editors_fill_angle, value = { context.getString(Resources.string.editors_fill_degrees, gradient.angle) },
                down = { apply(gradient.copy(angle = Math.floorMod(gradient.angle - ANGLE_STEP, 360))) },
                up = { apply(gradient.copy(angle = Math.floorMod(gradient.angle + ANGLE_STEP, 360))) })
        ) + stops + listOfNotNull(
            if (gradient.stops.size < MAX_STOPS) Command.Action(Resources.string.editors_fill_add_stop, Resources.drawable.ic_editor_add) {
                // Between the two stops furthest apart, in the color of the first of them
                val (first, second) = gradient.stops.zipWithNext().maxByOrNull { (a, b) -> b.position - a.position } ?: return@Action
                val stop = GradientStop(first.color, (first.position + second.position) / 2)
                apply(gradient.copy(stops = (gradient.stops + stop).sortedBy { it.position }))
            } else null
        )
    }

    private fun patternCommands(back: () -> Unit): List<Command> {
        val pattern = fill as? ShapeFill.Pattern ?: return emptyList()
        return listOf(
            Command.Custom(Resources.string.editors_fill_pattern) { Patterns(pattern) },
            colorCommand(Resources.string.editors_fill_foreground, { pattern.foreground }, null, back) { color -> if (color != null) apply(pattern.copy(foreground = color)) },
            colorCommand(Resources.string.editors_fill_background, { pattern.background }, null, back) { color -> if (color != null) apply(pattern.copy(background = color)) }
        )
    }

    private fun pictureCommands(): List<Command> {
        val current = fill as? ShapeFill.Picture
        return listOfNotNull(
            Command.Action(Resources.string.editors_fill_choose_picture, Resources.drawable.ic_editor_image) {
                pickPicture { name, width, height ->
                    val chosen = ShapeFill.Picture(name, width, height, tile = current?.tile == true)
                    picture = chosen
                    apply(chosen)
                }
            },
            current?.let {
                // The engine keeps the shape's picture when given none
                fun tile(on: Boolean) {
                    val tiled = (picture ?: ShapeFill.Picture()).copy(tile = on)
                    picture = tiled
                    apply(tiled)
                }
                Command.Segments(Resources.string.editors_fill_tile, listOf(
                    Command.Toggle(Resources.string.editors_fill_stretch, null, { !it.tile }) { tile(false) },
                    Command.Toggle(Resources.string.editors_fill_tile, null, { it.tile }) { tile(true) }
                ))
            }
        )
    }

    // ---------------------------------------------------------------------------------
    // What the commands don't draw

    @Composable
    private fun GradientPreview(gradient: ShapeFill.Gradient) {
        val colors = gradient.stops.map { stop -> stop.position / 100f to Color(("#" + hex(stop.color, "FFFFFF")).toColorInt()) }.toTypedArray()
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(40.dp).clip(RoundedCornerShape(8.dp))
                .drawBehind {
                    val brush = if (gradient.radial) {
                        Brush.radialGradient(*colors, center = center, radius = maxOf(size.width, size.height) / 2)
                    } else {
                        // The direction at the angle, across the bar
                        val radians = Math.toRadians(gradient.angle.toDouble())
                        val dx = (cos(radians) * size.width / 2).toFloat()
                        val dy = (sin(radians) * size.height / 2).toFloat()
                        Brush.linearGradient(*colors, start = center - Offset(dx, dy), end = center + Offset(dx, dy))
                    }
                    drawRect(brush)
                }
        )
    }

    // A stop: its color (all colors in a page), its position, removing it
    @Composable
    private fun Stop(gradient: ShapeFill.Gradient, index: Int, stop: GradientStop, back: () -> Unit) {
        val name = stringResource(Resources.string.editors_fill_stop, index + 1)
        val hex = hex(stop.color, "")
        val description = name + ", " + Accessibility.colorName(context, hex)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(runCatching { Color("#$hex".toColorInt()) }.getOrDefault(Color.Transparent))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable {
                        colorPicker.choose(name, colorPicker.colors.hexOf(stop.color), null, back) { color ->
                            if (color != null) apply(gradient.copy(stops = gradient.stops.toMutableList().also { it[index] = stop.copy(color = color) }))
                        }
                    }
                    .semantics { contentDescription = description }
            )
            Text(name, Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
            StepperControls(stringResource(Resources.string.editors_fill_stop_position), stringResource(Resources.string.editors_fill_percent, stop.position), true, {
                move(gradient, index, -POSITION_STEP)
            }, {
                move(gradient, index, POSITION_STEP)
            })
            if (gradient.stops.size > 2) {
                IconButton(onClick = { apply(gradient.copy(stops = gradient.stops.filterIndexed { i, _ -> i != index })) }) {
                    Icon(painterResource(Resources.drawable.ic_editor_close), stringResource(Resources.string.editors_fill_remove_stop))
                }
            }
        }
    }

    private fun move(gradient: ShapeFill.Gradient, index: Int, by: Int) {
        val stop = gradient.stops[index]
        val position = (stop.position + by).coerceIn(0, 100)
        apply(gradient.copy(stops = gradient.stops.toMutableList().also { it[index] = stop.copy(position = position) }.sortedBy { it.position }))
    }

    @Composable
    private fun Patterns(pattern: ShapeFill.Pattern) {
        val patterns = fillResources?.patterns.orEmpty()
        val foreground = hex(pattern.foreground, "000000")
        val background = hex(pattern.background, "FFFFFF")
        val name = stringResource(Resources.string.editors_fill_pattern)
        Cells(patterns.size, CELL_DP.dp, { pattern.pattern == it }, { "$name ${it + 1}" }, { index ->
            remember(index, foreground, background) { ShaderBrush(ImageShader(patternBitmap(patterns[index], foreground, background), TileMode.Repeated, TileMode.Repeated)) }
        }) { apply(pattern.copy(pattern = it)) }
    }

    @Composable
    private fun Textures() {
        val names = context.resources.getStringArray(Resources.array.editors_fill_textures)
        val current = (fill as? ShapeFill.Texture)?.texture ?: -1
        val other = stringResource(Resources.string.editors_fill_texture)
        Cells(textures.size, TEXTURE_DP.dp, { it == current }, { names.getOrNull(it) ?: "$other ${it + 1}" }, { index ->
            textures[index]?.let { remember(it) { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) } }
        }) { apply(ShapeFill.Texture(it)) }
    }

    // [count] cells of [size], as many in a row as fit, each filled by its [brush]
    @Composable
    private fun Cells(count: Int, size: Dp, isSelected: (Int) -> Boolean, describe: (Int) -> String, brush: @Composable (Int) -> Brush?, onChoose: (Int) -> Unit) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (index in 0 until count) {
                val selected = isSelected(index)
                val fill = brush(index)
                val shape = RoundedCornerShape(8.dp)
                val description = describe(index)
                Box(
                    Modifier.size(size).clip(shape)
                        .drawBehind { if (fill != null) drawRect(fill) }
                        .border(if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                        .clickable { onChoose(index) }
                        .semantics {
                            contentDescription = description
                            this.selected = selected
                        }
                )
            }
        }
    }

    // From the current solid color (or the theme's first accent) to white
    private fun defaultGradient(): ShapeFill.Gradient {
        val from = (fill as? ShapeFill.Solid)?.color ?: "theme:24"
        return ShapeFill.Gradient(listOf(GradientStop(from, 0), GradientStop("FFFFFF", 100)), angle = 90)
    }

    // The 8 x 8 pattern ("0" foreground, "1" background) in its colors, its pixels 3 dp
    private fun patternBitmap(bits: String, foreground: String, background: String): ImageBitmap {
        val fg = "#$foreground".toColorInt()
        val bg = "#$background".toColorInt()
        val tile = createBitmap(PATTERN_SIZE, PATTERN_SIZE)
        for (i in 0 until PATTERN_SIZE * PATTERN_SIZE) tile[i % PATTERN_SIZE, i / PATTERN_SIZE] = if (bits.getOrNull(i) == '0') fg else bg
        val side = PATTERN_SIZE * (3 * density).toInt().coerceAtLeast(1)
        return tile.scale(side, side, filter = false).asImageBitmap()
    }

    private fun decodeDataUrl(url: String): Bitmap? = runCatching {
        val bytes = Base64.decode(url.substring(url.indexOf(',') + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private companion object {
        const val ANGLE_STEP = 15
        const val POSITION_STEP = 5
        const val MAX_STOPS = 10
        const val PATTERN_SIZE = 8
        const val CELL_DP = 40
        const val TEXTURE_DP = 64
    }
}
