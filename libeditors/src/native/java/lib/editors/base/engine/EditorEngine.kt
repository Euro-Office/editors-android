package lib.editors.base.engine

import android.content.Context
import android.content.res.AssetManager
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

        isInitialized = NativeEngine.nativeInitialize(runtimeDir.absolutePath)
        return isInitialized
    }

    /** Opens [file] (docx, odt, ...); null if it can't be opened. Call on [dispatcher]. */
    fun open(context: Context, file: File): EditorDocument? {
        if (!initialize(context)) return null

        val tempDir = File(context.cacheDir, "editor").apply {
            deleteRecursively()
            mkdirs()
        }
        val handle = NativeEngine.nativeCreateDocument()
        if (!NativeEngine.nativeOpen(handle, file.absolutePath, tempDir.absolutePath)) {
            Log.e(TAG, "Can't open ${file.name}")
            NativeEngine.nativeDestroyDocument(handle)
            return null
        }
        return NativeEditorDocument(handle)
    }

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
