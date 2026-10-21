package lib.editors.base.engine

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class SpellingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var runtime: File
    private lateinit var downloads: File
    private lateinit var dictionaries: Dictionaries

    @Before
    fun setUp() {
        runtime = folder.newFolder("runtime")
        downloads = folder.newFolder("downloads")
        File(runtime, "dictionaries/en_US").mkdirs()
        File(runtime, "dictionaries/en_US/en_US.aff").writeText("SET UTF-8")
        File(runtime, "dictionaries/en_US/en_US.dic").writeText("0")
        File(runtime, "dictionaries/index.json").writeText(
            """{"commit": "abc", "url": "https://example.org/dictionaries",
               "languages": {"en_US": [1033], "de_DE": [1031], "de_AT": [3079], "fr_FR": [1036]}}"""
        )
        dictionaries = Dictionaries(runtime, downloads)
    }

    // A downloaded dictionary, of the given commit
    private fun download(name: String, commit: String) {
        File(downloads, name).mkdirs()
        File(downloads, "$name/$name.aff").writeText("SET UTF-8")
        File(downloads, "$name/$name.dic").writeText("0")
        File(downloads, "$name/commit").writeText(commit)
    }

    @Test
    fun `finds dictionaries by language`() {
        assertEquals("de_DE", dictionaries.nameFor(1031))
        assertNull(dictionaries.nameFor(1049))
    }

    @Test
    fun `chooses the dictionary of the device's language`() {
        assertEquals("de_AT", dictionaries.forLocale(Locale("de", "AT")))
        assertEquals("de_AT", dictionaries.forLocale(Locale("de", "CH")))
        assertEquals("fr_FR", dictionaries.forLocale(Locale("fr", "BE")))
        assertNull(dictionaries.forLocale(Locale("ja", "JP")))
    }

    @Test
    fun `knows bundled and downloaded dictionaries of the app's commit only`() {
        assertEquals(listOf(1033), dictionaries.installedLanguages())
        download("de_DE", "abc")
        download("fr_FR", "older")
        assertEquals(setOf(1033, 1031), dictionaries.installedLanguages().toSet())
        assertNull(dictionaries.files("fr_FR"))
    }

    private class FakeSpeller(private val words: Set<String>) : Speller {
        val added = mutableListOf<String>()
        override fun spell(words: Array<String>) = BooleanArray(words.size) { words[it] in this.words || words[it] in added }
        override fun suggest(word: String) = listOf(word.reversed(), word.uppercase())
        override fun add(word: String) {
            added += word
        }
        override fun close() = Unit
    }

    @Test
    fun `answers spell and suggest requests`() {
        val speller = FakeSpeller(setOf("good", "words"))
        val service = SpellService(dictionaries, File(folder.root, "user.txt")) { _, _ -> speller }
        val answer = JSONArray(
            service.answer(
                """[{"type": "spell", "usrWords": ["good", "bda", "words", "Wort"], "usrLang": [1033, 1033, 1033, 1049]},
                    {"type": "suggest", "usrWords": ["bda"], "usrLang": [1033]}]"""
            )
        )
        // Languages without a dictionary count as correct
        assertEquals("[true,false,true,true]", answer.getJSONObject(0).getJSONArray("usrCorrect").toString())
        assertEquals("[[\"adb\",\"BDA\"]]", answer.getJSONObject(1).getJSONArray("usrSuggest").toString())
    }

    @Test
    fun `keeps the user's words`() {
        val userWords = File(folder.root, "user.txt")
        val speller = FakeSpeller(emptySet())
        SpellService(dictionaries, userWords) { _, _ -> speller }.addWord("Euro-Office")
        assertEquals("Euro-Office\n", userWords.readText())
        // A new service (the next start) knows it too
        val answer = SpellService(dictionaries, userWords) { _, _ -> FakeSpeller(emptySet()) }
            .answer("""[{"type": "spell", "usrWords": ["Euro-Office"], "usrLang": [1033]}]""")
        assertEquals("[true]", JSONArray(answer).getJSONObject(0).getJSONArray("usrCorrect").toString())
    }
}
