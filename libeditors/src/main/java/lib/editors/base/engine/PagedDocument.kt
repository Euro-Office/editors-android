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
}

/** Result of [EditorEngine.openDrawingFile]. */
sealed interface DrawingFileResult {
    class Opened(val document: PagedDocument) : DrawingFileResult
    /** The file is encrypted: open it again with its password. */
    data object NeedsPassword : DrawingFileResult
    data object Failed : DrawingFileResult
}
