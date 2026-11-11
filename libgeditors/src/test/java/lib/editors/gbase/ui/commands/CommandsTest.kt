package lib.editors.gbase.ui.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandsTest {

    private fun tab(id: String, contextual: Boolean = false) = CommandTab(id, 0, emptyList(), contextual)

    @Test
    fun `puts the tabs for what is selected first`() {
        val tabs = listOf(tab("home"), tab("insert"), tab("table", contextual = true))
        assertEquals(listOf("table", "home", "insert"), tabs.ordered().map { it.id })
    }

    @Test
    fun `keeps the tab shown while it is there`() {
        val tabs = listOf(tab("home"), tab("insert"))
        assertEquals("insert", tabs.keeping("insert")?.id)
        assertEquals("home", tabs.keeping("table")?.id)
        assertEquals("home", tabs.keeping(null)?.id)
        assertNull(emptyList<CommandTab>().keeping("home"))
    }
}
