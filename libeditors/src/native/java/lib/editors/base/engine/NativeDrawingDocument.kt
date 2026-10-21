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

    override fun pageText(page: Int): PageText? {
        val result = NativeEngine.nativeGetDrawingPageText(handle, page) ?: return null
        return PageText(result[0] as String, result[1] as FloatArray)
    }

    override fun links(page: Int): List<PageLink> {
        val result = NativeEngine.nativeGetDrawingLinks(handle, page) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val urls = result[0] as Array<String>
        val areas = result[1] as FloatArray
        return urls.mapIndexed { i, url -> PageLink(url, areas[4 * i], areas[4 * i + 1], areas[4 * i + 2], areas[4 * i + 3]) }
    }

    override fun close() {
        if (handle != 0L) {
            NativeEngine.nativeDestroyDrawingDocument(handle)
            handle = 0L
        }
    }
}
