package lib.editors.base.engine

/** Hunspell in the engine (eo::Speller). */
internal class NativeSpeller(private var handle: Long) : Speller {

    override fun spell(words: Array<String>): BooleanArray = NativeEngine.nativeSpell(handle, words)

    override fun suggest(word: String): List<String> = NativeEngine.nativeSuggest(handle, word).toList()

    override fun add(word: String) = NativeEngine.nativeAddWord(handle, word)

    override fun close() {
        if (handle == 0L) return
        NativeEngine.nativeDestroySpeller(handle)
        handle = 0L
    }
}
