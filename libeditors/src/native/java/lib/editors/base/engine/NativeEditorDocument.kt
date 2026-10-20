package lib.editors.base.engine

import android.graphics.Bitmap

internal class NativeEditorDocument(private var handle: Long) : EditorDocument {

    override val pageCount: Int
        get() = NativeEngine.nativeGetPageCount(handle)

    override fun pageSize(page: Int): PageSize {
        val size = NativeEngine.nativeGetPageSize(handle, page)
        return PageSize(size[0], size[1])
    }

    override fun renderTile(page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, bitmap: Bitmap): Boolean =
        NativeEngine.nativeRenderTile(handle, page, pageWidth, pageHeight, x, y, bitmap)

    override fun close() {
        if (handle != 0L) {
            NativeEngine.nativeDestroyDocument(handle)
            handle = 0L
        }
    }
}
