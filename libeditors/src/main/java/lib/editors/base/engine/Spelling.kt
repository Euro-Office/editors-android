package lib.editors.base.engine

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Answers sdkjs' spelling requests (see [EditorDocument.checkSpelling]). */
fun interface SpellChecker {
    /** The requests ([{type, usrWords, usrLang, ...}]) with usrCorrect or usrSuggest added, as JSON. */
    fun answer(requests: String): String
}

/** Hunspell with one dictionary (see [EditorEngine.createSpeller]). */
interface Speller {
    fun spell(words: Array<String>): BooleanArray
    fun suggest(word: String): List<String>
    fun add(word: String)
    fun close()
}

/**
 * Hunspell dictionaries from Euro-Office's dictionaries repository. English comes with the app;
 * other languages are downloaded when needed, from the commit the AndroidEditors checkout pinned
 * when the app was built (index.json), so they are always the ones it was built with.
 *
 * A dictionary is named after its folder in the repository ("de_DE"); sdkjs asks by LCID.
 */
class Dictionaries(runtimeDir: File, private val downloadDir: File) {

    private val bundledDir = File(runtimeDir, "dictionaries")

    private class Index(val commit: String, val baseUrl: String, val languages: Map<String, List<Int>>)

    private val index: Index? by lazy {
        runCatching {
            val json = JSONObject(File(bundledDir, "index.json").readText())
            val languages = json.getJSONObject("languages")
            Index(
                json.getString("commit"), json.getString("url"),
                languages.keys().asSequence().associateWith { name ->
                    languages.getJSONArray(name).let { codes -> (0 until codes.length()).map { codes.getInt(it) } }
                }
            )
        }.onFailure { Log.e(TAG, "no dictionary index", it) }.getOrNull()
    }

    /** The dictionary for a language (LCID), installed or not; null if there is none. */
    fun nameFor(lcid: Int): String? = index?.languages?.entries?.firstOrNull { lcid in it.value }?.key

    /** The dictionary for the device's language ("de_DE" for German in Germany, else another German one). */
    fun forLocale(locale: Locale = Locale.getDefault()): String? {
        val names = index?.languages?.keys ?: return null
        val tag = locale.language + "_" + locale.country
        return names.firstOrNull { it == tag } ?: names.sorted().firstOrNull { it == locale.language || it.startsWith(locale.language + "_") }
    }

    /** The .aff and .dic files of an installed dictionary. */
    fun files(name: String): Pair<File, File>? {
        val bundled = File(bundledDir, name)
        if (File(bundled, "$name.dic").exists()) return File(bundled, "$name.aff") to File(bundled, "$name.dic")
        val downloaded = File(downloadDir, name)
        val commit = File(downloaded, COMMIT).takeIf { it.exists() }?.readText()
        if (commit == null || commit != index?.commit) return null
        return File(downloaded, "$name.aff") to File(downloaded, "$name.dic")
    }

    /** The languages (LCIDs) of the installed dictionaries. */
    fun installedLanguages(): List<Int> =
        index?.languages.orEmpty().filterKeys { files(it) != null }.values.flatten()

    /** Downloads a dictionary; blocks, so call it on an IO thread. False if that failed. */
    fun download(name: String): Boolean {
        val index = index ?: return false
        if (name !in index.languages) return false
        val target = File(downloadDir, name)
        val partial = File(downloadDir, "$name.partial").apply {
            deleteRecursively()
            mkdirs()
        }
        return runCatching {
            for (extension in listOf("aff", "dic")) {
                val connection = URL("${index.baseUrl}/${index.commit}/$name/$name.$extension").openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
                    connection.inputStream.use { input -> File(partial, "$name.$extension").outputStream().use { input.copyTo(it) } }
                } finally {
                    connection.disconnect()
                }
            }
            File(partial, COMMIT).writeText(index.commit)
            target.deleteRecursively()
            check(partial.renameTo(target))
            true
        }.onFailure {
            Log.w(TAG, "can't download the dictionary $name", it)
            partial.deleteRecursively()
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "Dictionaries"
        const val COMMIT = "commit"
        const val TIMEOUT_MS = 20_000
    }
}

/**
 * Checks words with the installed dictionaries and the user's own words. Not thread-safe: use it
 * on the engine's thread ([EditorEngine.dispatcher]).
 */
class SpellService(private val dictionaries: Dictionaries, private val userWordsFile: File) : SpellChecker {

    // Loaded on first use; null for a language without a dictionary (its words count as correct)
    private val spellers = mutableMapOf<String, Speller?>()
    private val userWords: MutableSet<String> by lazy {
        runCatching { userWordsFile.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList()).toMutableSet()
    }

    override fun answer(requests: String): String {
        val array = JSONArray(requests)
        for (i in 0 until array.length()) {
            val request = array.getJSONObject(i)
            val words = request.getJSONArray("usrWords")
            val languages = request.getJSONArray("usrLang")
            if (request.optString("type") == "suggest") {
                val suggestions = JSONArray()
                for (w in 0 until words.length()) {
                    val speller = speller(languages.optInt(w))
                    suggestions.put(JSONArray(speller?.suggest(words.getString(w)).orEmpty().take(MAX_SUGGESTIONS)))
                }
                request.put("usrSuggest", suggestions)
            } else {
                // Words of the same language are checked together
                val correct = BooleanArray(words.length()) { true }
                (0 until words.length()).groupBy { languages.optInt(it) }.forEach { (lcid, indices) ->
                    val speller = speller(lcid) ?: return@forEach
                    val checked = speller.spell(Array(indices.size) { words.getString(indices[it]) })
                    indices.forEachIndexed { k, index -> correct[index] = checked[k] || words.getString(index) in userWords }
                }
                request.put("usrCorrect", JSONArray(correct.toList()))
            }
        }
        return array.toString()
    }

    /** A word of the user's: correct in all languages from now on. */
    fun addWord(word: String) {
        if (!userWords.add(word)) return
        runCatching { userWordsFile.parentFile?.mkdirs(); userWordsFile.appendText(word + "\n") }
        spellers.values.forEach { it?.add(word) }
    }

    /** A dictionary was downloaded: languages without one are looked up again. */
    fun reload() {
        spellers.entries.removeAll { it.value == null }
    }

    private fun speller(lcid: Int): Speller? {
        val name = dictionaries.nameFor(lcid) ?: return null
        return spellers.getOrPut(name) {
            dictionaries.files(name)?.let { (aff, dic) -> EditorEngine.createSpeller(aff, dic) }
                ?.also { speller -> userWords.forEach { speller.add(it) } }
        }
    }

    private companion object {
        const val MAX_SUGGESTIONS = 5
    }
}

/** The app's dictionaries and spell checker, shared by all editors. */
object EditorSpelling {

    @Volatile
    private var dictionaries: Dictionaries? = null
    private var service: SpellService? = null

    fun dictionaries(context: android.content.Context): Dictionaries = dictionaries ?: synchronized(this) {
        dictionaries ?: Dictionaries(
            EditorEngine.runtimeDir(context), File(context.filesDir, "editors/dictionaries")
        ).also { dictionaries = it }
    }

    /** Call on the engine's thread ([EditorEngine.dispatcher]). */
    fun service(context: android.content.Context): SpellService = service
        ?: SpellService(dictionaries(context), File(context.filesDir, "editors/dictionaries/user-words.txt")).also { service = it }
}
