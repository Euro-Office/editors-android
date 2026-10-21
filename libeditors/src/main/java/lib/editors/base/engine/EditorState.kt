package lib.editors.base.engine

import org.json.JSONObject

/** Cursor line on a page, in millimeters (slanted for rotated text). */
data class Cursor(val page: Int, val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/** Selected area on a page: four corners (x1, y1 ... x4, y4) in millimeters. */
class SelectionArea(val page: Int, val points: DoubleArray)

/** Paragraph alignment, with sdkjs' values. */
enum class Alignment(val value: Int) {
    RIGHT(0), LEFT(1), CENTER(2), JUSTIFY(3);

    companion object {
        fun of(value: Int?): Alignment? = entries.firstOrNull { it.value == value }
    }
}

data class ToolbarState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val alignment: Alignment? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

/**
 * The editor's view state after a command: where to draw the cursor and selection,
 * which pages to draw again and the toolbar state.
 */
class EditorState(
    val cursor: Cursor?,
    val selection: List<SelectionArea>,
    val dirtyPages: Set<Int>,
    val layoutChanged: Boolean,
    /** Changed since opening or the last save. */
    val modified: Boolean,
    val toolbar: ToolbarState
) {
    companion object {
        fun parse(json: String): EditorState {
            val root = JSONObject(json)
            val cursor = root.optJSONObject("cursor")?.let {
                Cursor(it.getInt("page"), it.getDouble("x1"), it.getDouble("y1"), it.getDouble("x2"), it.getDouble("y2"))
            }
            val selectionJson = root.getJSONArray("selection")
            val selection = (0 until selectionJson.length()).map { index ->
                val area = selectionJson.getJSONObject(index)
                val points = area.getJSONArray("points")
                SelectionArea(area.getInt("page"), DoubleArray(points.length()) { points.getDouble(it) })
            }
            val dirtyPages = root.getJSONObject("dirtyPages").keys().asSequence().map { it.toInt() }.toSet()
            val ui = root.getJSONObject("interface")
            val toolbar = ToolbarState(
                bold = ui.optBoolean("Bold"),
                italic = ui.optBoolean("Italic"),
                underline = ui.optBoolean("Underline"),
                alignment = if (ui.has("PrAlign")) Alignment.of(ui.getInt("PrAlign")) else null,
                canUndo = ui.optBoolean("CanUndo"),
                canRedo = ui.optBoolean("CanRedo")
            )
            return EditorState(cursor, selection, dirtyPages, root.getBoolean("layoutChanged"), root.getBoolean("modified"), toolbar)
        }
    }
}
