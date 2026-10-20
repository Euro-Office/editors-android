package lib.editors.base.convert

import android.content.Context
import java.io.File

/** Stand-in for builds without the native core (-PwithNativeCore not set). */
object DocumentConverter {

    val isAvailable: Boolean = false

    fun convertToEditorBin(context: Context, source: File, target: File): ConversionResult =
        ConversionResult(-1, null)
}
