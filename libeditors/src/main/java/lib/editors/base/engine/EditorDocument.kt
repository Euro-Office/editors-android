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

    /** Runs JavaScript in sdkjs and returns its result if it is a string. */
    fun execute(script: String): String

    fun state(): EditorState = EditorState.parse(execute("eoEngine.getState()"))

    /** Touch at page coordinates in millimeters; [clickCount] 2 selects a word. */
    fun touch(action: TouchAction, page: Int, xMm: Double, yMm: Double, clickCount: Int = 1): EditorState =
        command("eoEngine.mouse(${action.value}, $page, $xMm, $yMm, $clickCount)")

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

    fun undo(): EditorState = command("eoEngine.undo()")

    fun redo(): EditorState = command("eoEngine.redo()")

    private fun command(script: String): EditorState = EditorState.parse(execute("$script; eoEngine.getState()"))
}

enum class TouchAction(val value: Int) { DOWN(0), MOVE(1), UP(2) }
