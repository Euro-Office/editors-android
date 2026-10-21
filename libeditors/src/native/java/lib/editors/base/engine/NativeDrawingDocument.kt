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
        return urls.mapIndexed { i, url ->
            PageLink(url, areas[6 * i], areas[6 * i + 1], areas[6 * i + 2], areas[6 * i + 3], areas[6 * i + 4].toInt(), areas[6 * i + 5])
        }
    }

    override fun fileOutline(): List<PageOutlineItem> {
        val result = NativeEngine.nativeGetDrawingOutline(handle) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val titles = result[0] as Array<String>
        val values = result[1] as FloatArray
        return titles.mapIndexed { i, title -> PageOutlineItem(title, values[3 * i].toInt(), values[3 * i + 1].toInt(), values[3 * i + 2]) }
    }

    override fun saveWithChanges(path: String, markups: List<PdfMarkup>, images: List<PdfImage>, author: String): Int {
        val ints = markups.flatMap { listOf(it.page, it.type, it.quads.size / 8) }.toIntArray()
        val floats = markups.flatMap { it.color.toList() + it.quads.toList() }.toFloatArray()
        val result = NativeEngine.nativeSaveDrawingWithChanges(
            handle, path, ints, floats, author,
            images.map { it.page }.toIntArray(), images.map { it.path }.toTypedArray(),
            images.flatMap { listOf(it.x, it.y, it.width, it.height) }.toFloatArray()
        )
        // Closed by saving
        close()
        return result
    }

    override fun close() {
        if (handle != 0L) {
            NativeEngine.nativeDestroyDrawingDocument(handle)
            handle = 0L
        }
    }
}
