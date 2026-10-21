package lib.editors.base.engine

import org.json.JSONObject

/** Page size in millimeters. */
data class PageSize(val widthMm: Double, val heightMm: Double)

/**
 * A document open in the editor engine. Not thread-safe: call it on [EditorEngine.dispatcher] only.
 *
 * Editing commands return the editor's state afterwards (see engine.js in editors-android/engine).
 */
interface EditorDocument : PagedDocument {

    /**
     * Saves the document with its edits to [path] in the format of [extension] ("docx", "odt",
     * "pdf", ...; see [WRITABLE_FORMATS]). Returns 0, the converter's error code or -1 for a
     * format the engine can't write. Editing continues afterwards.
     */
    fun save(path: String, extension: String): Int

    /** Runs JavaScript in sdkjs and returns its result if it is a string. */
    fun execute(script: String): String

    fun state(): EditorState = EditorState.parse(execute("eoEngine.getState()"))

    /**
     * Touch at page coordinates in millimeters; [clickCount] 2 selects a word. Pictures and shapes
     * are selected, moved, resized and rotated by touching them and their handles (see
     * [EditorState.drawings]); [addToSelection] adds one to the selected ones, like Ctrl+click.
     */
    fun touch(action: TouchAction, page: Int, xMm: Double, yMm: Double, clickCount: Int = 1, addToSelection: Boolean = false): EditorState =
        command("eoEngine.mouse(${action.value}, $page, $xMm, $yMm, $clickCount, $addToSelection)")

    /** Spreadsheets: touch on pictures, shapes and charts, at sheet millimeters (see [touch]). */
    fun touchDrawing(action: TouchAction, xMm: Double, yMm: Double, clickCount: Int = 1, addToSelection: Boolean = false): EditorState =
        command("eoEngine.mouseDrawing(${action.value}, $xMm, $yMm, $clickCount, $addToSelection)")

    /** Directory that pictures to insert are copied into (see [insertImage]). */
    fun imagesDirectory(): String = execute("eoEngine.getImagesDirectory()")

    /** Inserts the picture [name] in [imagesDirectory], [width] x [height] pixels, at the cursor. */
    fun insertImage(name: String, width: Int, height: Int): EditorState =
        command("eoEngine.insertImage(${JSONObject.quote(name)}, $width, $height)")

    /** Inserts a shape by its preset name ("rect", "ellipse", "rightArrow", "line", ...), selected. */
    fun insertShape(preset: String): EditorState = command("eoEngine.insertShape(${JSONObject.quote(preset)})")

    /** Fill of the selected shapes as "RRGGBB"; null for none. */
    fun setShapeFill(color: String?): EditorState = command("eoEngine.setShapeFill(${JSONObject.quote(color.orEmpty())})")

    /** Outline of the selected shapes as "RRGGBB" ([color] null for none), [widthMm] wide. */
    fun setShapeOutline(color: String?, widthMm: Double = 0.75): EditorState =
        command("eoEngine.setShapeOutline(${JSONObject.quote(color.orEmpty())}, $widthMm)")

    /** Z-order of the selected pictures and shapes. */
    fun arrange(order: DrawingOrder): EditorState = command("eoEngine.arrange(${order.value})")

    /** Groups the selected pictures and shapes, or ungroups the selected group. */
    fun group(group: Boolean): EditorState = command("eoEngine.group($group)")

    /** Selects from the start to the end point, in page millimeters (selection handles). */
    fun select(startPage: Int, startXMm: Double, startYMm: Double, endPage: Int, endXMm: Double, endYMm: Double): EditorState =
        command("eoEngine.select($startPage, $startXMm, $startYMm, $endPage, $endXMm, $endYMm)")

    /** Key with its web key code (8 backspace, 13 enter, 37-40 arrows, 46 delete). */
    fun keyDown(keyCode: Int, shift: Boolean = false, ctrl: Boolean = false): EditorState =
        command("eoEngine.keyDown($keyCode, $shift, $ctrl)")

    fun enterText(text: String): EditorState = command("eoEngine.enterText(${JSONObject.quote(text)})")

    /** Text from the keyboard: replaces the text being composed, if any. */
    fun commitText(text: String): EditorState = command("eoEngine.commitText(${JSONObject.quote(text)})")

    /** Text the keyboard is still composing; replaced by the next call until [finishComposingText]. */
    fun setComposingText(text: String): EditorState = command("eoEngine.setComposingText(${JSONObject.quote(text)})")

    fun finishComposingText(): EditorState = command("eoEngine.finishComposingText()")

    fun setBold(value: Boolean): EditorState = command("eoEngine.setBold($value)")

    fun setItalic(value: Boolean): EditorState = command("eoEngine.setItalic($value)")

    fun setUnderline(value: Boolean): EditorState = command("eoEngine.setUnderline($value)")

    fun setAlignment(alignment: Alignment): EditorState = command("eoEngine.setAlign(${alignment.value})")

    /** The selection as plain text and in sdkjs' internal format (keeps formatting). */
    fun copy(): ClipboardContent = JSONObject(execute("eoEngine.copy()")).let {
        ClipboardContent(it.getString("text"), it.getString("internal"))
    }

    fun cut(): EditorState = command("eoEngine.cut()")

    /** Pastes [internal] if given (from [copy] in this app), [text] otherwise. */
    fun paste(text: String, internal: String? = null): EditorState =
        command("eoEngine.paste(${JSONObject.quote(text)}, ${JSONObject.quote(internal.orEmpty())})")

    fun selectAll(): EditorState = command("eoEngine.selectAll()")

    /**
     * Continues work sdkjs left for later, like laying out the pages after the first ones,
     * for up to [budgetMs]. Commands between ticks run without waiting for the whole layout.
     */
    fun tick(budgetMs: Int): EditorState = command("eoEngine.tick($budgetMs)")

    /** Presentations: adds a slide after the current one, with its layout, and goes to it. */
    fun addSlide(): EditorState = command("eoEngine.addSlide()")

    /** Presentations: deletes the current slide. */
    fun deleteSlide(): EditorState = command("eoEngine.deleteSlide()")

    /**
     * Spreadsheets: selects the cells from ([c1], [r1]) to ([c2], [r2]); ([c1], [r1]) is the
     * active cell. [enterText] is entered into the active cell first, if given (see [setCellText]).
     */
    fun selectCells(c1: Int, r1: Int, c2: Int, r2: Int, enterText: String? = null): EditorState =
        command("${enterScript(enterText)}eoEngine.selectCells($c1, $r1, $c2, $r2)")

    /** Spreadsheets: the active cell's new content (a value or a formula starting with "="); empty clears it. */
    fun setCellText(text: String): EditorState = command(enterScript(text))

    /** Spreadsheets: clears the content of the selected cells. */
    fun clearCells(): EditorState = command("eoEngine.clearCells()")

    /** Spreadsheets: shows another sheet, after entering [enterText] into the active cell, if given. */
    fun setActiveSheet(index: Int, enterText: String? = null): EditorState =
        command("${enterScript(enterText)}eoEngine.setActiveSheet($index)")

    // ---------------------------------------------------------------------------------
    // Text and paragraph formatting (state: EditorState.format)

    /** Font names the engine has, sorted. */
    fun fonts(): List<String> = jsonStrings(execute("eoEngine.getFonts()"))

    /** Text documents: paragraph style names. */
    fun styles(): List<String> = jsonStrings(execute("eoEngine.getStyles()"))

    fun setFontName(name: String): EditorState = asyncCommand("eoEngine.setFontName(${JSONObject.quote(name)})")

    fun setFontSize(points: Double): EditorState = command("eoEngine.setFontSize($points)")

    fun setStrikeout(value: Boolean): EditorState = command("eoEngine.setStrikeout($value)")

    /** 0 baseline, 1 subscript, 2 superscript. */
    fun setBaseline(value: Int): EditorState = command("eoEngine.setBaseline($value)")

    /** Text color as "RRGGBB"; null for automatic. */
    fun setTextColor(color: String?): EditorState = command("eoEngine.setTextColor(${JSONObject.quote(color.orEmpty())})")

    /** Highlight as "RRGGBB"; null for none. */
    fun setHighlight(color: String?): EditorState = command("eoEngine.setHighlight(${JSONObject.quote(color.orEmpty())})")

    fun clearFormatting(): EditorState = command("eoEngine.clearFormatting()")

    /**
     * Lists: [type] 0 bullets, 1 numbers; [subtype] for bullets 1 dot, 2 circle, 3 square; for
     * numbers 1 "1.", 2 "1)", 3 "I.", 4 "A.", 5 "a)", 6 "a.", 7 "i."; -1 removes the list.
     */
    fun setList(type: Int, subtype: Int): EditorState = asyncCommand("eoEngine.setList($type, $subtype)")

    fun indent(increase: Boolean): EditorState = command("eoEngine.indent($increase)")

    /** [rule] 0 at least, 1 multiple ([value] e.g. 1.15), 2 exactly ([value] in mm). */
    fun setLineSpacing(rule: Int, value: Double): EditorState = command("eoEngine.setLineSpacing($rule, $value)")

    /** Space before and after paragraphs in mm. */
    fun setParagraphSpacing(beforeMm: Double, afterMm: Double): EditorState =
        command("eoEngine.setParagraphSpacing($beforeMm, $afterMm)")

    /** Text documents: applies a paragraph style by name (see [styles]). */
    fun setStyle(name: String): EditorState = asyncCommand("eoEngine.setStyle(${JSONObject.quote(name)})")

    // ---------------------------------------------------------------------------------
    // Spreadsheets: cell formats, rows and columns, sheets (state: EditorState.sheet)

    /** A cell fill as "RRGGBB"; null for none. */
    fun setCellFill(color: String?): EditorState = command("eoEngine.setCellFill(${JSONObject.quote(color.orEmpty())})")

    /** A number format code: "General", "0.00", "0.00%", "#,##0.00 €", "dd.mm.yyyy", "@" (text). */
    fun setNumberFormat(format: String): EditorState = command("eoEngine.setNumberFormat(${JSONObject.quote(format)})")

    /** A number format by type, for the document's locale: 0 general, 1 number, 4 currency, 5 date, 7 time, 8 percent, 10 text. */
    fun setNumberFormatType(type: Int): EditorState = command("eoEngine.setNumberFormatType($type)")

    fun changeDecimals(more: Boolean): EditorState = command("eoEngine.changeDecimals($more)")

    /** Borders of the selected cells: "all", "outer", "bottom" or "none". */
    fun setBorders(kind: String): EditorState = command("eoEngine.setBorders(${JSONObject.quote(kind)})")

    fun setWrap(value: Boolean): EditorState = command("eoEngine.setWrap($value)")

    fun setMerge(value: Boolean): EditorState = command("eoEngine.setMerge($value)")

    /** 0 bottom, 1 center, 4 top. */
    fun setVerticalAlign(value: Int): EditorState = command("eoEngine.setVerticalAlign($value)")

    fun selectRows(r1: Int, r2: Int, enterText: String? = null): EditorState = command("${enterScript(enterText)}eoEngine.selectRows($r1, $r2)")

    fun selectColumns(c1: Int, c2: Int, enterText: String? = null): EditorState =
        command("${enterScript(enterText)}eoEngine.selectColumns($c1, $c2)")

    /** Inserts at the selection: 1 cells shifted right, 2 cells shifted down, 3 columns, 4 rows. */
    fun insertCells(option: Int): EditorState = command("eoEngine.insertCells($option)")

    /** Deletes the selection: 1 cells shifted left, 2 cells shifted up, 3 columns, 4 rows. */
    fun deleteCells(option: Int): EditorState = command("eoEngine.deleteCells($option)")

    fun hideRows(hide: Boolean): EditorState = command("eoEngine.hideRows($hide)")

    fun hideColumns(hide: Boolean): EditorState = command("eoEngine.hideColumns($hide)")

    fun autoFitColumns(): EditorState = command("eoEngine.autoFitColumns()")

    fun setColumnWidth(mm: Double): EditorState = command("eoEngine.setColumnWidth($mm)")

    fun setRowHeight(mm: Double): EditorState = command("eoEngine.setRowHeight($mm)")

    /** Freezes the columns before [col] and the rows before [row]; 0, 0 unfreezes. */
    fun freezePanes(col: Int, row: Int): EditorState = command("eoEngine.freezePanes($col, $row)")

    /** Fills the selection from its filled cells, as dragging Excel's fill handle does. */
    fun fillSelection(): EditorState = command("eoEngine.fillSelection()")

    fun addSheet(name: String): EditorState = command("eoEngine.addSheet(${JSONObject.quote(name)})")

    fun renameSheet(name: String): EditorState = command("eoEngine.renameSheet(${JSONObject.quote(name)})")

    fun deleteSheet(): EditorState = command("eoEngine.deleteSheet()")

    /** Moves the active sheet to before the sheet at [index]. */
    fun moveSheet(index: Int): EditorState = command("eoEngine.moveSheet($index)")

    fun hideSheet(): EditorState = command("eoEngine.hideSheet()")

    fun showSheet(index: Int): EditorState = command("eoEngine.showSheet($index)")

    /** Spreadsheet function names, for autocomplete. */
    fun functions(): List<String> = jsonStrings(execute("eoEngine.getFunctions()"))

    fun undo(): EditorState = command("eoEngine.undo()")

    fun redo(): EditorState = command("eoEngine.redo()")

    // One command, so that the state reports the change of the entered cell too
    private fun enterScript(text: String?): String = when {
        text == null -> ""
        text.isEmpty() -> "eoEngine.clearCells(); "
        else -> "eoEngine.setCellText(${JSONObject.quote(text)}); "
    }

    // Commands sdkjs finishes in a promise (lists, styles, loading fonts): the state is read
    // in a second script, after V8 has run the promise
    private fun asyncCommand(script: String): EditorState {
        execute("try { $script; } catch (e) { eoEngine.reportError(e); }")
        return state()
    }

    private fun jsonStrings(json: String): List<String> =
        runCatching { org.json.JSONArray(json).let { array -> List(array.length()) { array.getString(it) } } }.getOrDefault(emptyList())

    private fun command(script: String): EditorState =
        EditorState.parse(execute("try { $script; } catch (e) { eoEngine.reportError(e); } eoEngine.getState()"))
}

/** Copied content: [internal] is empty when the selection can't be copied in sdkjs' format. */
data class ClipboardContent(val text: String, val internal: String)

/** Formats documents can be saved in, by extension; others (doc, ...) can only be opened. */
val WRITABLE_FORMATS = setOf(
    "docx", "docm", "dotx", "dotm", "odt", "ott", "rtf", "txt", "html", "epub", "fb2",
    "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "odp", "otp",
    "xlsx", "xlsm", "xltx", "xltm", "ods", "ots", "csv",
    "pdf"
)

/** Z-order changes, with sdkjs' values (c_oAscDrawingLayerType). */
enum class DrawingOrder(val value: Int) { TO_FRONT(0), FORWARD(1), TO_BACK(2), BACKWARD(3) }

enum class TouchAction(val value: Int) { DOWN(0), MOVE(1), UP(2) }
