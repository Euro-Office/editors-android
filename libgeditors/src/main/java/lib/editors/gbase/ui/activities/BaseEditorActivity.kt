package lib.editors.gbase.ui.activities

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.convert.DocumentConverter
import lib.editors.base.engine.Alignment
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.TouchAction
import lib.editors.gbase.R
import lib.editors.gbase.ui.views.DocumentView
import lib.toolkit.base.managers.utils.EditType
import lib.toolkit.base.managers.utils.EditorsContract
import lib.toolkit.base.managers.utils.EditorsType
import lib.editors.resources.R as Resources
import java.io.File

/**
 * Base for the editor activities the app launches by class name (see [EditorsContract]).
 *
 * Documents on the device are opened by the editor engine, for editing unless the app asks
 * for viewing only. Everything else is still a placeholder that reports
 * [EditorsContract.RESULT_FAILED_OPEN].
 */
abstract class BaseEditorActivity : AppCompatActivity() {

    protected abstract val editorsType: EditorsType

    @Volatile
    private var document: EditorDocument? = null

    private lateinit var documentView: DocumentView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EditorsContract.KEY_KEEP_SCREEN_ON, false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setResult(EditorsContract.RESULT_FAILED_OPEN)

        val uri = intent.data
        if (uri != null && editorsType == EditorsType.DOCS && EditorEngine.isAvailable) {
            showViewer(uri)
        } else {
            showPlaceholder(uri)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val document = document ?: return
        this.document = null
        CoroutineScope(EditorEngine.dispatcher).launch { document.close() }
    }

    private fun showViewer(uri: Uri) {
        setContentView(R.layout.activity_editor_viewer)
        documentView = findViewById(R.id.documentView)
        val progress = findViewById<View>(R.id.documentProgress)
        val isEditable = isEditable()

        // Room for the system bars and the keyboard (the toolbar stays above it). The window
        // is laid out edge to edge, so the keyboard's size arrives as insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.editorRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { copyToCache(uri) }.getOrNull() }
            if (file == null) {
                Log.e(TAG, "Can't read $uri")
                finish()
                return@launch
            }
            val opened = withContext(EditorEngine.dispatcher) {
                var start = SystemClock.elapsedRealtime()
                if (!EditorEngine.initialize(this@BaseEditorActivity)) return@withContext null
                Log.i(TAG, "engine ready: ${SystemClock.elapsedRealtime() - start} ms")

                start = SystemClock.elapsedRealtime()
                val document = EditorEngine.open(this@BaseEditorActivity, file) ?: return@withContext null
                val pages = (0 until document.pageCount).map { document.pageSize(it) }
                Log.i(TAG, "open + layout: ${SystemClock.elapsedRealtime() - start} ms, ${pages.size} pages")
                Triple(document, pages, document.state())
            }
            if (opened == null) {
                finish()
                return@launch
            }

            document = opened.first
            setResult(RESULT_OK, Intent().setData(uri).putExtra(EditorsContract.EXTRA_IS_MODIFIED, false))
            progress.isVisible = false
            documentView.setPages(opened.second, ::renderTile, lifecycleScope)
            if (isEditable) {
                documentView.editListener = editListener
                findViewById<View>(R.id.editorToolbar).isVisible = true
                bindToolbar()
                applyState(opened.third)
            }
        }
    }

    private fun isEditable(): Boolean {
        @Suppress("DEPRECATION")
        val editType = intent.getSerializableExtra(EditorsContract.KEY_EDIT_TYPE) as? EditType
        return editType == null || editType is EditType.Edit
    }

    // ---------------------------------------------------------------------------------
    // Editing: input goes to the engine in order, its state comes back to the view

    private val editListener = object : DocumentView.EditListener {
        override fun onTouch(action: TouchAction, page: Int, xMm: Double, yMm: Double, clickCount: Int) =
            edit { touch(action, page, xMm, yMm, clickCount) }

        override fun onKey(keyCode: Int, shift: Boolean, ctrl: Boolean) = edit { keyDown(keyCode, shift, ctrl) }

        override fun onCommitText(text: String) = edit { commitText(text) }

        override fun onComposingText(text: String) = edit { setComposingText(text) }

        override fun onFinishComposingText() = edit { finishComposingText() }
    }

    private fun edit(command: EditorDocument.() -> EditorState) {
        lifecycleScope.launch {
            val result = withContext(EditorEngine.dispatcher) {
                val document = document ?: return@withContext null
                val state = document.command()
                // Page count or sizes may change while editing
                val pages = if (state.layoutChanged) (0 until document.pageCount).map { document.pageSize(it) } else null
                state to pages
            } ?: return@launch
            result.second?.let { documentView.updatePages(it) }
            applyState(result.first)
        }
    }

    private fun applyState(state: EditorState) {
        documentView.setEditorState(state)
        val toolbar = state.toolbar
        findViewById<View>(R.id.toolBold).isSelected = toolbar.bold
        findViewById<View>(R.id.toolItalic).isSelected = toolbar.italic
        findViewById<View>(R.id.toolUnderline).isSelected = toolbar.underline
        findViewById<View>(R.id.toolAlignLeft).isSelected = toolbar.alignment == Alignment.LEFT
        findViewById<View>(R.id.toolAlignCenter).isSelected = toolbar.alignment == Alignment.CENTER
        findViewById<View>(R.id.toolAlignRight).isSelected = toolbar.alignment == Alignment.RIGHT
        findViewById<View>(R.id.toolUndo).isEnabled = toolbar.canUndo
        findViewById<View>(R.id.toolRedo).isEnabled = toolbar.canRedo
        findViewById<ImageButton>(R.id.toolUndo).imageAlpha = if (toolbar.canUndo) 255 else 80
        findViewById<ImageButton>(R.id.toolRedo).imageAlpha = if (toolbar.canRedo) 255 else 80
    }

    private fun bindToolbar() {
        fun bind(id: Int, command: EditorDocument.(selected: Boolean) -> EditorState) {
            findViewById<View>(id).setOnClickListener { view -> edit { command(!view.isSelected) } }
        }
        bind(R.id.toolBold) { setBold(it) }
        bind(R.id.toolItalic) { setItalic(it) }
        bind(R.id.toolUnderline) { setUnderline(it) }
        bind(R.id.toolAlignLeft) { setAlignment(Alignment.LEFT) }
        bind(R.id.toolAlignCenter) { setAlignment(Alignment.CENTER) }
        bind(R.id.toolAlignRight) { setAlignment(Alignment.RIGHT) }
        bind(R.id.toolUndo) { undo() }
        bind(R.id.toolRedo) { redo() }
    }

    private suspend fun renderTile(page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, width: Int, height: Int): Bitmap? =
        withContext(EditorEngine.dispatcher) {
            val document = document ?: return@withContext null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val start = SystemClock.elapsedRealtime()
            val rendered = document.renderTile(page, pageWidth, pageHeight, x, y, bitmap)
            Log.i(TAG, "render page ${page + 1} ${width}x$height: ${SystemClock.elapsedRealtime() - start} ms")
            if (rendered) bitmap else null
        }

    private fun copyToCache(uri: Uri): File {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        val dir = File(cacheDir, "editor-input").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, name)
        contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun showPlaceholder(uri: Uri?) {
        setContentView(R.layout.activity_editor_placeholder)
        findViewById<TextView>(R.id.editorTitle).setText(getTitleRes())
        findViewById<TextView>(R.id.editorFile).text =
            uri?.lastPathSegment ?: getString(Resources.string.editors_server_document)

        if (uri != null && DocumentConverter.isAvailable) {
            findViewById<Button>(R.id.editorConvert).apply {
                visibility = View.VISIBLE
                setOnClickListener { convert(uri) }
            }
        }
    }

    /** Developer check of the native core for editors that don't exist yet: converts the document with x2t. */
    private fun convert(uri: Uri) {
        val button = findViewById<Button>(R.id.editorConvert)
        val status = findViewById<TextView>(R.id.editorConvertStatus)
        button.isEnabled = false
        status.setText(Resources.string.editors_converting)

        lifecycleScope.launch {
            val (result, duration) = withContext(Dispatchers.IO) {
                val source = copyToCache(uri)
                val target = File(source.parentFile, "Editor.bin")
                val start = SystemClock.elapsedRealtime()
                DocumentConverter.convertToEditorBin(this@BaseEditorActivity, source, target) to
                    SystemClock.elapsedRealtime() - start
            }
            button.isEnabled = true
            val output = result.output
            status.text = if (output == null) {
                getString(Resources.string.editors_convert_failed, result.code)
            } else {
                getString(Resources.string.editors_convert_success, duration, output.length() / 1024)
            }
        }
    }

    private fun getTitleRes(): Int = when (editorsType) {
        EditorsType.DOCS -> Resources.string.editors_type_docs
        EditorsType.CELLS -> Resources.string.editors_type_cells
        EditorsType.PRESENTATION -> Resources.string.editors_type_slides
        EditorsType.PDF -> Resources.string.editors_type_pdf
    }

    private companion object {
        const val TAG = "EditorViewer"
    }
}
