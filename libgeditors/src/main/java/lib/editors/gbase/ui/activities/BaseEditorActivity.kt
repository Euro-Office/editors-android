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
import lib.editors.base.engine.DocumentResult
import lib.editors.base.engine.FormField
import lib.editors.base.engine.PageSize
import lib.editors.base.engine.PdfImage
import lib.editors.base.engine.PdfMarkup
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
    // The document's name without extension, the copies saved next to it for the app to
    // upload (EditorsContract.EXTRA_SAVED_FILES) and whether the document itself was saved
    private var baseName = "document"
    private val savedCopies = mutableListOf<Uri>()
    private var documentSaved = false
    private var editing = false

    // PDF files: the current copy (changes are saved into new copies) and whether it changed
    private var pdfFile: File? = null
    private var pdfPassword = ""
    private var pdfEdited = false
    private var pdfText: PdfText? = null
    private val hiddenTools = mutableListOf<View>()
    private var documentUi: DocumentUi? = null
    private var commentsUi: CommentsUi? = null
    private var formsUi: FormsUi? = null
    // A picture field waiting for a picture from the gallery
    private var pictureField: FormField? = null
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
        onBackPressedDispatcher.addCallback(this) { onBack() }
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
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { copyToCache(uri) }.getOrNull() }
            if (file == null) {
                Log.e(TAG, "Can't read $uri")
                finish()
                return@launch
            }
            openDocument(uri, file, password = "")
        }
    }

    // Encrypted files ask for their password and are opened again with it
    private fun openDocument(uri: Uri, file: File, password: String) {
        val progress = findViewById<View>(R.id.documentProgress)
        val isEditable = isEditable()

        lifecycleScope.launch {
            var needsPassword = false
            val opened = withContext(EditorEngine.dispatcher) {
                var start = SystemClock.elapsedRealtime()
                if (!EditorEngine.initialize(this@BaseEditorActivity)) return@withContext null
                Log.i(TAG, "engine ready: ${SystemClock.elapsedRealtime() - start} ms")

                start = SystemClock.elapsedRealtime()
                val document = when (val result = EditorEngine.openDocument(this@BaseEditorActivity, file, password)) {
                    is DocumentResult.Opened -> result.document
                    DocumentResult.NeedsPassword -> {
                        needsPassword = true
                        return@withContext null
                    }
                    DocumentResult.Failed -> return@withContext null
                }
                val pages = (0 until document.pageCount).map { document.pageSize(it) }
                Log.i(TAG, "open + layout: ${SystemClock.elapsedRealtime() - start} ms, ${pages.size} pages")
                Triple(document, pages, document.state())
            }
            if (needsPassword) {
                askPassword(wrong = password.isNotEmpty()) { openDocument(uri, file, it) }
                return@launch
            }
            if (opened == null) {
                finish()
                return@launch
            }

            document = opened.first
            documentUri = uri
            updateResult()
            extension = file.extension.lowercase()
            baseName = file.nameWithoutExtension
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
            } else {
                documentView.setPages(opened.second, ::renderTile, lifecycleScope)
            }
            // Viewing: text can still be selected, copied and found
            if (!isSpreadsheet) {
                documentView.editListener = editListener
                documentView.readOnly = true
            }
            showToolbar(editable = false)
            findViewById<View>(R.id.toolFile).isVisible = true
            findViewById<View>(R.id.toolFile).setOnClickListener { fileUi.showMenu(it) }
            // Comments: written when editing or with comment access (KEY_EDIT_ACCESS), read otherwise
            val comments = CommentsUi(
                this@BaseEditorActivity, lifecycleScope, { document }, { command -> edit(command = command) },
                canComment = isEditable || editAccess() == EditorsContract.INTERNAL_EDIT_ACCESS_COMMENT,
                fileId = intent.getStringExtra(EditorsContract.EXTRA_ITEM_ID)
            )
            commentsUi = comments
            findViewById<View>(R.id.toolComments).isVisible = true
            findViewById<View>(R.id.toolComments).setOnClickListener { comments.show() }
            if (editorsType == EditorsType.PRESENTATION) {
                val firstSlide = opened.second.firstOrNull()
                val ui = SlidesUi(
                    this@BaseEditorActivity, lifecycleScope, { document }, { command -> edit(command = command) },
                    render = { page, width, height -> renderTile(page, width, height, 0, 0, width, height) },
                    slideAspect = { firstSlide?.let { (it.widthMm / it.heightMm).toFloat() } ?: (16f / 9f) },
                    editable = false
                )
                val root = findViewById<ViewGroup>(R.id.editorRoot)
                root.addView(ui.strip, root.indexOfChild(findViewById(R.id.editorToolbar)))
                slidesUi = ui
                findViewById<View>(R.id.toolSlideshow).isVisible = true
                findViewById<View>(R.id.toolSlideshow).setOnClickListener { ui.startSlideshow() }
            }
            addSearchBar(object : SearchBar.Target {
                override val canReplace get() = editing
                override fun find(text: String, matchCase: Boolean, wholeWords: Boolean, forward: Boolean) =
                    edit { find(text, matchCase, wholeWords, forward) }
                override fun replace(text: String, replacement: String, matchCase: Boolean, wholeWords: Boolean, all: Boolean) =
                    edit { replace(text, replacement, matchCase, wholeWords, all) }
                override fun close() = edit { endFind() }
            })
            applyState(opened.third)
            if (editorsType == EditorsType.DOCS && isEditable) {
                val forms = FormsUi(this@BaseEditorActivity, { command -> edit(command = command) },
                    pickPicture = { field ->
                        pictureField = field
                        pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onStartFilling = if (startsFilling()) ({ startFilling() }) else null)
                val root = findViewById<ViewGroup>(R.id.editorRoot)
                root.addView(forms.bar, root.indexOfChild(findViewById(R.id.editorToolbar)))
                formsUi = forms
            }
            // The app may ask to show the document first (EditType.Edit.initialView): editing then starts with a button
            if (isEditable && (!startsInView() || fillsForms() || startsFilling())) {
                enableEditing()
            } else if (isEditable) {
                findViewById<View>(R.id.toolEdit).isVisible = true
                findViewById<View>(R.id.toolEdit).setOnClickListener { enableEditing() }
            }
        }
    }

    /** From viewing to editing the document. */
    private fun enableEditing() {
        if (editing || document == null) return
        editing = true
        findViewById<View>(R.id.toolEdit).isVisible = false
        showToolbar(editable = true)
        if (isSpreadsheet) spreadsheetUi?.enableEditing() else documentView.readOnly = false
        slidesUi?.editable = true
        if (editorsType == EditorsType.DOCS) {
            val ui = DocumentUi(this, lifecycleScope, documentView, { document }) { command -> edit(command = command) }
            val root = findViewById<ViewGroup>(R.id.editorRoot)
            root.addView(ui.bar, root.indexOfChild(findViewById(R.id.editorToolbar)))
            documentUi = ui
            // Reviewers' changes are always tracked
            if (editAccess() == EditorsContract.INTERNAL_EDIT_ACCESS_TRACKED_CHANGES) {
                ui.trackChangesForced = true
                edit { setTrackChanges(true) }
            }
        }
        bindToolbar()
        if (fillsForms()) edit { setFillMode(true) }
    }

    // Sends the form to its roles: the app's StartFillingActivity assigns people to them
    private fun startFilling() {
        val document = document ?: return
        lifecycleScope.launch {
            val roles = withContext(EditorEngine.dispatcher) { runCatching { document.formRoles() }.getOrDefault(emptyList()) }
            val json = org.json.JSONObject().put("formRoles", org.json.JSONArray(roles.map { role ->
                org.json.JSONObject().put("name", role.name)
                    .put("color", (role.color.toIntOrNull(16) ?: 0) or 0xFF000000.toInt())
                    .put("fieldCount", role.fieldCount)
            })).toString()
            val request = Intent().setClassName(this@BaseEditorActivity, EditorsContract.START_FILLING_CLASSNAME)
                .putExtra(EditorsContract.EXTRA_FORM_ROLES, json)
                .putExtra(EditorsContract.EXTRA_ROOM_ID, intent.getStringExtra(EditorsContract.EXTRA_ROOM_ID))
                .putExtra(EditorsContract.EXTRA_ITEM_ID, intent.getStringExtra(EditorsContract.EXTRA_ITEM_ID))
            runCatching { startFillingResult.launch(request) }.onFailure { toast(getString(Resources.string.editors_forms_start_failed)) }
        }
    }

    private val startFillingResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(EditorsContract.RESULT_START_FILLING_COMPLETE, Intent().setData(documentUri))
            finish()
        }
    }

    private fun editAccess() = intent.getIntExtra(EditorsContract.KEY_EDIT_ACCESS, EditorsContract.INTERNAL_EDIT_ACCESS_EDIT)

    private fun startsInView(): Boolean {
        @Suppress("DEPRECATION")
        return (intent.getSerializableExtra(EditorsContract.KEY_EDIT_TYPE) as? EditType.Edit)?.initialView == true
    }

    // Back closes the search first; changes (edits, or comments when only commenting) are saved before leaving
    private fun onBack() {
        if (searchBar?.isShown == true) searchBar?.hide() else leave()
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
                    documentUri = uri
                    pdfFile = file
                    pdfPassword = password
                    setResult(RESULT_OK, Intent().setData(uri).putExtra(EditorsContract.EXTRA_IS_MODIFIED, false))
                    findViewById<View>(R.id.documentProgress).isVisible = false
                    val pages = opened.second.orEmpty()
                    documentView.setPages(pages, ::renderTile, lifecycleScope)
                    val pdfText = PdfText(lifecycleScope, result.document, pages.size,
                        onState = { state ->
                            documentView.setEditorState(state)
                            searchBar?.update(state.search)
                        },
                        // Places in the file are shown at once; web addresses in the link bar
                        onLink = { link ->
                            if (link != null && link.destPage >= 0) {
                                showLink(null, null)
                                documentView.goToPage(link.destPage, link.destY)
                            } else {
                                showLink(link?.url, link?.url?.let { url -> { openUrl(url) } })
                            }
                        },
                        onCopy = { text ->
                            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
                        })
                    documentView.editListener = pdfText
                    documentView.readOnly = true
                    addSearchBar(pdfText)
                    showToolbar(editable = false)
                    showPageTools(result.document, pages)
                    this@BaseEditorActivity.pdfText = pdfText
                    if (file.extension.equals("pdf", ignoreCase = true) && isEditable()) enablePdfChanges(pdfText)
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
        // Reading and commenting only (KEY_EDIT_ACCESS) keep the document as it is
        val access = editAccess()
        val accessEdits = access == EditorsContract.INTERNAL_EDIT_ACCESS_EDIT ||
            access == EditorsContract.INTERNAL_EDIT_ACCESS_TRACKED_CHANGES ||
            access == EditorsContract.INTERNAL_EDIT_ACCESS_CUSTOM_FILTER ||
            access == EditorsContract.INTERNAL_EDIT_ACCESS_FILLING_FORMS
        return (editType == null || editType is EditType.Edit || editType is EditType.Fill || editType is EditType.StartFilling) && accessEdits
    }

    // Only form fields take input: filling (EditType.Fill, form filling access) and finished forms (oform, PDF forms)
    private fun fillsForms(): Boolean {
        @Suppress("DEPRECATION")
        val editType = intent.getSerializableExtra(EditorsContract.KEY_EDIT_TYPE) as? EditType
        return editType is EditType.Fill || editAccess() == EditorsContract.INTERNAL_EDIT_ACCESS_FILLING_FORMS ||
            extension == "oform" || extension == "pdf"
    }

    private fun startsFilling(): Boolean {
        @Suppress("DEPRECATION")
        return intent.getSerializableExtra(EditorsContract.KEY_EDIT_TYPE) is EditType.StartFilling
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
        commentsUi?.onState()
        formsUi?.update(state)
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
        if (uri != null) insertPicture(uri) else pictureField = null
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
            val field = pictureField
            pictureField = null
            if (field != null) {
                edit { setFormPicture(field, picture.first, picture.second, picture.third) }
            } else {
                edit { insertImage(picture.first, picture.second, picture.third) }
            }
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

    // PDF files: marking selected text up and signing, written into the file
    private fun enablePdfChanges(text: PdfText) {
        fun markup(type: Int, color: FloatArray) = lifecycleScope.launch {
            applyPdfChanges(text.takeSelectionMarkups(type, color), emptyList())
        }
        documentView.selectionActions = listOf(
            getString(Resources.string.editors_pdf_highlight) to { markup(8, floatArrayOf(1f, 0.92f, 0.23f)) },
            getString(Resources.string.editors_pdf_underline) to { markup(9, floatArrayOf(0.2f, 0.4f, 0.85f)) },
            getString(Resources.string.editors_pdf_strikeout) to { markup(11, floatArrayOf(0.85f, 0.15f, 0.15f)) }
        )
        findViewById<View>(R.id.toolSign).isVisible = true
        findViewById<View>(R.id.toolSign).setOnClickListener {
            SignaturePad(this) { picture, aspect ->
                toast(getString(Resources.string.editors_sign_tap))
                text.onPlace = { page, x, y ->
                    val width = 50f
                    val height = width / aspect
                    applyPdfChanges(emptyList(), listOf(PdfImage(page, picture.path, x - width / 2, y - height / 2, width, height)))
                }
            }.show()
        }
    }

    // Saves the PDF with the changes into a new copy and shows that one
    private fun applyPdfChanges(markups: List<PdfMarkup>, images: List<PdfImage>) {
        if (markups.isEmpty() && images.isEmpty()) return
        val document = drawingDocument ?: return
        val uri = documentUri ?: return
        val current = pdfFile ?: return
        val text = pdfText ?: return
        // Not drawn while it is saved and closed
        drawingDocument = null
        val page = documentView.visiblePage()
        lifecycleScope.launch {
            val target = File(cacheDir, "pdf-edit/edit-${System.currentTimeMillis()}.pdf").apply { parentFile?.mkdirs() }
            val author = getSharedPreferences("lib.editors.comments", MODE_PRIVATE).getString("user_name", null).orEmpty()
            val error = withContext(EditorEngine.dispatcher) { document.saveWithChanges(target.path, markups, images, author) }
            if (error != 0) toast(getString(Resources.string.editors_save_failed, error))
            val file = if (error == 0) target else current
            val reopened = withContext(EditorEngine.dispatcher) {
                (EditorEngine.openDrawingFile(this@BaseEditorActivity, file, pdfPassword) as? DrawingFileResult.Opened)?.document
            }
            if (reopened == null) {
                finish()
                return@launch
            }
            drawingDocument = reopened
            if (error == 0) {
                pdfFile = target
                pdfEdited = true
                setResult(RESULT_OK, Intent().setData(uri).putExtra(EditorsContract.EXTRA_IS_MODIFIED, true))
            }
            text.replaceDocument(reopened)
            documentView.reloadPages()
            documentView.goToPage(page)
        }
    }

    // PDF, XPS and DjVu: page thumbnails and the outline with going to a page
    private fun showPageTools(file: PagedDocument, pages: List<PageSize>) {
        val strip = PageStrip(this, lifecycleScope, pages,
            render = { page, width, height -> renderTile(page, width, height, 0, 0, width, height) },
            onPage = { documentView.goToPage(it) })
        val root = findViewById<ViewGroup>(R.id.editorRoot)
        root.addView(strip.view, root.indexOfChild(findViewById(R.id.editorToolbar)))
        documentView.onVisiblePageChanged = { strip.setCurrent(it) }
        findViewById<View>(R.id.toolPages).isVisible = true
        findViewById<View>(R.id.toolPages).setOnClickListener { strip.toggle() }
        findViewById<View>(R.id.toolOutline).isVisible = true
        findViewById<View>(R.id.toolOutline).setOnClickListener {
            lifecycleScope.launch {
                val outline = withContext(EditorEngine.dispatcher) { file.fileOutline() }
                val labels = listOf(getString(Resources.string.editors_doc_go_to_page)) +
                    outline.map { "\u2003".repeat((it.level - 1).coerceAtLeast(0)) + it.title }
                AlertDialog.Builder(this@BaseEditorActivity)
                    .setTitle(Resources.string.editors_pages_contents)
                    .setItems(labels.toTypedArray()) { _, position ->
                        if (position == 0) askPage(pages.size) else outline[position - 1].let { documentView.goToPage(it.page, it.yMm) }
                    }
                    .show()
            }
        }
    }

    private fun askPage(pageCount: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((documentView.visiblePage() + 1).toString())
            selectAll()
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        AlertDialog.Builder(this)
            .setTitle(getString(Resources.string.editors_pages_go_to, pageCount))
            .setView(FrameLayout(this).apply {
                setPadding(padding, 0, padding, 0)
                addView(input)
            })
            .setPositiveButton(android.R.string.ok) { _, _ -> input.text.toString().toIntOrNull()?.let { documentView.goToPage(it - 1) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------------------
    // Find, links

    // The toolbar; viewing only, it has just the search
    private fun showToolbar(editable: Boolean) {
        val toolbar = findViewById<View>(R.id.editorToolbar)
        toolbar.isVisible = true
        val buttons = findViewById<View>(R.id.toolSearch).parent as ViewGroup
        if (!editable) {
            // The editing buttons come back when editing starts
            for (i in 0 until buttons.childCount) {
                val button = buttons.getChildAt(i)
                if (button.isVisible && button.id !in VIEWING_TOOLS) {
                    hiddenTools += button
                    button.isVisible = false
                }
            }
        } else {
            hiddenTools.forEach { it.isVisible = true }
            hiddenTools.clear()
        }
        findViewById<View>(R.id.toolSearch).setOnClickListener { searchBar?.show() }
    }

    private fun addSearchBar(target: SearchBar.Target) {
        val bar = SearchBar(this, target)
        findViewById<ViewGroup>(R.id.editorRoot).addView(bar.view, 0)
        searchBar = bar
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
        // A changed PDF goes back into the file the app gave
        if (pdfEdited) {
            val file = pdfFile
            val uri = documentUri
            pdfEdited = false
            lifecycleScope.launch {
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri!!, "wt")?.use { output -> file!!.inputStream().use { it.copyTo(output) } }
                    }.getOrNull() != null
                }
                if (!written) toast(getString(Resources.string.editors_save_failed, WRITE_ERROR))
                finish()
            }
            return
        }
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
        saving = true
        // Not tied to the activity: saving finishes even if it is destroyed meanwhile
        CoroutineScope(Dispatchers.Main).launch {
            val (error, state) = withContext(EditorEngine.dispatcher) { saveInto(document, uri) to document.state() }
            saving = false
            if (error == 0) {
                documentSaved = extension in WRITABLE_FORMATS
                updateResult()
            } else {
                Log.e(TAG, "save failed: $error")
            }
            if (!isDestroyed) applyState(state)
            onDone(error)
        }
    }

    // Runs on the engine's thread: x2t writes a file, which then replaces the document. Formats
    // the engine can't write (doc, xls, ppt, ...) are saved as a modern copy next to it instead.
    private fun saveInto(document: EditorDocument, uri: Uri): Int {
        if (extension !in WRITABLE_FORMATS) {
            val format = modernFormat()
            val dir = File(cacheDir, "editor-output").apply { mkdirs() }
            val file = File(dir, "$baseName.$format")
            file.delete()
            val error = document.save(file.path, format)
            if (error != 0) return error
            val message = deliverCopy(file, "$baseName.$format")
            runOnUiThread { toast(message) }
            return 0
        }
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

    private fun modernFormat() = when (editorsType) {
        EditorsType.CELLS -> "xlsx"
        EditorsType.PRESENTATION -> "pptx"
        else -> "docx"
    }

    // Formats for "save as": the document's own (if the engine writes it) and the common ones
    private fun copyFormats(): List<String> {
        val common = when (editorsType) {
            EditorsType.CELLS -> listOf("xlsx", "ods", "csv", "pdf")
            EditorsType.PRESENTATION -> listOf("pptx", "odp", "pdf")
            else -> listOf("docx", "odt", "rtf", "txt", "pdf")
        }
        return (listOfNotNull(extension.takeIf { it in WRITABLE_FORMATS }) + common).distinct()
    }

    /**
     * Puts a copy of the document next to it: in the same folder for files on the device, or
     * for the app to upload into the document's folder when the editor is closed. Returns a
     * message for the user.
     */
    private fun deliverCopy(file: File, name: String): String {
        val uri = documentUri
        val path = uri?.takeIf { it.scheme == "file" }?.path
        if (path != null) {
            val folder = File(path).parentFile
            if (folder != null && folder.canWrite()) {
                val target = uniqueFile(folder, name)
                file.copyTo(target, overwrite = true)
                return getString(Resources.string.editors_file_saved_next_to, target.name)
            }
        }
        val dir = File(cacheDir, "editor-copies").apply { mkdirs() }
        val target = File(dir, name)
        file.copyTo(target, overwrite = true)
        // The app's FileProvider (documents-app-android) shares the cache directory
        val copy = FileProvider.getUriForFile(this, "$packageName.asc.provider", target)
        if (copy !in savedCopies) savedCopies += copy
        runOnUiThread { updateResult() }
        return getString(Resources.string.editors_file_saved_on_close, name)
    }

    private fun uniqueFile(folder: File, name: String): File {
        var file = File(folder, name)
        var number = 2
        while (file.exists()) {
            file = File(folder, "${name.substringBeforeLast('.')} ($number).${name.substringAfterLast('.')}")
            number++
        }
        return file
    }

    private fun updateResult() {
        val uri = documentUri ?: return
        setResult(
            RESULT_OK,
            Intent().setData(uri)
                .putExtra(EditorsContract.EXTRA_IS_MODIFIED, documentSaved)
                .putParcelableArrayListExtra(EditorsContract.EXTRA_SAVED_FILES, ArrayList(savedCopies))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private val fileUi by lazy {
        FileUi(this, lifecycleScope, { document }, copyFormats(), { baseName }, ::deliverCopy)
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
        // Toolbar buttons also when viewing
        private val VIEWING_TOOLS = setOf(
            R.id.toolSearch, R.id.toolFile, R.id.toolComments, R.id.toolSlideshow, R.id.toolEdit, R.id.toolPages, R.id.toolOutline,
            R.id.toolSign
        )
        const val UNSUPPORTED_FORMAT = -1
        const val WRITE_ERROR = -2
    }
}
