package lib.editors.base.engine

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.Closeable

/** Page size in millimeters. */
data class PageSize(val widthMm: Double, val heightMm: Double)

/**
 * A document open in the editor engine. Not thread-safe: call it on [EditorEngine.dispatcher] only.
 *
 * Editing commands return the editor's state afterwards (see engine.js in editors-android/engine).
 */
interface EditorDocument : Closeable {

    val pageCount: Int

    fun pageSize(page: Int): PageSize

    /**
     * Draws the area at ([x], [y]) of [page] rendered at [pageWidth] x [pageHeight] pixels into
     * [bitmap] (ARGB_8888), whose size is the size of the area.
     */
    fun renderTile(page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, bitmap: Bitmap): Boolean

    /**
     * Saves the document with its edits to [path] in the format of [extension] ("docx", "odt",
     * "pdf", ...; see [WRITABLE_FORMATS]). Returns 0, the converter's error code or -1 for a
     * format the engine can't write. Editing continues afterwards.
     */
    fun save(path: String, extension: String): Int

    /** Runs JavaScript in sdkjs and returns its result if it is a string. */
    fun execute(script: String): String

    fun state(): EditorState = EditorState.parse(execute("eoEngine.getState()"))

    /** Touch at page coordinates in millimeters; [clickCount] 2 selects a word. */
    fun touch(action: TouchAction, page: Int, xMm: Double, yMm: Double, clickCount: Int = 1): EditorState =
        command("eoEngine.mouse(${action.value}, $page, $xMm, $yMm, $clickCount)")

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

    fun undo(): EditorState = command("eoEngine.undo()")

    fun redo(): EditorState = command("eoEngine.redo()")

    private fun command(script: String): EditorState =
        EditorState.parse(execute("try { $script; } catch (e) { eoEngine.reportError(e); } eoEngine.getState()"))
}

/** Copied content: [internal] is empty when the selection can't be copied in sdkjs' format. */
data class ClipboardContent(val text: String, val internal: String)

/** Formats documents can be saved in, by extension; others (doc, ...) can only be opened. */
val WRITABLE_FORMATS = setOf(
    "docx", "docm", "dotx", "dotm", "odt", "ott", "rtf", "txt", "html", "epub", "fb2",
    "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "odp", "otp",
    "pdf"
)

enum class TouchAction(val value: Int) { DOWN(0), MOVE(1), UP(2) }
