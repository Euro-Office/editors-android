package lib.editors.base.engine

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import lib.x2t.X2t
import java.io.File
import java.util.concurrent.Executors

/**
 * The editor engine: sdkjs running in core's doctrenderer (V8).
 *
 * sdkjs is single-threaded, so the engine and its documents are used on one thread, [dispatcher].
 */
object EditorEngine {

    private const val TAG = "EditorEngine"
    private const val RUNTIME_ASSETS = "editors/runtime"

    val isAvailable: Boolean = true

    val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "editor-engine") }.asCoroutineDispatcher()

    private var isInitialized = false

    /**
     * Prepares the engine on first use: unpacks sdkjs from the APK and indexes the device's fonts.
     * Call on [dispatcher].
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) return true

        val runtimeDir = File(context.filesDir, RUNTIME_ASSETS)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val marker = File(runtimeDir, "version")
        if (!marker.exists() || marker.readText() != version) {
            runtimeDir.deleteRecursively()
            copyAssets(context.assets, RUNTIME_ASSETS, runtimeDir)
            marker.writeText(version)
        }

        // AllFonts.js and font_selection.bin, from the bundled fonts and /system/fonts. In its
        // own folder, because generating the index removes the other files in the folder.
        val fontsDir = File(runtimeDir, "fonts")
        if (!File(fontsDir, "AllFonts.js").exists()) {
            fontsDir.mkdirs()
            X2t.setFonts(arrayOf(File(runtimeDir, "bundled-fonts").absolutePath), fontsDir.absolutePath)
        }

        var start = SystemClock.elapsedRealtime()
        NativeEngine.load()
        Log.i(TAG, "load libraries: ${SystemClock.elapsedRealtime() - start} ms")
        isInitialized = NativeEngine.nativeInitialize(runtimeDir.absolutePath)
        return isInitialized
    }

    /** Opens [file] (docx, odt, ...); null if it can't be opened. Call on [dispatcher]. */
    fun open(context: Context, file: File): EditorDocument? =
        (openDocument(context, file) as? DocumentResult.Opened)?.document

    /**
     * Opens [file] (docx, odt, ...); [password] is for encrypted files, which are saved with
     * it again. Call on [dispatcher].
     */
    fun openDocument(context: Context, file: File, password: String = ""): DocumentResult {
        if (!initialize(context)) return DocumentResult.Failed

        val tempDir = File(context.cacheDir, "editor").apply {
            deleteRecursively()
            mkdirs()
        }
        val handle = NativeEngine.nativeCreateDocument()
        val result = NativeEngine.nativeOpen(handle, file.absolutePath, tempDir.absolutePath, password)
        if (result != 0) {
            Log.e(TAG, "Can't open ${file.name}: $result")
            NativeEngine.nativeDestroyDocument(handle)
            return if (result in X2T_PASSWORD_ERRORS) DocumentResult.NeedsPassword else DocumentResult.Failed
        }
        return DocumentResult.Opened(NativeEditorDocument(handle))
    }

    /**
     * Opens a PDF, XPS or DjVu file, drawn by core's readers (no sdkjs). [password] is for
     * encrypted PDFs. Call on [dispatcher].
     */
    fun openDrawingFile(context: Context, file: File, password: String = ""): DrawingFileResult {
        if (!initialize(context)) return DrawingFileResult.Failed

        val tempDir = File(context.cacheDir, "drawing").apply {
            deleteRecursively()
            mkdirs()
        }
        val handle = NativeEngine.nativeCreateDrawingDocument()
        val result = NativeEngine.nativeOpenDrawing(handle, file.absolutePath, password, tempDir.absolutePath)
        if (result == OPENED) return DrawingFileResult.Opened(NativeDrawingDocument(handle))
        NativeEngine.nativeDestroyDrawingDocument(handle)
        return if (result == NEEDS_PASSWORD) DrawingFileResult.NeedsPassword else DrawingFileResult.Failed
    }

    // x2t's results for an encrypted file without (DRM) or with a wrong password
    private val X2T_PASSWORD_ERRORS = setOf(90, 91)

    // eo::DrawingDocument::OpenResult
    private const val OPENED = 0
    private const val NEEDS_PASSWORD = 2

    private fun copyAssets(assets: AssetManager, path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        children.forEach { copyAssets(assets, "$path/$it", File(target, it)) }
    }
}
