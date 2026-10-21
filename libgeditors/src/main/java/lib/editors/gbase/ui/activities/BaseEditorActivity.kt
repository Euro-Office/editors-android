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
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.content.ClipData
import android.content.ClipboardManager
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.appcompat.app.AlertDialog
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
import lib.editors.base.engine.DrawingFileResult
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.PagedDocument
import lib.editors.base.engine.TouchAction
import lib.editors.base.engine.WRITABLE_FORMATS
import lib.editors.gbase.R
import lib.editors.gbase.ui.clipboard.EditorClipboard
import lib.editors.gbase.ui.views.DocumentView
import lib.editors.gbase.ui.views.SheetView
import lib.toolkit.base.managers.utils.EditType
import lib.toolkit.base.managers.utils.EditorsContract
import lib.toolkit.base.managers.utils.EditorsType
import lib.editors.resources.R as Resources
import java.io.File

/**
 * Base for the editor activities the app launches by class name (see [EditorsContract]).
 *
 * Documents and presentations on the device are opened by the editor engine, for editing unless
 * the app asks for viewing only. Slides are shown below each other, like pages. PDF, XPS and DjVu
 * files are shown read-only. Spreadsheets show one sheet at a time, with cells edited in a
 * formula bar (see [SpreadsheetUi]).
 *
 * Edits are saved into the document the app passed (the save button, leaving the editor and
 * going to the background save). The result then reports the document as modified, and the
 * app uploads it if it comes from a server.
 */
abstract class BaseEditorActivity : AppCompatActivity() {

    protected abstract val editorsType: EditorsType

    @Volatile
    private var document: EditorDocument? = null

    // PDF, XPS and DjVu files (PdfActivity), read-only
    @Volatile
    private var drawingDocument: PagedDocument? = null

    private lateinit var documentView: DocumentView
    // Spreadsheets: the sheet instead of the document view
    private var spreadsheetUi: SpreadsheetUi? = null
    private var searchBar: SearchBar? = null
    private var documentUi: DocumentUi? = null
    private var slidesUi: SlidesUi? = null
    private lateinit var linkBar: View
    private val isSpreadsheet get() = editorsType == EditorsType.CELLS

    // The document the app passed, and the format edits are saved in (its extension)
    private var documentUri: Uri? = null
    private var extension = ""
    private var modified = false
    private var saving = false
    // A slice of the layout of later pages is queued (see EditorDocument.tick)
    private var layingOut = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EditorsContract.KEY_KEEP_SCREEN_ON, false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setResult(EditorsContract.RESULT_FAILED_OPEN)

        val uri = intent.data
        if (uri != null && editorsType in ENGINE_EDITORS && EditorEngine.isAvailable) {
            showViewer(uri)
        } else if (uri != null && editorsType == EditorsType.PDF && EditorEngine.isAvailable) {
            showDrawingViewer(uri)
        } else {
            showPlaceholder(uri)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val documents = listOfNotNull(document, drawingDocument)
        document = null
        drawingDocument = null
        if (documents.isNotEmpty()) CoroutineScope(EditorEngine.dispatcher).launch { documents.forEach { it.close() } }
    }

    private fun setUpViewer() {
        if (isSpreadsheet) {
            setContentView(R.layout.activity_editor_cells)
        } else {
            setContentView(R.layout.activity_editor_viewer)
            documentView = findViewById(R.id.documentView)
        }

        linkBar = createLinkBar()
        val root = findViewById<ViewGroup>(R.id.editorRoot)
        root.addView(linkBar, root.indexOfChild(findViewById(R.id.editorToolbar)))

        // Room for the system bars and the keyboard (the toolbar stays above it). The window
        // is laid out edge to edge, so the keyboard's size arrives as insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.editorRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun showViewer(uri: Uri) {
        setUpViewer()
        val progress = findViewById<View>(R.id.documentProgress)
        val isEditable = isEditable()

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
            documentUri = uri
            extension = file.extension.lowercase()
            setResult(RESULT_OK, Intent().setData(uri).putExtra(EditorsContract.EXTRA_IS_MODIFIED, false))
            progress.isVisible = false
            if (isSpreadsheet) {
                val sheetView = findViewById<SheetView>(R.id.sheetView)
                sheetView.setRenderer(::renderTile, lifecycleScope)
                val openedDocument = opened.first
                spreadsheetUi = SpreadsheetUi(this@BaseEditorActivity, sheetView, { command -> edit(command = command) }, object : SpreadsheetUi.Clipboard {
                    override fun cut() = this@BaseEditorActivity.copy(cut = true)
                    override fun copy() = this@BaseEditorActivity.copy(cut = false)
                    override fun paste() = editListener.onPaste()
                }, loadFunctions = { onLoaded ->
                    lifecycleScope.launch { onLoaded(withContext(EditorEngine.dispatcher) { openedDocument.functions() }) }
                })
                if (isEditable) spreadsheetUi?.enableEditing()
            } else {
                documentView.setPages(opened.second, ::renderTile, lifecycleScope)
            }
            // Viewing only: text can still be selected, copied and found
            if (!isSpreadsheet) {
                documentView.editListener = editListener
                documentView.readOnly = !isEditable
            }
            showToolbar(isEditable)
            if (editorsType == EditorsType.PRESENTATION) {
                val firstSlide = opened.second.firstOrNull()
                val ui = SlidesUi(
                    this@BaseEditorActivity, lifecycleScope, { document }, { command -> edit(command = command) },
                    render = { page, width, height -> renderTile(page, width, height, 0, 0, width, height) },
                    slideAspect = { firstSlide?.let { (it.widthMm / it.heightMm).toFloat() } ?: (16f / 9f) },
                    editable = isEditable
                )
                val root = findViewById<ViewGroup>(R.id.editorRoot)
                root.addView(ui.strip, root.indexOfChild(findViewById(R.id.editorToolbar)))
                slidesUi = ui
                findViewById<View>(R.id.toolSlideshow).isVisible = true
                findViewById<View>(R.id.toolSlideshow).setOnClickListener { ui.startSlideshow() }
            }
            if (isEditable && editorsType == EditorsType.DOCS) {
                val ui = DocumentUi(this@BaseEditorActivity, lifecycleScope, documentView, { document }) { command -> edit(command = command) }
                val root = findViewById<ViewGroup>(R.id.editorRoot)
                root.addView(ui.bar, root.indexOfChild(findViewById(R.id.editorToolbar)))
                documentUi = ui
            }
            if (isEditable) {
                bindToolbar()
                onBackPressedDispatcher.addCallback(this@BaseEditorActivity) { leave() }
            }
            // After leaving's back callback: back closes the search bar first
            addSearchBar(object : SearchBar.Target {
                override val canReplace = isEditable
                override fun find(text: String, matchCase: Boolean, wholeWords: Boolean, forward: Boolean) =
                    edit { find(text, matchCase, wholeWords, forward) }
                override fun replace(text: String, replacement: String, matchCase: Boolean, wholeWords: Boolean, all: Boolean) =
                    edit { replace(text, replacement, matchCase, wholeWords, all) }
                override fun close() = edit { endFind() }
            })
            applyState(opened.third)
        }
    }

    // ---------------------------------------------------------------------------------
    // PDF, XPS and DjVu: drawn by core's readers, without sdkjs

    private fun showDrawingViewer(uri: Uri) {
        setUpViewer()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { copyToCache(uri) }.getOrNull() }
            if (file == null) {
                Log.e(TAG, "Can't read $uri")
                finish()
                return@launch
            }
            openDrawingFile(uri, file, password = "")
        }
    }

    private fun openDrawingFile(uri: Uri, file: File, password: String) {
        lifecycleScope.launch {
            val opened = withContext(EditorEngine.dispatcher) {
                val start = SystemClock.elapsedRealtime()
                val result = EditorEngine.openDrawingFile(this@BaseEditorActivity, file, password)
                val pages = (result as? DrawingFileResult.Opened)?.document?.let { document ->
                    (0 until document.pageCount).map { document.pageSize(it) }
                }
                Log.i(TAG, "open ${file.extension}: ${SystemClock.elapsedRealtime() - start} ms, ${pages?.size} pages")
                result to pages
            }
            when (val result = opened.first) {
                is DrawingFileResult.Opened -> {
                    if (isDestroyed) {
                        withContext(EditorEngine.dispatcher) { result.document.close() }
                        return@launch
                    }
                    drawingDocument = result.document
                    setResult(RESULT_OK, Intent().setData(uri).putExtra(EditorsContract.EXTRA_IS_MODIFIED, false))
                    findViewById<View>(R.id.documentProgress).isVisible = false
                    val pages = opened.second.orEmpty()
                    documentView.setPages(pages, ::renderTile, lifecycleScope)
                    val pdfText = PdfText(lifecycleScope, result.document, pages.size,
                        onState = { state ->
                            documentView.setEditorState(state)
                            searchBar?.update(state.search)
                        },
                        onLink = { url -> showLink(url, url?.let { { openUrl(it) } }) },
                        onCopy = { text ->
                            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
                        })
                    documentView.editListener = pdfText
                    documentView.readOnly = true
                    addSearchBar(pdfText)
                    showToolbar(editable = false)
                }
                DrawingFileResult.NeedsPassword -> askPassword(wrong = password.isNotEmpty()) { openDrawingFile(uri, file, it) }
                DrawingFileResult.Failed -> finish()
            }
        }
    }

    private fun askPassword(wrong: Boolean, onPassword: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val container = FrameLayout(this).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(Resources.string.editors_password_title)
            .setMessage(if (wrong) Resources.string.editors_password_wrong else Resources.string.editors_password_message)
            .setView(container)
            .setPositiveButton(Resources.string.editors_open) { _, _ -> onPassword(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun isEditable(): Boolean {
        @Suppress("DEPRECATION")
        val editType = intent.getSerializableExtra(EditorsContract.KEY_EDIT_TYPE) as? EditType
        return editType == null || editType is EditType.Edit
    }

    // ---------------------------------------------------------------------------------
    // Editing: input goes to the engine in order, its state comes back to the view

    private val editListener = object : DocumentView.EditListener {
        override fun onTouch(action: TouchAction, page: Int, xMm: Double, yMm: Double, clickCount: Int, addToSelection: Boolean) =
            edit { touch(action, page, xMm, yMm, clickCount, addToSelection) }

        override fun onKey(keyCode: Int, shift: Boolean, ctrl: Boolean) = edit { keyDown(keyCode, shift, ctrl) }

        override fun onCommitText(text: String) = edit { commitText(text) }

        override fun onComposingText(text: String) = edit { setComposingText(text) }

        override fun onFinishComposingText() = edit { finishComposingText() }

        override fun onSelect(startPage: Int, startXMm: Double, startYMm: Double, endPage: Int, endXMm: Double, endYMm: Double) =
            edit { select(startPage, startXMm, startYMm, endPage, endXMm, endYMm) }

        override fun onCut() = copy(cut = true)

        override fun onCopy() = copy(cut = false)

        override fun onPaste() {
            val content = EditorClipboard.get(this@BaseEditorActivity) ?: return
            edit { paste(content.text, content.internal) }
        }

        override fun onSelectAll() = edit { selectAll() }
    }

    private fun copy(cut: Boolean) = edit {
        val content = copy()
        if (content.text.isNotEmpty() || content.internal.isNotEmpty()) {
            EditorClipboard.put(this@BaseEditorActivity, content)
        }
        if (cut) cut() else state()
    }

    private fun edit(isLayoutSlice: Boolean = false, command: EditorDocument.() -> EditorState) {
        lifecycleScope.launch {
            val result = withContext(EditorEngine.dispatcher) {
                val document = document ?: return@withContext null
                // A failed command changes nothing; editing goes on
                val state = runCatching { document.command() }.onFailure { Log.e(TAG, "command failed", it) }.getOrNull()
                    ?: return@withContext null
                state.error?.let { Log.e(TAG, "sdkjs: $it") }
                // Page count or sizes may change while editing
                val pages = if (state.layoutChanged) (0 until document.pageCount).map { document.pageSize(it) } else null
                state to pages
            }
            if (isLayoutSlice) layingOut = false
            if (result == null) return@launch
            if (!isSpreadsheet) result.second?.let { documentView.updatePages(it) }
            applyState(result.first)
        }
    }

    private fun applyState(state: EditorState) {
        if (isSpreadsheet) spreadsheetUi?.setState(state) else documentView.setEditorState(state)
        // The pages after the first ones are laid out in slices; input in between runs first
        if (state.layoutPending && !layingOut) {
            layingOut = true
            edit(isLayoutSlice = true) { tick(LAYOUT_SLICE_MS) }
        }
        modified = state.modified
        findViewById<ImageButton>(R.id.toolSave).imageAlpha = if (modified) 255 else 80
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
        // Formatting of pictures and shapes while some are selected
        findViewById<View>(R.id.toolShape).isVisible = isEditable() && state.drawings.any { !it.tracking }
        formatPanel.update(state.format)
        state.sheet?.let { cellFormatPanel.update(it.cellFormat) }
        searchBar?.update(state.search)
        slidesUi?.update(state)
        drawingUi.chart = state.chart
        documentUi?.let { ui ->
            ui.update(state)
            findViewById<View>(R.id.toolTable).isVisible = ui.isInTable
        }
        val link = state.link?.takeIf { state.selection.isEmpty() }
        showLink(link?.let { it.url.ifEmpty { it.anchor } }, link?.let { { edit { followLink() } } })
        state.openUrl?.let { openUrl(it) }
    }

    // ---------------------------------------------------------------------------------
    // Pictures and shapes

    private val formatPanel by lazy {
        FormatPanel(
            this, { command -> edit(command = command) },
            load = { onLoaded ->
                val document = document ?: return@FormatPanel
                lifecycleScope.launch {
                    val lists = withContext(EditorEngine.dispatcher) { document.fonts() to document.styles() }
                    onLoaded(lists.first, lists.second)
                }
            },
            withParagraphs = editorsType != EditorsType.CELLS,
            withStyles = editorsType == EditorsType.DOCS
        )
    }

    private val cellFormatPanel by lazy {
        CellFormatPanel(this, { command -> edit(command = command) }) { onLoaded ->
            val document = document ?: return@CellFormatPanel
            lifecycleScope.launch { onLoaded(withContext(EditorEngine.dispatcher) { document.fonts() }) }
        }
    }

    private val drawingUi by lazy {
        DrawingUi(
            this, { command -> edit(command = command) },
            { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, ::takePhoto,
            ChartUi(this, lifecycleScope, { document }) { command -> edit(command = command) }
        )
    }

    private val pickPicture = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) insertPicture(uri)
    }

    private val photoFile by lazy { File(cacheDir, "editor-camera/photo.jpg") }

    private val photo = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) insertPicture(Uri.fromFile(photoFile))
    }

    private fun takePhoto() {
        photoFile.parentFile?.mkdirs()
        photoFile.delete()
        // The app's FileProvider (documents-app-android) shares the cache directory
        val uri = FileProvider.getUriForFile(this, "$packageName.asc.provider", photoFile)
        runCatching { photo.launch(uri) }.onFailure { Log.e(TAG, "no camera", it) }
    }

    // Copies the picture into the document's images and inserts it at the cursor
    private fun insertPicture(uri: Uri) {
        val document = document ?: return
        lifecycleScope.launch {
            val directory = withContext(EditorEngine.dispatcher) { document.imagesDirectory() }
            val picture = withContext(Dispatchers.IO) {
                runCatching { DrawingUi.importPicture(this@BaseEditorActivity, uri, File(directory)) }
                    .onFailure { Log.e(TAG, "can't import $uri", it) }.getOrNull()
            }
            if (picture == null) {
                toast(getString(Resources.string.editors_picture_failed))
                return@launch
            }
            edit { insertImage(picture.first, picture.second, picture.third) }
        }
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
        if (editorsType == EditorsType.PRESENTATION) {
            findViewById<View>(R.id.toolAddSlide).isVisible = true
            findViewById<View>(R.id.toolDeleteSlide).isVisible = true
            bind(R.id.toolAddSlide) { addSlide() }
            bind(R.id.toolDeleteSlide) { deleteSlide() }
        }
        findViewById<View>(R.id.toolInsert).setOnClickListener { drawingUi.showInsertMenu(it) }
        documentUi?.let { ui ->
            findViewById<View>(R.id.toolDocument).isVisible = true
            findViewById<View>(R.id.toolDocument).setOnClickListener { ui.showDocumentMenu(it) }
            findViewById<View>(R.id.toolTable).setOnClickListener { ui.showTableMenu(it) }
        }
        findViewById<View>(R.id.toolFormat).isVisible = true
        findViewById<View>(R.id.toolFormat).setOnClickListener { if (isSpreadsheet) cellFormatPanel.show() else formatPanel.show() }
        findViewById<View>(R.id.toolShape).setOnClickListener { drawingUi.showShapeMenu(it) }
        findViewById<View>(R.id.toolSave).setOnClickListener {
            save { error -> if (error == 0) toast(getString(Resources.string.editors_saved)) else toast(saveErrorMessage(error)) }
        }
    }

    // ---------------------------------------------------------------------------------
    // Find, links

    // The toolbar; viewing only, it has just the search
    private fun showToolbar(editable: Boolean) {
        val toolbar = findViewById<View>(R.id.editorToolbar)
        toolbar.isVisible = true
        if (!editable) {
            val buttons = findViewById<View>(R.id.toolSearch).parent as ViewGroup
            for (i in 0 until buttons.childCount) buttons.getChildAt(i).isVisible = buttons.getChildAt(i).id == R.id.toolSearch
        }
        findViewById<View>(R.id.toolSearch).setOnClickListener { searchBar?.show() }
    }

    private fun addSearchBar(target: SearchBar.Target) {
        val bar = SearchBar(this, target)
        findViewById<ViewGroup>(R.id.editorRoot).addView(bar.view, 0)
        searchBar = bar
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (bar.isShown) {
                    bar.hide()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun createLinkBar(): View = layoutInflater.inflate(R.layout.editor_link_bar, findViewById(R.id.editorRoot), false)

    /** Shows the link (its address or place) with a button that opens it; hides it for null. */
    private fun showLink(text: String?, onOpen: (() -> Unit)?) {
        linkBar.isVisible = text != null && onOpen != null
        if (text == null || onOpen == null) return
        linkBar.findViewById<TextView>(R.id.linkText).text = text
        linkBar.findViewById<View>(R.id.linkOpen).setOnClickListener { onOpen() }
    }

    private fun openUrl(url: String) {
        val address = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) url else "https://$url"
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
            .onFailure { toast(getString(Resources.string.editors_link_failed)) }
    }

    // ---------------------------------------------------------------------------------
    // Saving

    override fun onStop() {
        super.onStop()
        // The app may be closed in the background: keep the edits
        if (modified && !isChangingConfigurations) save {}
    }

    /** Leaves the editor, saving the edits first; asks what to do if that doesn't work. */
    private fun leave() {
        if (!modified) {
            finish()
            return
        }
        save { error ->
            if (error == 0) {
                finish()
            } else if (!isFinishing) {
                AlertDialog.Builder(this)
                    .setTitle(Resources.string.editors_leave_unsaved_title)
                    .setMessage(saveErrorMessage(error))
                    .setPositiveButton(Resources.string.editors_leave) { _, _ -> finish() }
                    .setNegativeButton(Resources.string.editors_stay, null)
                    .show()
            }
        }
    }

    /**
     * Saves into the document the app passed. [onDone] gets 0, the converter's error code,
     * [UNSUPPORTED_FORMAT] or [WRITE_ERROR].
     */
    private fun save(onDone: (Int) -> Unit) {
        val uri = documentUri ?: return
        // Taken now: onDestroy forgets it, but closes it only after this save (same thread)
        val document = document ?: return
        if (saving) return
        if (extension !in WRITABLE_FORMATS) return onDone(UNSUPPORTED_FORMAT)
        saving = true
        // Not tied to the activity: saving finishes even if it is destroyed meanwhile
        CoroutineScope(Dispatchers.Main).launch {
            val (error, state) = withContext(EditorEngine.dispatcher) { saveInto(document, uri) to document.state() }
            saving = false
            if (error == 0) {
                setResult(RESULT_OK, Intent().setData(uri).putExtra(EditorsContract.EXTRA_IS_MODIFIED, true))
            } else {
                Log.e(TAG, "save failed: $error")
            }
            if (!isDestroyed) applyState(state)
            onDone(error)
        }
    }

    // Runs on the engine's thread: x2t writes a file, which then replaces the document
    private fun saveInto(document: EditorDocument, uri: Uri): Int {
        val start = SystemClock.elapsedRealtime()
        val dir = File(cacheDir, "editor-output").apply { mkdirs() }
        val file = File(dir, "document.$extension")
        file.delete()
        val error = document.save(file.path, extension)
        if (error != 0) return error
        val written = runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } }
        }.getOrNull() != null
        file.delete()
        if (!written) {
            // sdkjs considers the document saved
            document.execute("Asc.editor.SetDocumentModified(true)")
            return WRITE_ERROR
        }
        Log.i(TAG, "saved: ${SystemClock.elapsedRealtime() - start} ms")
        return 0
    }

    private fun saveErrorMessage(error: Int): String =
        if (error == UNSUPPORTED_FORMAT) getString(Resources.string.editors_save_unsupported, extension)
        else getString(Resources.string.editors_save_failed, error)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private suspend fun renderTile(page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, width: Int, height: Int): Bitmap? =
        withContext(EditorEngine.dispatcher) {
            val document = document ?: drawingDocument ?: return@withContext null
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
        val ENGINE_EDITORS = setOf(EditorsType.DOCS, EditorsType.PRESENTATION, EditorsType.CELLS)
        const val LAYOUT_SLICE_MS = 30
        const val UNSUPPORTED_FORMAT = -1
        const val WRITE_ERROR = -2
    }
}
