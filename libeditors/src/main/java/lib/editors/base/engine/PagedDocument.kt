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

    /**
     * The text of [page] in blocks with their areas, for screen readers: paragraphs, the text of
     * shapes; lines for PDF, XPS and DjVu files.
     */
    fun textBlocks(page: Int): List<TextBlock> {
        val text = pageText(page) ?: return emptyList()
        val blocks = mutableListOf<TextBlock>()
        var start = 0
        for (end in 0..text.text.length) {
            if (end < text.text.length && text.text[end] != '\n') continue
            val line = (start until end).filter { text.right(it) > text.left(it) }
            if (line.isNotEmpty() && text.text.substring(start, end).isNotBlank()) {
                blocks += TextBlock(
                    text.text.substring(start, end).trim(),
                    line.minOf { text.left(it) }.toDouble(), line.minOf { text.top(it) }.toDouble(),
                    line.maxOf { text.right(it) }.toDouble(), line.maxOf { text.bottom(it) }.toDouble()
                )
            }
            start = end + 1
        }
        return blocks
    }

    /** Links on [page] (PDF files): web addresses and places in the file. */
    fun links(page: Int): List<PageLink> = emptyList()

    /** The file's outline (bookmarks), in order. */
    fun fileOutline(): List<PageOutlineItem> = emptyList()

    /**
     * PDF files: saves to [path] with the markups and pictures added (an incremental update).
     * The document is closed then; open the saved file to go on. 0 or an error.
     */
    fun saveWithChanges(path: String, markups: List<PdfMarkup>, images: List<PdfImage>, author: String): Int = -1
}

/** Text on a page and its area, in mm from the page's top left. */
data class TextBlock(val text: String, val left: Double, val top: Double, val right: Double, val bottom: Double)

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

/**
 * Text marked up on a PDF page: [type] 8 highlight, 9 underline, 11 strikeout; [quads] four
 * corners each (top left, top right, bottom left, bottom right as x, y in mm from the page's top
 * left); [color] as red, green, blue from 0 to 1.
 */
class PdfMarkup(val page: Int, val type: Int, val color: FloatArray, val quads: FloatArray)

/** A picture (PNG file) put on a PDF page, such as a drawn signature: its area in mm from the top left. */
class PdfImage(val page: Int, val path: String, val x: Float, val y: Float, val width: Float, val height: Float)

/** An outline entry of a PDF, XPS or DjVu file: [level] 1 for the top; the place is [page] and [yMm] from its top. */
data class PageOutlineItem(val title: String, val level: Int, val page: Int, val yMm: Float)

/**
 * A link on a page, with its area in mm from the page's top left: a web address ([url]) or a
 * place in the file ([destPage] >= 0, [destY] mm from its top).
 */
class PageLink(
    val url: String, val x: Float, val y: Float, val width: Float, val height: Float,
    val destPage: Int = -1, val destY: Float = 0f
) {
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
