package lib.editors.base.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextOptionsTest {

    @Test
    fun parsesTheEngineOptions() {
        assertEquals(TextOptions(44, 2), TextOptions.parse("""{"encoding":44,"delimiter":2}"""))
        assertEquals(TextOptions(46, -1), TextOptions.parse("""{"encoding":46,"delimiter":-1}"""))
    }

    @Test
    fun otherFilesHaveNone() {
        assertNull(TextOptions.parse("null"))
        assertNull(TextOptions.parse(""))
    }

    @Test
    fun encodingsAreCoresInOrder() {
        assertEquals((0..53).toList(), TextEncoding.ALL.map { it.index })
        assertEquals("UTF-8", TextEncoding.ALL[46].charsetName)
    }

    @Test
    fun delimitersHaveX2tsValues() {
        assertEquals(CsvDelimiter.SEMICOLON, CsvDelimiter.of(2))
        assertNull(CsvDelimiter.of(-1))
    }
}
