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
    external fun nativeOpen(handle: Long, path: String, tempDir: String): Boolean
    external fun nativeSave(handle: Long, path: String, extension: String): Int
    external fun nativeExecute(handle: Long, script: String): ByteArray
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
    external fun nativeRenderDrawingTile(
        handle: Long, page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, bitmap: android.graphics.Bitmap
    ): Boolean
    external fun nativeRenderTile(
        handle: Long, page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, bitmap: android.graphics.Bitmap
    ): Boolean
}
