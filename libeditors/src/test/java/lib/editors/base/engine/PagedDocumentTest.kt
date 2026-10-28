package lib.editors.base.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PagedDocumentTest {

    // "ab cd\nef": each character 2 mm wide, lines 5 mm high
    private val text = PageText(
        "ab cd\nef",
        floatArrayOf(
            0f, 0f, 2f, 5f, 2f, 0f, 4f, 5f, 4f, 0f, 6f, 5f, 6f, 0f, 8f, 5f, 8f, 0f, 10f, 5f,
            0f, 0f, 0f, 0f,
            0f, 6f, 2f, 11f, 2f, 6f, 4f, 11f
        )
    )

    private val document = object : PagedDocument {
        override val pageCount = 1
        override fun pageSize(page: Int) = PageSize(100.0, 100.0)
        override fun renderTile(page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, bitmap: android.graphics.Bitmap) = false
        override fun pageText(page: Int) = text
        override fun close() = Unit
    }

    @Test
    fun `text blocks are the lines of a page`() {
        assertEquals(
            listOf(TextBlock("ab cd", 0.0, 0.0, 10.0, 5.0), TextBlock("ef", 0.0, 6.0, 4.0, 11.0)),
            document.textBlocks(0)
        )
    }

    @Test
    fun `finds the character at a point, not a space`() {
        assertEquals(1, text.indexAt(3f, 2f))
        // On the space: the nearest character instead
        assertNotEquals(2, text.indexAt(5f, 2f))
        assertEquals(7, text.indexAt(3f, 9f))
    }
}
