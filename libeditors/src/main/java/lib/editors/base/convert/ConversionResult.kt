package lib.editors.base.convert

import java.io.File

/** Outcome of a [DocumentConverter] call; [code] is the native x2t result code (0 = success). */
class ConversionResult(val code: Int, val output: File?) {

    val isSuccess: Boolean
        get() = output != null
}
