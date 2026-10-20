package lib.editors.base.engine

/** JNI binding of the engine (editors-android/engine, libeoengine.so). */
internal object NativeEngine {

    init {
        System.loadLibrary("eoengine")
    }

    external fun nativeInitialize(runtimeDir: String): Boolean
    external fun nativeCreateDocument(): Long
    external fun nativeDestroyDocument(handle: Long)
    external fun nativeOpen(handle: Long, path: String, tempDir: String): Boolean
    external fun nativeGetPageCount(handle: Long): Int
    external fun nativeGetPageSize(handle: Long, page: Int): DoubleArray
    external fun nativeRenderTile(
        handle: Long, page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, bitmap: android.graphics.Bitmap
    ): Boolean
}
