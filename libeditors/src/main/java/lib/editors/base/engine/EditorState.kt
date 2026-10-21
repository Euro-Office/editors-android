package lib.editors.base.engine

import org.json.JSONObject

/** Cursor line on a page, in millimeters (slanted for rotated text). */
data class Cursor(val page: Int, val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/** Selected area on a page: four corners (x1, y1 ... x4, y4) in millimeters. */
class SelectionArea(val page: Int, val points: DoubleArray)

/**
 * A selected picture or shape: its frame (four corners, clockwise from the top left, in page
 * millimeters), where its rotation handle is (null if it can't rotate) and whether it can be
 * moved and resized. While it is dragged ([tracking]), the frame is where it would go.
 */
class DrawingFrame(
    val page: Int,
    val points: DoubleArray,
    val rotateHandle: DoubleArray?,
    val canMove: Boolean,
    val canResize: Boolean,
    val tracking: Boolean
)

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

/** Format of the active cell. Colors as "RRGGBB"; [verticalAlign] 0 bottom, 1 center, 4 top. */
data class CellFormat(
    val numFormat: String = "General",
    /** Asc.c_oAscNumFormatType: 0 general, 1 number, 4 currency, 5 date, 8 percent, 10 text, ... */
    val numFormatType: Int = 0,
    val fontName: String = "",
    val fontSize: Double = 0.0,
    val strikeout: Boolean = false,
    val color: String = "",
    val fill: String = "",
    val wrap: Boolean = false,
    val merged: Boolean = false,
    val verticalAlign: Int = 0,
    val border: Boolean = false
)

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
    /** Frames of all pictures, shapes and charts on the sheet (taps on them go to [EditorDocument.touchDrawing]). */
    val objects: List<DoubleArray>,
    /** Index of the active sheet. */
    val active: Int,
    val geometry: SheetGeometry?,
    /** Frozen panes: columns before [frozenCol] and rows before [frozenRow] stay in place (0: none). */
    val frozenCol: Int = 0,
    val frozenRow: Int = 0,
    /** sdkjs refused the last command with this error code (e.g. shifting cells of a table), or null. */
    val warning: Int? = null,
    val cellFormat: CellFormat = CellFormat()
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

/**
 * Formatting at the cursor or of the selection, for the formatting panel. Colors as "RRGGBB"
 * ("" for automatic or none); spacing in mm; [list] -1 none, 0 bullets, 1 numbers (documents);
 * [style] the paragraph style (documents).
 */
data class TextFormat(
    val fontName: String = "",
    val fontSize: Double = 0.0,
    val strikeout: Boolean = false,
    /** 0 baseline, 1 subscript, 2 superscript. */
    val baseline: Int = 0,
    val color: String = "",
    val highlight: String = "",
    /** 0 at least, 1 multiple, 2 exactly (see [EditorDocument.setLineSpacing]). */
    val lineRule: Int = 1,
    val line: Double = 1.0,
    val spacingBefore: Double = 0.0,
    val spacingAfter: Double = 0.0,
    val list: Int = -1,
    val style: String = ""
)

/** Find and replace: the number of matches, the current one (0-based, -1 for none) and, after replace all, how many were replaced (-1 otherwise). */
data class SearchState(val count: Int, val current: Int, val replaced: Int)

/** The hyperlink at the cursor or in the active cell: a web address ([url]) or a place in the document ([anchor]). */
data class Link(val url: String, val anchor: String)

/** A tracked change: [type] is Asc.c_oAscRevisionsChangeType (1 text added, 2 text removed, 3/4 paragraph added/removed, 5+ formatting). */
data class TrackedChange(val type: Int, val user: String, val date: String, val text: String)

/** Documents: where the cursor is (table, header or footer, footnote) and track changes. */
data class DocumentStructure(
    val inTable: Boolean = false,
    val inHeaderFooter: Boolean = false,
    val differentFirst: Boolean = false,
    val differentOddEven: Boolean = false,
    val inNote: Boolean = false,
    val trackChanges: Boolean = false,
    /** The change at the cursor, after going to the next or previous one. */
    val change: TrackedChange? = null
)

/** Page size, orientation and margins of the current section, in mm, and its columns. */
data class PageSetup(
    val width: Double, val height: Double, val landscape: Boolean,
    val left: Double, val top: Double, val right: Double, val bottom: Double,
    val columns: Int
)

/** A heading of the document outline, at [level] (0 for the top). */
data class OutlineItem(val index: Int, val text: String, val level: Int)

data class Statistics(val pages: Int, val words: Int, val paragraphs: Int, val symbols: Int, val symbolsWithSpaces: Int)

/**
 * How a slide appears in a slideshow: [type] is Asc.c_oAscSlideTransitionTypes (0 none, 1 fade,
 * 2 push, 3 wipe, 4 split, 5 uncover, 6 cover, 7 clock, 8 zoom, 9 morph), [option] its
 * Asc.c_oAscSlideTransitionParams (direction and so on); the next slide comes on a tap
 * ([onClick]) and/or after [afterMs] ([advanceAfter]).
 */
data class SlideTransition(
    val type: Int, val option: Int, val durationMs: Int,
    val onClick: Boolean, val advanceAfter: Boolean, val afterMs: Int
)

data class SlideInfo(val hidden: Boolean, val layout: String, val transition: SlideTransition?, val notes: String)

/** A slide layout; [index] is for [EditorDocument.addSlide] and [EditorDocument.setLayout]. */
data class SlideLayout(val index: Int, val name: String)

/**
 * The selected chart: [type] is Asc.c_oAscChartTypeSettings, [legend] Asc.c_oAscChartLegendShowSettings
 * (0 none, 1 left, 2 top, 3 right, 4 bottom). In spreadsheets the data is in cells ([dataInCells]).
 */
data class ChartState(
    val type: Int, val title: Boolean, val legend: Int, val labels: Boolean, val gridlines: Boolean, val dataInCells: Boolean
)

/** A chart's data: a value per category ([categories]) for each series ([series]); null for none. */
data class ChartData(val series: List<String>, val categories: List<String>, val values: List<List<Double?>>)

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
    /** Selected pictures and shapes. */
    val drawings: List<DrawingFrame>,
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
    val sheet: SheetState? = null,
    val format: TextFormat = TextFormat(),
    /** The search, while find and replace is open. */
    val search: SearchState? = null,
    val link: Link? = null,
    /** A web address to open, after following a link (reported once). */
    val openUrl: String? = null,
    /** Documents only. */
    val structure: DocumentStructure? = null,
    val chart: ChartState? = null
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
                cursor, areas("selection"), drawings(root.getJSONArray("drawings")), dirtyPages, root.getBoolean("layoutChanged"),
                root.getBoolean("layoutPending"), root.getInt("currentPage"), root.getBoolean("modified"), toolbar,
                root.optString("error").takeIf { it.isNotEmpty() && it != "null" },
                root.optJSONObject("sheet")?.let { parseSheet(it) },
                root.optJSONObject("format")?.let { parseFormat(it) } ?: TextFormat(),
                root.optJSONObject("search")?.let { SearchState(it.optInt("count"), it.optInt("current", -1), it.optInt("replaced", -1)) },
                root.optJSONObject("link")?.let { Link(it.optString("url"), it.optString("anchor")) },
                root.optString("openUrl").takeIf { it.isNotEmpty() && it != "null" },
                root.optJSONObject("structure")?.let { parseStructure(it) },
                root.optJSONObject("chart")?.let {
                    ChartState(
                        it.optInt("type"), it.optBoolean("title"), it.optInt("legend"), it.optBoolean("labels"),
                        it.optBoolean("gridlines"), it.optBoolean("dataInCells")
                    )
                }
            )
        }

        private fun parseStructure(json: JSONObject) = DocumentStructure(
            inTable = json.optBoolean("inTable"),
            inHeaderFooter = json.optBoolean("inHeaderFooter"),
            differentFirst = json.optBoolean("differentFirst"),
            differentOddEven = json.optBoolean("differentOddEven"),
            inNote = json.optBoolean("inNote"),
            trackChanges = json.optBoolean("trackChanges"),
            change = json.optJSONObject("change")?.let {
                TrackedChange(it.optInt("type"), it.optString("user"), it.optString("date"), it.optString("text"))
            }
        )

        private fun parseFormat(json: JSONObject) = TextFormat(
            fontName = json.optString("FontName"),
            fontSize = json.optDouble("FontSize", 0.0),
            strikeout = json.optBoolean("Strikeout"),
            baseline = json.optInt("VertAlign"),
            color = json.optString("Color"),
            highlight = json.optString("Highlight"),
            lineRule = json.optInt("LineRule", 1),
            line = json.optDouble("Line", 1.0),
            spacingBefore = json.optDouble("SpacingBefore", 0.0),
            spacingAfter = json.optDouble("SpacingAfter", 0.0),
            list = json.optInt("List", -1),
            style = json.optString("Style")
        )



        private fun doubles(array: org.json.JSONArray): DoubleArray = DoubleArray(array.length()) { array.getDouble(it) }

        private fun drawings(json: org.json.JSONArray): List<DrawingFrame> = (0 until json.length()).map { index ->
            val drawing = json.getJSONObject(index)
            DrawingFrame(
                drawing.getInt("page"),
                doubles(drawing.getJSONArray("points")),
                drawing.optJSONArray("rotate")?.let { doubles(it) },
                drawing.optBoolean("canMove", true),
                drawing.optBoolean("canResize", true),
                drawing.optBoolean("tracking")
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
            val objectsJson = json.optJSONArray("objects") ?: org.json.JSONArray()
            val objects = (0 until objectsJson.length()).map { doubles(objectsJson.getJSONArray(it)) }
            return SheetState(
                json.getInt("c1"), json.getInt("r1"), json.getInt("c2"), json.getInt("r2"),
                json.getInt("col"), json.getInt("row"), json.getString("name"), json.getString("text"),
                sheets, objects, json.getInt("active"), geometry,
                json.optInt("frozenCol"), json.optInt("frozenRow"),
                if (json.isNull("warning") || !json.has("warning")) null else json.getInt("warning"),
                json.optJSONObject("cellFormat")?.let {
                    CellFormat(
                        it.optString("numFormat", "General"), it.optInt("numFormatType"), it.optString("fontName"),
                        it.optDouble("fontSize", 0.0), it.optBoolean("strikeout"), it.optString("color"), it.optString("fill"),
                        it.optBoolean("wrap"), it.optBoolean("merged"), it.optInt("verticalAlign"), it.optBoolean("border")
                    )
                } ?: CellFormat()
            )
        }
    }
}
