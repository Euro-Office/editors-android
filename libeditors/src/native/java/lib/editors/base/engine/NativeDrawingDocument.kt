package lib.editors.base.engine

import android.graphics.Bitmap

internal class NativeDrawingDocument(private var handle: Long) : PagedDocument {

    override val pageCount: Int
        get() = NativeEngine.nativeGetDrawingPageCount(handle)

    override fun pageSize(page: Int): PageSize {
        val size = NativeEngine.nativeGetDrawingPageSize(handle, page)
        return PageSize(size[0], size[1])
    }

    override fun renderTile(page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, bitmap: Bitmap): Boolean =
        NativeEngine.nativeRenderDrawingTile(handle, page, pageWidth, pageHeight, x, y, bitmap)

    override fun close() {
        if (handle != 0L) {
            NativeEngine.nativeDestroyDrawingDocument(handle)
            handle = 0L
        }
    }
}
