package lib.editors.base.engine

import android.graphics.Bitmap
import java.io.Closeable

/** A document shown as pages: documents and presentations ([EditorDocument]) and PDF, XPS and DjVu files. */
interface PagedDocument : Closeable {

    val pageCount: Int

    fun pageSize(page: Int): PageSize

    /**
     * Draws the area at ([x], [y]) of [page] rendered at [pageWidth] x [pageHeight] pixels into
     * [bitmap] (ARGB_8888), whose size is the size of the area.
     */
    fun renderTile(page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, bitmap: Bitmap): Boolean

    /** The text of [page] (PDF, XPS and DjVu files), or null if there is none. */
    fun pageText(page: Int): PageText? = null

    /** Web addresses in the text of [page] (PDF files). */
    fun links(page: Int): List<PageLink> = emptyList()
}

/**
 * The text of a page in reading order, with spaces and line breaks between its words and lines.
 * [boxes] has the area of each character (left, top, right, bottom in mm from the page's top left).
 */
class PageText(val text: String, val boxes: FloatArray) {
    fun left(index: Int) = boxes[4 * index]
    fun top(index: Int) = boxes[4 * index + 1]
    fun right(index: Int) = boxes[4 * index + 2]
    fun bottom(index: Int) = boxes[4 * index + 3]

    /** The character (not a space) at the point (mm), or the nearest one; -1 if there is none. */
    fun indexAt(x: Float, y: Float): Int {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in text.indices) {
            if (right(i) <= left(i) || text[i].isWhitespace()) continue
            val dx = when {
                x < left(i) -> left(i) - x
                x > right(i) -> x - right(i)
                else -> 0f
            }
            val dy = when {
                y < top(i) -> top(i) - y
                y > bottom(i) -> y - bottom(i)
                else -> 0f
            }
            // Lines count more than the distance along them
            val distance = dx + 4 * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }
}

/** A web address in a page's text, with its area in mm from the page's top left. */
class PageLink(val url: String, val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float) = px >= x && px <= x + width && py >= y && py <= y + height
}

/** Result of [EditorEngine.openDocument]. */
sealed interface DocumentResult {
    class Opened(val document: EditorDocument) : DocumentResult
    /** The file is encrypted, or the password was wrong: open it again with the password. */
    data object NeedsPassword : DocumentResult
    data object Failed : DocumentResult
}

/** Result of [EditorEngine.openDrawingFile]. */
sealed interface DrawingFileResult {
    class Opened(val document: PagedDocument) : DrawingFileResult
    /** The file is encrypted: open it again with its password. */
    data object NeedsPassword : DrawingFileResult
    data object Failed : DrawingFileResult
}
