package lib.editors.base.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * The fill of shapes and slide backgrounds ([EditorDocument.setFill]). Colors are "RRGGBB" or
 * theme colors (see [ThemeColors]); the ones [EditorDocument.fill] reports are "RRGGBB".
 */
sealed class ShapeFill {

    object None : ShapeFill()

    data class Solid(val color: String) : ShapeFill()

    /** Linear at [angle] degrees (0 left to right, 90 top to bottom) or [radial] from the middle. */
    data class Gradient(val stops: List<GradientStop>, val angle: Int = 0, val radial: Boolean = false) : ShapeFill()

    /** [pattern] is an index in [FillResources.patterns]. */
    data class Pattern(val pattern: Int, val foreground: String, val background: String) : ShapeFill()

    /** [texture] is an index in [FillResources.textures]. */
    data class Texture(val texture: Int) : ShapeFill()

    /**
     * A picture in the document's images directory ([EditorDocument.imagesDirectory]), [width] x
     * [height] pixels, stretched or [tile]d. Reported fills have no [name] and size.
     */
    data class Picture(val name: String = "", val width: Int = 0, val height: Int = 0, val tile: Boolean = false) : ShapeFill()

    fun toJson(): JSONObject = when (this) {
        None -> JSONObject().put("type", "none")
        is Solid -> JSONObject().put("type", "solid").put("color", color)
        is Gradient -> JSONObject().put("type", "gradient").put("angle", angle).put("radial", radial)
            .put("stops", JSONArray(stops.map { JSONObject().put("color", it.color).put("position", it.position) }))
        is Pattern -> JSONObject().put("type", "pattern").put("pattern", pattern).put("foreground", foreground).put("background", background)
        is Texture -> JSONObject().put("type", "texture").put("texture", texture)
        is Picture -> JSONObject().put("type", "picture").put("picture", name).put("width", width).put("height", height).put("tile", tile)
    }

    companion object {
        /** A fill from the engine's JSON, null for "null" (nothing to fill). */
        fun parse(json: String): ShapeFill? {
            if (json == "null" || json.isEmpty()) return null
            val root = JSONObject(json)
            return when (root.optString("type")) {
                "solid" -> Solid(root.optString("color"))
                "gradient" -> {
                    val stops = root.optJSONArray("stops") ?: JSONArray()
                    Gradient(
                        (0 until stops.length()).map { stops.getJSONObject(it).let { stop -> GradientStop(stop.optString("color"), stop.optDouble("position").toInt()) } },
                        Math.round(root.optDouble("angle")).toInt(), root.optBoolean("radial")
                    )
                }
                "pattern" -> Pattern(root.optInt("pattern"), root.optString("foreground"), root.optString("background"))
                "texture" -> Texture(root.optInt("texture"))
                "picture" -> Picture(tile = root.optBoolean("tile"))
                else -> None
            }
        }
    }
}

/** A gradient's color at [position] percent of its way. */
data class GradientStop(val color: String, val position: Int)

/**
 * What fills are made of: [patterns], each 8 x 8 pixels as 64 "0" (foreground) and "1"
 * (background) by rows, and [textures] as data URLs (PNG).
 */
data class FillResources(val patterns: List<String>, val textures: List<String>) {
    companion object {
        fun parse(json: String): FillResources {
            val root = JSONObject(json)
            fun list(name: String) = root.optJSONArray(name)?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
            return FillResources(list("patterns"), list("textures"))
        }
    }
}

/** What fills: the selected shapes or the current slide's background. */
enum class FillTarget(val value: String) { SHAPE("shape"), SLIDE("slide") }

/** The kinds of list presets ([EditorDocument.setListPreset]); previews "list-<value>". */
enum class ListKind(val value: String) { BULLETS("bullets"), NUMBERS("numbers"), MULTILEVEL("multilevel") }
