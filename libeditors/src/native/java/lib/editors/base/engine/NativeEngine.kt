package lib.editors.base.engine

/** JNI binding of the engine (editors-android/engine, libeoengine.so). */
internal object NativeEngine {

    init {
        System.loadLibrary("eoengine")
    }

    /** Loads the libraries (in the initializer). */
    fun load() = Unit

    external fun nativeInitialize(runtimeDir: String): Boolean
    external fun nativeCreateDocument(): Long
    external fun nativeDestroyDocument(handle: Long)
    /** 0, x2t's error code or -1. */
    external fun nativeOpen(handle: Long, path: String, tempDir: String, password: String, encoding: Int, delimiter: Int, language: String): Int
    external fun nativeSave(handle: Long, path: String, extension: String): Int
    external fun nativeExecute(handle: Long, script: String): ByteArray
    /** The count of presentation designs, converted once (eo::Document::PrepareThemes). */
    external fun nativePrepareThemes(handle: Long): Int
    external fun nativeGetPageCount(handle: Long): Int
    external fun nativeGetPageSize(handle: Long, page: Int): DoubleArray
    external fun nativeCreateDrawingDocument(): Long
    external fun nativeDestroyDrawingDocument(handle: Long)
    external fun nativeOpenDrawing(handle: Long, path: String, password: String, tempDir: String): Int
    external fun nativeGetDrawingPageCount(handle: Long): Int
    external fun nativeGetDrawingPageSize(handle: Long, page: Int): DoubleArray
    /** The page's text and its character boxes: [String, FloatArray]. */
    external fun nativeGetDrawingPageText(handle: Long, page: Int): Array<Any>?
    /** The page's links: [Array<String> of addresses, FloatArray of areas]. */
    external fun nativeGetDrawingLinks(handle: Long, page: Int): Array<Any>?
    /** The outline: [Array<String> of titles, FloatArray of level, page and y per entry]. */
    external fun nativeGetDrawingOutline(handle: Long): Array<Any>?
    external fun nativeSaveDrawingWithChanges(
        handle: Long, path: String, markupInts: IntArray, markupFloats: FloatArray, author: String,
        imagePages: IntArray, imagePaths: Array<String>, imageAreas: FloatArray
    ): Int
    external fun nativeRenderDrawingTile(
        handle: Long, page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, bitmap: android.graphics.Bitmap
    ): Boolean
    /** Hunspell with a dictionary; 0 if it can't be read. */
    external fun nativeCreateSpeller(affPath: String, dicPath: String): Long
    external fun nativeDestroySpeller(handle: Long)
    external fun nativeSpell(handle: Long, words: Array<String>): BooleanArray
    external fun nativeSuggest(handle: Long, word: String): Array<String>
    external fun nativeAddWord(handle: Long, word: String)
    external fun nativeRenderTile(
        handle: Long, page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, bitmap: android.graphics.Bitmap
    ): Boolean
}
