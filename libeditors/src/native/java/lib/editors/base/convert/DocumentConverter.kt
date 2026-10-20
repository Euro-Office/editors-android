package lib.editors.base.convert

import android.content.Context
import lib.x2t.X2t
import java.io.File

/** Format conversion with core's x2t (libx2t). */
object DocumentConverter {

    val isAvailable: Boolean = true

    /**
     * Converts [source] (docx, xlsx, pptx, odt, ...) to the editors' internal format (Editor.bin) at [target].
     * Blocking; call it off the main thread.
     */
    fun convertToEditorBin(context: Context, source: File, target: File): ConversionResult {
        val formatTo = canvasFormat(X2t.getFileFormat(source.absolutePath))
            ?: return ConversionResult(X2t.CONVERTER_CODE_FAIL, null)

        // x2t keeps its index of the system fonts here (font_selection.bin)
        val fontsDir = File(context.cacheDir, "fonts").apply { mkdirs() }.absolutePath

        val builder = X2t.builder()
            .setContext(context)
            .setConvertType(formatTo.toString())
            .setFromPath(source.absolutePath)
            .setToPath(target.absolutePath)
            .setIsOverwrite(true)
            .setIsClearTemp(true)
        builder.getInputParams().apply {
            this.fontsDir = fontsDir
            this.fontsJs = fontsDir
        }

        val result = builder.convert()
        return ConversionResult(result.code, target.takeIf { result.isSuccess && it.exists() })
    }

    // Canvas format for an AVS_OFFICESTUDIO_FILE_* source format (core/Common/OfficeFileFormats.h)
    private fun canvasFormat(sourceFormat: Int): Int? = when (sourceFormat) {
        in 0x0040 until 0x0080 -> 0x2001 // document -> AVS_OFFICESTUDIO_FILE_CANVAS_WORD
        in 0x0080 until 0x0100 -> 0x2003 // presentation -> AVS_OFFICESTUDIO_FILE_CANVAS_PRESENTATION
        in 0x0100 until 0x0200 -> 0x2002 // spreadsheet -> AVS_OFFICESTUDIO_FILE_CANVAS_SPREADSHEET
        else -> null
    }
}
