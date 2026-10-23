package lib.editors.base.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateTest {

    // The fields every state of the engine has (engine.js getState)
    private fun state(extra: String = "") = """
        {"cursor": {"page": 1, "x1": 10, "y1": 20, "x2": 10, "y2": 25},
         "selection": [{"page": 0, "points": [0, 0, 5, 0, 5, 5, 0, 5]}],
         "drawings": [], "dirtyPages": {"0": true, "2": true}, "layoutChanged": false,
         "layoutPending": true, "currentPage": -1, "modified": true,
         "interface": {"Bold": true, "PrAlign": 2, "CanUndo": true}, "error": null$extra}
    """.trimIndent()

    @Test
    fun `parses cursor, selection, pages and toolbar`() {
        val parsed = EditorState.parse(state())
        assertEquals(Cursor(1, 10.0, 20.0, 10.0, 25.0), parsed.cursor)
        assertEquals(1, parsed.selection.size)
        assertEquals(setOf(0, 2), parsed.dirtyPages)
        assertTrue(parsed.layoutPending)
        assertTrue(parsed.modified)
        assertTrue(parsed.toolbar.bold)
        assertFalse(parsed.toolbar.italic)
        assertTrue(parsed.toolbar.canUndo)
        assertNull(parsed.error)
        assertNull(parsed.sheet)
    }

    @Test
    fun `parses spelling`() {
        val parsed = EditorState.parse(state(""", "spelling": {"pending": true, "missing": [1031, 1036], "word": {"word": "wrng", "variants": ["wrong"]}}"""))
        val spelling = parsed.spelling!!
        assertTrue(spelling.pending)
        assertEquals(listOf(1031, 1036), spelling.missing)
        assertEquals(Misspelling("wrng", listOf("wrong")), spelling.word)
    }

    @Test
    fun `variants are null while suggestions are looked up`() {
        val parsed = EditorState.parse(state(""", "spelling": {"pending": false, "missing": [], "word": {"word": "wrng", "variants": null}}"""))
        assertNull(parsed.spelling!!.word!!.variants)
    }

    @Test
    fun `parses the search and links`() {
        val parsed = EditorState.parse(state(""", "search": {"count": 3, "current": 1}, "link": {"url": "https://example.org", "anchor": ""}"""))
        assertEquals(SearchState(3, 1, -1), parsed.search)
        assertEquals("https://example.org", parsed.link!!.url)
    }

    @Test
    fun `parses the co-editing session`() {
        val parsed = EditorState.parse(state(""", "coEditing": {"state": 2, "reconnecting": true, "disconnected": false,
            "participants": [{"id": "u1", "name": "Ann", "color": "#a273e5", "view": false, "me": true},
                             {"id": "u2", "name": "Ben", "color": "#3367d6", "view": false, "me": false},
                             {"id": "u3", "name": "Cy", "color": "#55aa00", "view": true, "me": false}],
            "cursors": [{"user": "u2", "name": "Ben", "color": "#3367d6", "page": 0, "x1": 15, "y1": 36, "x2": 15, "y2": 44}]}"""))
        val session = parsed.coEditing!!
        assertTrue(session.reconnecting)
        assertFalse(session.disconnected)
        assertEquals(3, session.participants.size)
        assertEquals(listOf("Ben"), session.coEditors.map { it.name })
        assertEquals(ForeignCursor("u2", "Ben", "#3367d6", 0, 15.0, 36.0, 15.0, 44.0), session.cursors.single())
        assertTrue(session.selections.isEmpty())
    }

    @Test
    fun `parses others' selections in spreadsheets`() {
        val parsed = EditorState.parse(state(""", "coEditing": {"state": 2, "participants": [], "cursors": [],
            "selections": [{"user": "u2", "name": "Ben", "color": "#3367d6", "editing": true, "ranges": [[1, 2, 3, 4], [5, 5, 5, 5]]}]}"""))
        assertEquals(
            ForeignSelection("u2", "Ben", "#3367d6", true, listOf(CellRange(1, 2, 3, 4), CellRange(5, 5, 5, 5))),
            parsed.coEditing!!.selections.single()
        )
    }

    @Test
    fun `parses the PDF editor's state`() {
        val parsed = EditorState.parse(state(""", "pdf": {"ink": true, "annotation": {"id": "12", "type": 8}, "textSelected": false},
            "navigate": {"page": 3, "y": 120.5}"""))
        assertEquals(PdfState(ink = true, annotation = 8, textSelected = false), parsed.pdf)
        assertEquals(Place(3, 120.5), parsed.navigate)
        val textBox = EditorState.parse(state(""", "pdf": {"ink": false, "annotation": null, "textSelected": false, "textBox": true}"""))
        assertEquals(PdfState(ink = false, annotation = null, textSelected = false, textBox = true), textBox.pdf)
        assertNull(EditorState.parse(state("")).pdf)
    }

    @Test
    fun `no session without co-editing`() {
        assertNull(EditorState.parse(state(""", "coEditing": null""")).coEditing)
    }

    @Test(expected = org.json.JSONException::class)
    fun `fails for something that isn't a state`() {
        EditorState.parse("undefined")
    }
}
