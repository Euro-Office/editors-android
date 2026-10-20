package lib.editors.base.engine

import android.graphics.Bitmap
import java.io.Closeable

/** Page size in millimeters. */
data class PageSize(val widthMm: Double, val heightMm: Double)

/**
 * A document open in the editor engine. Not thread-safe: call it on [EditorEngine.dispatcher] only.
 */
interface EditorDocument : Closeable {

    val pageCount: Int

    fun pageSize(page: Int): PageSize

    /**
     * Draws the area at ([x], [y]) of [page] rendered at [pageWidth] x [pageHeight] pixels into
     * [bitmap] (ARGB_8888), whose size is the size of the area.
     */
    fun renderTile(page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, bitmap: Bitmap): Boolean
}
