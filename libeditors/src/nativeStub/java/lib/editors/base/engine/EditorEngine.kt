package lib.editors.base.engine

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Stand-in for builds without the native core (-PwithNativeCore not set). */
object EditorEngine {

    val isAvailable: Boolean = false

    val dispatcher: CoroutineDispatcher = Dispatchers.Default

    fun initialize(context: Context): Boolean = false

    fun open(context: Context, file: File): EditorDocument? = null

    fun openDrawingFile(context: Context, file: File, password: String = ""): DrawingFileResult = DrawingFileResult.Failed
}
