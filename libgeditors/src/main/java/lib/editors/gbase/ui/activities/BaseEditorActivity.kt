package lib.editors.gbase.ui.activities

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import lib.editors.base.convert.DocumentConverter
import lib.editors.gbase.R
import lib.toolkit.base.managers.utils.EditorsContract
import lib.toolkit.base.managers.utils.EditorsType
import lib.editors.resources.R as Resources
import java.io.File

/**
 * Base for the editor activities the app launches by class name (see [EditorsContract]).
 *
 * Until the engine exists every editor is a placeholder that reports [EditorsContract.RESULT_FAILED_OPEN].
 */
abstract class BaseEditorActivity : AppCompatActivity() {

    protected abstract val editorsType: EditorsType

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EditorsContract.KEY_KEEP_SCREEN_ON, false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setResult(EditorsContract.RESULT_FAILED_OPEN)
        setContentView(R.layout.activity_editor_placeholder)
        findViewById<TextView>(R.id.editorTitle).setText(getTitleRes())
        findViewById<TextView>(R.id.editorFile).text =
            intent.data?.lastPathSegment ?: getString(Resources.string.editors_server_document)

        val uri = intent.data
        if (uri != null && DocumentConverter.isAvailable) {
            findViewById<Button>(R.id.editorConvert).apply {
                visibility = View.VISIBLE
                setOnClickListener { convert(uri) }
            }
        }
    }

    /** Developer check of the native core until the editors exist: converts the document with x2t. */
    private fun convert(uri: Uri) {
        val button = findViewById<Button>(R.id.editorConvert)
        val status = findViewById<TextView>(R.id.editorConvertStatus)
        button.isEnabled = false
        status.setText(Resources.string.editors_converting)

        Thread {
            val workDir = File(cacheDir, "x2t-test").apply { deleteRecursively(); mkdirs() }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
            val source = File(workDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val target = File(workDir, "Editor.bin")

            val start = SystemClock.elapsedRealtime()
            val result = DocumentConverter.convertToEditorBin(this, source, target)
            val duration = SystemClock.elapsedRealtime() - start

            runOnUiThread {
                button.isEnabled = true
                val output = result.output
                if (output == null) {
                    status.text = getString(Resources.string.editors_convert_failed, result.code)
                    return@runOnUiThread
                }
                status.text = getString(Resources.string.editors_convert_success, duration, output.length() / 1024)
            }
        }.start()
    }

    private fun getTitleRes(): Int = when (editorsType) {
        EditorsType.DOCS -> Resources.string.editors_type_docs
        EditorsType.CELLS -> Resources.string.editors_type_cells
        EditorsType.PRESENTATION -> Resources.string.editors_type_slides
        EditorsType.PDF -> Resources.string.editors_type_pdf
    }
}
