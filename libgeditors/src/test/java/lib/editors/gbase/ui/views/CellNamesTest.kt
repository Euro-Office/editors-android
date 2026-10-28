package lib.editors.gbase.ui.views

import org.junit.Assert.assertEquals
import org.junit.Test

class CellNamesTest {

    @Test
    fun `names columns like spreadsheets do`() {
        assertEquals("A", columnName(0))
        assertEquals("Z", columnName(25))
        assertEquals("AA", columnName(26))
        assertEquals("AZ", columnName(51))
        assertEquals("XFD", columnName(16383))
    }

    @Test
    fun `references cells and ranges in either direction`() {
        assertEquals("B2", cellReference(1, 1))
        assertEquals("B2:D5", cellReference(1, 1, 3, 4))
        assertEquals("B2:D5", cellReference(3, 4, 1, 1))
    }
}
