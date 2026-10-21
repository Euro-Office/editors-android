package lib.editors.base.engine

import org.json.JSONObject

/** Cursor line on a page, in millimeters (slanted for rotated text). */
data class Cursor(val page: Int, val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/** Selected area on a page: four corners (x1, y1 ... x4, y4) in millimeters. */
class SelectionArea(val page: Int, val points: DoubleArray)

/** Column and row boundaries of a sheet in millimeters from its top left corner (n + 1 for n columns). */
class SheetGeometry(val cols: DoubleArray, val rows: DoubleArray) {
    val colCount get() = cols.size - 1
    val rowCount get() = rows.size - 1
    val widthMm get() = cols.last()
    val heightMm get() = rows.last()

    fun columnAt(xMm: Double): Int = indexAt(cols, xMm)

    fun rowAt(yMm: Double): Int = indexAt(rows, yMm)

    // The column or row whose end is after the position
    private fun indexAt(bounds: DoubleArray, position: Double): Int {
        if (bounds.size < 2) return 0
        var low = 0
        var high = bounds.size - 2
        while (low < high) {
            val middle = (low + high) / 2
            if (bounds[middle + 1] <= position) low = middle + 1 else high = middle
        }
        return low
    }
}

class Sheet(val name: String, val hidden: Boolean)

/**
 * Spreadsheets: the selection (columns c1..c2, rows r1..r2), the active cell, its content for
 * editing ([text]: value or formula) and the sheets. [geometry] is set when it changed.
 */
class SheetState(
    val c1: Int, val r1: Int, val c2: Int, val r2: Int,
    val col: Int, val row: Int,
    /** The active cell's name, like "B2". */
    val name: String,
    val text: String,
    val sheets: List<Sheet>,
    /** Index of the active sheet. */
    val active: Int,
    val geometry: SheetGeometry?
) {
    val isSingleCell get() = c1 == c2 && r1 == r2
}

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
    /** Selected pictures and shapes: their frames. */
    val drawings: List<SelectionArea>,
    val dirtyPages: Set<Int>,
    val layoutChanged: Boolean,
    /** Pages are still to be laid out: call [EditorDocument.tick] until this is false. */
    val layoutPending: Boolean,
    /** Presentations: the current slide (-1 for documents). */
    val currentPage: Int,
    /** Changed since opening or the last save. */
    val modified: Boolean,
    val toolbar: ToolbarState,
    /** The exception sdkjs threw in the command, if any (the state is still valid). */
    val error: String? = null,
    /** Spreadsheets: selection, active cell and sheets. */
    val sheet: SheetState? = null
) {
    companion object {
        /** Throws [org.json.JSONException] if [json] isn't a state (the engine failed). */
        fun parse(json: String): EditorState {
            val root = JSONObject(json)
            val cursor = root.optJSONObject("cursor")?.let {
                Cursor(it.getInt("page"), it.getDouble("x1"), it.getDouble("y1"), it.getDouble("x2"), it.getDouble("y2"))
            }
            fun areas(name: String): List<SelectionArea> {
                val json = root.getJSONArray(name)
                return (0 until json.length()).map { index ->
                    val area = json.getJSONObject(index)
                    val points = area.getJSONArray("points")
                    SelectionArea(area.getInt("page"), DoubleArray(points.length()) { points.getDouble(it) })
                }
            }
            val dirtyPages = root.getJSONObject("dirtyPages").keys().asSequence().map { it.toInt() }.toSet()
            val ui = root.getJSONObject("interface")
            val toolbar = ToolbarState(
                bold = ui.optBoolean("Bold"),
                italic = ui.optBoolean("Italic"),
                underline = ui.optBoolean("Underline"),
                alignment = if (ui.has("PrAlign") && !ui.isNull("PrAlign")) Alignment.of(ui.getInt("PrAlign")) else null,
                canUndo = ui.optBoolean("CanUndo"),
                canRedo = ui.optBoolean("CanRedo")
            )
            return EditorState(
                cursor, areas("selection"), areas("drawings"), dirtyPages, root.getBoolean("layoutChanged"),
                root.getBoolean("layoutPending"), root.getInt("currentPage"), root.getBoolean("modified"), toolbar,
                root.optString("error").takeIf { it.isNotEmpty() && it != "null" },
                root.optJSONObject("sheet")?.let { parseSheet(it) }
            )
        }

        private fun parseSheet(json: JSONObject): SheetState {
            val sheetsJson = json.getJSONArray("sheets")
            val sheets = (0 until sheetsJson.length()).map {
                val sheet = sheetsJson.getJSONObject(it)
                Sheet(sheet.getString("name"), sheet.getBoolean("hidden"))
            }
            val geometry = json.optJSONObject("geometry")?.let { geometryJson ->
                fun bounds(name: String): DoubleArray {
                    val array = geometryJson.getJSONArray(name)
                    return DoubleArray(array.length()) { array.getDouble(it) }
                }
                SheetGeometry(bounds("cols"), bounds("rows"))
            }
            return SheetState(
                json.getInt("c1"), json.getInt("r1"), json.getInt("c2"), json.getInt("r2"),
                json.getInt("col"), json.getInt("row"), json.getString("name"), json.getString("text"),
                sheets, json.getInt("active"), geometry
            )
        }
    }
}
