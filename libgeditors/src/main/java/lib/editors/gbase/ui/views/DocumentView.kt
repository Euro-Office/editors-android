package lib.editors.gbase.ui.views

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.util.LruCache
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.ForeignCursor
import lib.editors.base.engine.PageSize
import lib.editors.base.engine.TextBlock
import lib.editors.base.engine.TouchAction
import lib.editors.resources.R as Resources
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows document pages below each other, with scrolling and zoom, and the cursor and
 * selection when editing.
 *
 * Each page is rendered once at the width of the view (zoom 1) and cached. When zoomed in,
 * the visible part of each page is rendered again at full resolution once the gesture ends.
 * Pages changed by an edit keep their old picture until the new one is ready.
 *
 * Text is selected by a long press (then drag), a double tap or the selection handles; a
 * floating menu offers cut, copy, paste and select all.
 *
 * Screen readers read the text of the visible pages by blocks (paragraphs, see [textBlocks]).
 *
 * A mouse or touchpad works as on a desktop: pressing and dragging selects text and moves
 * pictures and shapes, the right button opens the menu, the wheel scrolls (and zooms with Ctrl).
 */
class DocumentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Renders the area (x, y, width, height) of a page drawn at pageWidth x pageHeight pixels. */
    fun interface Renderer {
        suspend fun render(page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, width: Int, height: Int): Bitmap?
    }

    /** Input for the document, in page coordinates (millimeters) and web key codes. */
    interface EditListener {
        /**
         * [addToSelection]: adds a picture or shape to the selected ones; [extend]: extends the
         * selection to the point (see EditorDocument.touch).
         */
        fun onTouch(action: TouchAction, page: Int, xMm: Double, yMm: Double, clickCount: Int, addToSelection: Boolean = false, extend: Boolean = false)
        fun onKey(keyCode: Int, shift: Boolean, ctrl: Boolean)
        fun onCommitText(text: String)
        fun onComposingText(text: String)
        fun onFinishComposingText()
        /** Selects from the start to the end point: dragging a selection handle. */
        fun onSelect(startPage: Int, startXMm: Double, startYMm: Double, endPage: Int, endXMm: Double, endYMm: Double)
        fun onCut()
        fun onCopy()
        fun onPaste()
        fun onSelectAll()
    }

    // contentRect: in pixels of the zoomed content (screen position plus scroll position)
    private class Detail(val zoom: Float, val contentRect: Rect, val bitmap: Bitmap)

    // End of the selection at the start or end of a line, in page millimeters
    private class Handle(val page: Int, val xMm: Double, val topMm: Double, val bottomMm: Double) {
        val middleMm get() = (topMm + bottomMm) / 2
    }

    private val density = resources.displayMetrics.density
    private val pageMargin = 12 * density
    private val maxZoom = 5f

    private var pageSizes: List<PageSize> = emptyList()
    private var renderer: Renderer? = null
    private var scope: CoroutineScope? = null

    /** Editing: taps place the cursor and the keyboard sends text. Otherwise read-only. */
    var editListener: EditListener? = null

    /**
     * Viewing only: text can be selected and copied through [editListener], but nothing is
     * typed, pasted or moved.
     */
    var readOnly = false

    /**
     * The keyboard comes up only while there is a cursor (PDFs: in text boxes and form fields),
     * not for each tap.
     */
    var keyboardWithCursor = false

    /** Drawing with a pen (PDFs): one finger draws (its touches go to [editListener]), two scroll and zoom. */
    var penMode = false
        set(value) {
            field = value
            penDown = false
        }
    private var penDown = false

    /** More actions in the menu of a selection (label and action), such as marking text up. */
    var selectionActions: List<Pair<String, () -> Unit>> = emptyList()

    /**
     * Actions for the misspelled word at the cursor (suggestions, ignore, ...), first in the menu.
     * Set before [setEditorState]: a tap on such a word opens the menu.
     */
    var spellingActions: List<Pair<String, () -> Unit>> = emptyList()
        set(value) {
            if (value.map { it.first } == field.map { it.first }) {
                field = value
                return
            }
            field = value
            actionMode?.invalidate()
        }

    /** Screen readers: the text of a page in blocks (see PagedDocument.textBlocks). */
    var textBlocks: (suspend (page: Int) -> List<TextBlock>)? = null
        set(value) {
            field = value
            ViewCompat.setAccessibilityDelegate(this, if (value != null) accessibility else null)
        }
    private val accessibility = DocumentAccessibility()
    private val blocks = mutableMapOf<Int, List<TextBlock>>()
    private val blockJobs = mutableMapOf<Int, Job>()

    /** Called when another page comes into the middle of the view (scrolling, zooming). */
    var onVisiblePageChanged: ((Int) -> Unit)? = null
    private var lastVisiblePage = -1

    // Layout at zoom 1, in view pixels
    private var pageWidth = 0f
    private val pageTops = mutableListOf<Float>()
    private val pageHeights = mutableListOf<Float>()
    private var contentHeight = 0f

    private var zoom = 1f
    private var scrollPosX = 0f
    private var scrollPosY = 0f

    private val pageBitmaps = object : LruCache<Int, Bitmap>(pageCacheBytes()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val pageJobs = mutableMapOf<Int, Job>()
    // Incremented when a page changes; a render of an older version doesn't replace the picture
    private val pageVersions = mutableMapOf<Int, Int>()
    private val stalePages = mutableSetOf<Int>()
    private var details: List<Detail> = emptyList()
    private var detailJob: Job? = null

    private var editorState: EditorState? = null
    private var cursorShown = true
    private var selecting = false

    // Pictures and shapes: what a touch grabbed, and whether it is being dragged
    private val drawingHandles = DrawingHandles(context)
    private var drawingGrab: DrawingHandles.Grab? = null
    private var drawingDragging = false
    private var grabX = 0f
    private var grabY = 0f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val toScreen = DrawingHandles.ToScreen { page, xMm, yMm -> android.graphics.PointF(screenX(page, xMm), screenY(page, yMm)) }
    private var lastMoveTime = 0L

    // Mouse: a button is down; where and when the last click was, and how many in a row
    private var mouseDown = false
    private var mouseDragging = false
    private var mouseDownX = 0f
    private var mouseDownY = 0f
    private var mouseDownPoint: PagePoint? = null
    private var lastClickTime = 0L
    private var clickCount = 0
    // The last press came from a mouse: no selection handles, which are for fingers
    private var usingMouse = false
    private val detailsAfterWheel = Runnable { requestDetails() }

    // Dragging a selection handle: the other end stays, the dragged end follows the finger
    private var fixedHandle: Handle? = null
    private var handleOffsetX = 0f
    private var handleOffsetY = 0f

    private var actionMode: ActionMode? = null
    // The menu at the cursor (paste, select all) rather than for a selection
    private var insertionMenu = false
    // Select all from the menu: show the menu again for the new selection
    private var menuAfterUpdate = false

    private val background = ContextCompat.getColor(context, Resources.color.editors_document_background)
    private val handleRadius = 11 * density
    private val handleTouchRadius = 28 * density
    private val paperPaint = Paint().apply { color = Color.WHITE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 2 * density
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, Resources.color.editors_selection)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, Resources.color.editors_selection_handle)
        strokeWidth = 2 * density
    }
    // Others' cursors while co-editing: a line in their color with their name above it
    private val foreignCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * density }
    private val foreignNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11 * resources.displayMetrics.scaledDensity
    }
    private val scroller = OverScroller(context)

    private val blink = object : Runnable {
        override fun run() {
            cursorShown = !cursorShown
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            actionMode?.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
            scrollTo(scrollPosX + distanceX, scrollPosY + distanceY)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            scroller.fling(
                scrollPosX.roundToInt(), scrollPosY.roundToInt(), -velocityX.roundToInt(), -velocityY.roundToInt(),
                0, maxScrollX().roundToInt(), 0, maxScrollY().roundToInt()
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (editListener == null) return false
            hideActionMenu()
            if (readOnly) {
                click(e.x, e.y, 1)
                return true
            }
            // A tap on the cursor opens the menu (paste, select all)
            val onCursor = isNearCursor(e.x, e.y)
            click(e.x, e.y, 1)
            if (!keyboardWithCursor) showKeyboard()
            if (onCursor) showActionMenu(insertion = true) else menuAfterUpdate = true
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (editListener != null && !readOnly) {
                click(e.x, e.y, 2)
                menuAfterUpdate = true
            } else {
                zoomTo(if (zoom > 1.5f) 1f else 2.5f, e.x, e.y)
                requestDetails()
            }
            return true
        }

        // Long press selects a word, dragging extends the selection by words. With pictures or
        // shapes selected, it adds the one pressed to them.
        override fun onLongPress(e: MotionEvent) {
            val listener = editListener ?: return
            val point = toPage(e.x, e.y) ?: return
            if (visibleDrawings().isNotEmpty()) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener.onTouch(TouchAction.DOWN, point.page, point.xMm, point.yMm, 1, addToSelection = true)
                listener.onTouch(TouchAction.UP, point.page, point.xMm, point.yMm, 1, addToSelection = true)
                return
            }
            selecting = true
            hideActionMenu()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener.onTouch(TouchAction.DOWN, point.page, point.xMm, point.yMm, 2)
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setPages(sizes: List<PageSize>, renderer: Renderer, scope: CoroutineScope) {
        this.renderer = renderer
        this.scope = scope
        updatePages(sizes)
    }

    /** New page count or sizes after an edit. */
    fun updatePages(sizes: List<PageSize>) {
        if (sizes != pageSizes) {
            // Bitmaps of pages whose size changed no longer fit
            for (page in sizes.indices) {
                if (pageSizes.getOrNull(page) != sizes[page]) pageBitmaps.remove(page)
            }
            pageSizes = sizes
            layoutPages()
        }
        invalidate()
    }

    /** Shows the cursor and selection and draws changed pages again. */
    fun setEditorState(state: EditorState) {
        val previousCursor = editorState?.cursor
        val previousSearch = editorState?.search
        val previousPage = editorState?.currentPage
        editorState = state
        if (keyboardWithCursor && !readOnly && editListener != null && (state.cursor != null) != (previousCursor != null)) {
            if (state.cursor != null) showKeyboard()
            else context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        }
        for (page in state.dirtyPages) {
            if (page !in pageSizes.indices) continue
            pageVersions[page] = (pageVersions[page] ?: 0) + 1
            stalePages.add(page)
            blocks.remove(page)
        }
        if (state.dirtyPages.isNotEmpty()) invalidateAccessibility()
        // Sharp renders of the zoomed view are replaced once the new ones are ready
        if (state.dirtyPages.isNotEmpty()) requestDetails()
        restartBlink()
        // Only when it moved: states also come from layout in the background, while scrolling
        if (editListener != null && state.cursor != null && state.cursor != previousCursor) scrollToCursor()
        // Find selects the match
        if (state.search != null && state.search != previousSearch) scrollToSelection()
        // Presentations: a new slide, for example, becomes the current one
        if (previousPage != null && state.currentPage != previousPage && state.currentPage in pageSizes.indices) {
            showPage(state.currentPage)
        }
        val hasDrawings = visibleDrawings().any { !it.tracking }
        // A selected picture or shape takes no typing
        if (hasDrawings && state.cursor == null) {
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        }
        if (menuAfterUpdate && (state.selection.isNotEmpty() || hasDrawings)) {
            showActionMenu(insertion = false)
        } else if (menuAfterUpdate && spellingActions.isNotEmpty() && !readOnly) {
            showActionMenu(insertion = true)
        } else if (!insertionMenu && state.selection.isEmpty() && !hasDrawings) {
            hideActionMenu()
        }
        menuAfterUpdate = false
        actionMode?.invalidateContentRect()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            // Pages are rendered for the view width
            pageBitmaps.evictAll()
            details = emptyList()
        }
        layoutPages()
        // Keep the cursor visible when the keyboard opens
        if (h < oldh && editorState?.cursor != null) scrollToCursor()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blink)
        hideActionMenu()
    }

    private fun layoutPages() {
        pageTops.clear()
        pageHeights.clear()
        pageWidth = width - 2 * pageMargin
        var top = pageMargin
        for (size in pageSizes) {
            val height = (pageWidth * size.heightMm / size.widthMm).toFloat()
            pageTops.add(top)
            pageHeights.add(height)
            top += height + pageMargin
        }
        contentHeight = top
        scrollTo(scrollPosX, scrollPosY)
    }

    // ---------------------------------------------------------------------------------
    // Page coordinates

    private class PagePoint(val page: Int, val xMm: Double, val yMm: Double)

    /** Pixels per millimeter of a page on screen. */
    private fun scale(page: Int): Double = pageWidth * zoom / pageSizes[page].widthMm

    private fun pageLeft(): Float = pageMargin * zoom - scrollPosX

    private fun pageTop(page: Int): Float = pageTops[page] * zoom - scrollPosY

    /** The page at (or nearest to) a screen point, and the point in its millimeters. */
    private fun toPage(x: Float, y: Float): PagePoint? {
        if (pageSizes.isEmpty()) return null
        val contentY = (y + scrollPosY) / zoom
        var page = pageTops.indexOfLast { it <= contentY }.coerceAtLeast(0)
        if (page > 0 && contentY > pageTops[page] + pageHeights[page] + pageMargin / 2) page = min(page + 1, pageSizes.lastIndex)
        val scale = scale(page)
        return PagePoint(page, (x - pageLeft()) / scale, (y - pageTop(page)) / scale)
    }

    private fun screenX(page: Int, xMm: Double): Float = (pageLeft() + xMm * scale(page)).toFloat()

    private fun screenY(page: Int, yMm: Double): Float = (pageTop(page) + yMm * scale(page)).toFloat()

    private fun click(x: Float, y: Float, clickCount: Int) {
        val listener = editListener ?: return
        val point = toPage(x, y) ?: return
        listener.onTouch(TouchAction.DOWN, point.page, point.xMm, point.yMm, clickCount)
        listener.onTouch(TouchAction.UP, point.page, point.xMm, point.yMm, clickCount)
        // The cursor moved: the keyboard starts composing anew
        context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
    }

    private fun isNearCursor(x: Float, y: Float): Boolean {
        val cursor = editorState?.cursor ?: return false
        if (cursor.page !in pageSizes.indices || editorState?.selection?.isNotEmpty() == true) return false
        val cursorX = screenX(cursor.page, (cursor.x1 + cursor.x2) / 2)
        val cursorY = screenY(cursor.page, (cursor.y1 + cursor.y2) / 2)
        return hypot(x - cursorX, y - cursorY) < handleTouchRadius
    }

    /** Draws all pages again: the file changed (same page sizes). */
    fun reloadPages() {
        pageBitmaps.evictAll()
        details = emptyList()
        for (page in pageSizes.indices) {
            pageVersions[page] = (pageVersions[page] ?: 0) + 1
        }
        requestDetails()
        invalidate()
    }

    /** The page in the middle of the view. */
    fun visiblePage(): Int {
        if (pageTops.isEmpty()) return 0
        val contentY = (scrollPosY + height / 2) / zoom
        return pageTops.indexOfLast { it <= contentY }.coerceAtLeast(0)
    }

    /** The middle of the view: its page and the point there in millimeters. */
    fun visibleCenter(): Triple<Int, Double, Double>? = toPage(width / 2f, height / 2f)?.let { Triple(it.page, it.xMm, it.yMm) }

    /** Scrolls to the top of [page], or to [yMm] from it. */
    fun goToPage(page: Int, yMm: Float = 0f) {
        if (page !in pageSizes.indices) return
        val offset = if (yMm > 0f) (yMm * scale(page)).toFloat() - 48 * density else -pageMargin
        scrollTo(scrollPosX, pageTops[page] * zoom + offset)
        requestDetails()
    }

    /** Scrolls so that [page] is visible, if it isn't. */
    private fun showPage(page: Int) {
        val top = pageTop(page)
        val bottom = top + pageHeights[page] * zoom
        if (top >= 0 && bottom <= height) return
        scrollTo(scrollPosX, pageTops[page] * zoom - pageMargin)
        requestDetails()
    }

    /** Scrolls so that the start of the selection is visible, with some room around it. */
    fun scrollToSelection() {
        val area = editorState?.selection?.firstOrNull { it.page in pageSizes.indices } ?: return
        if (height == 0) return
        val xs = (0 until 4).map { screenX(area.page, area.points[2 * it]) }
        val ys = (0 until 4).map { screenY(area.page, area.points[2 * it + 1]) }
        val margin = 96 * density
        var dx = 0f
        var dy = 0f
        if (ys.max() > height - margin) dy = ys.max() - (height - margin) else if (ys.min() < margin) dy = ys.min() - margin
        if (xs.max() > width - margin / 2) dx = xs.max() - (width - margin / 2) else if (xs.min() < margin / 2) dx = xs.min() - margin / 2
        if (dx != 0f || dy != 0f) {
            scrollTo(scrollPosX + dx, scrollPosY + dy)
            requestDetails()
        }
    }

    private fun scrollToCursor() {
        val cursor = editorState?.cursor ?: return
        if (cursor.page !in pageSizes.indices || height == 0) return
        val top = min(screenY(cursor.page, cursor.y1), screenY(cursor.page, cursor.y2))
        val bottom = max(screenY(cursor.page, cursor.y1), screenY(cursor.page, cursor.y2))
        val x = screenX(cursor.page, cursor.x1)
        val margin = 48 * density
        var dx = 0f
        var dy = 0f
        if (bottom > height - margin) dy = bottom - (height - margin) else if (top < margin) dy = top - margin
        if (x > width - margin) dx = x - (width - margin) else if (x < margin) dx = x - margin
        if (dx != 0f || dy != 0f) {
            scrollTo(scrollPosX + dx, scrollPosY + dy)
            requestDetails()
        }
    }

    // ---------------------------------------------------------------------------------
    // Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && usingMouse != isMouse(event)) {
            usingMouse = isMouse(event)
            invalidate()
        }
        if (usingMouse) return handleMouse(event)
        if (penMode && handlePen(event)) return true
        if (handleDrawingTouch(event)) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN && startHandleDrag(event)) return true
        if (fixedHandle != null) {
            handleHandleDrag(event)
            return true
        }
        if (selecting) {
            handleSelectionDrag(event)
            return true
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && scroller.isFinished) requestDetails()
        return true
    }

    // A stroke of the pen; a second finger ends it and scrolls or zooms instead
    private fun handlePen(event: MotionEvent): Boolean {
        val listener = editListener ?: return false
        if (event.pointerCount > 1) {
            if (penDown) {
                penDown = false
                toPage(event.x, event.y)?.let { listener.onTouch(TouchAction.UP, it.page, it.xMm, it.yMm, 1) }
            }
            return false
        }
        val point = toPage(event.x, event.y) ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                penDown = true
                listener.onTouch(TouchAction.DOWN, point.page, point.xMm, point.yMm, 1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!penDown) return true
                val now = SystemClock.uptimeMillis()
                if (now - lastMoveTime < MOVE_INTERVAL_MS) return true
                lastMoveTime = now
                listener.onTouch(TouchAction.MOVE, point.page, point.xMm, point.yMm, 1)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!penDown) return true
                penDown = false
                listener.onTouch(TouchAction.UP, point.page, point.xMm, point.yMm, 1)
            }
        }
        return true
    }

    private fun handleSelectionDrag(event: MotionEvent) {
        val listener = editListener ?: return
        val point = toPage(event.x, event.y) ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // The engine needs a few milliseconds per move; don't queue up more than it can do
                val now = SystemClock.uptimeMillis()
                if (now - lastMoveTime < MOVE_INTERVAL_MS) return
                lastMoveTime = now
                listener.onTouch(TouchAction.MOVE, point.page, point.xMm, point.yMm, 2)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selecting = false
                listener.onTouch(TouchAction.UP, point.page, point.xMm, point.yMm, 2)
                menuAfterUpdate = true
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Mouse and touchpad

    // Editing: the presses go to the document as they are. Viewing: dragging selects from the
    // pressed point, a double click a word, a click clears the selection or follows a link.
    private fun handleMouse(event: MotionEvent): Boolean {
        val listener = editListener ?: return gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                val point = toPage(event.x, event.y) ?: return true
                if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                    showContextMenu(listener, point)
                    return true
                }
                if (event.buttonState != 0 && !event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) return true
                hideActionMenu()
                val near = hypot(event.x - mouseDownX, event.y - mouseDownY) < touchSlop
                clickCount = if (near && event.eventTime - lastClickTime < DOUBLE_CLICK_MS) clickCount % 3 + 1 else 1
                lastClickTime = event.eventTime
                mouseDownX = event.x
                mouseDownY = event.y
                mouseDownPoint = point
                mouseDown = true
                mouseDragging = false
                if (readOnly) {
                    requestFocus()
                    if (clickCount > 1) listener.onTouch(TouchAction.DOWN, point.page, point.xMm, point.yMm, 2)
                } else {
                    listener.onTouch(TouchAction.DOWN, point.page, point.xMm, point.yMm, clickCount, event.isCtrlDown, event.isShiftDown)
                    showKeyboard()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!mouseDown) return true
                if (!mouseDragging && hypot(event.x - mouseDownX, event.y - mouseDownY) < touchSlop) return true
                mouseDragging = true
                scrollAtEdges(event)
                val now = SystemClock.uptimeMillis()
                if (now - lastMoveTime < MOVE_INTERVAL_MS) return true
                lastMoveTime = now
                val point = toPage(event.x, event.y) ?: return true
                val start = mouseDownPoint
                if (!readOnly) {
                    listener.onTouch(TouchAction.MOVE, point.page, point.xMm, point.yMm, clickCount, event.isCtrlDown, event.isShiftDown)
                } else if (clickCount > 1) {
                    listener.onTouch(TouchAction.MOVE, point.page, point.xMm, point.yMm, 2)
                } else if (start != null) {
                    listener.onSelect(start.page, start.xMm, start.yMm, point.page, point.xMm, point.yMm)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!mouseDown) return true
                mouseDown = false
                val point = toPage(event.x, event.y) ?: return true
                if (!readOnly) {
                    listener.onTouch(TouchAction.UP, point.page, point.xMm, point.yMm, clickCount, event.isCtrlDown, event.isShiftDown)
                    context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
                } else if (clickCount > 1) {
                    listener.onTouch(TouchAction.UP, point.page, point.xMm, point.yMm, 2)
                } else if (!mouseDragging) {
                    listener.onTouch(TouchAction.DOWN, point.page, point.xMm, point.yMm, 1)
                    listener.onTouch(TouchAction.UP, point.page, point.xMm, point.yMm, 1)
                }
                requestDetails()
            }
        }
        return true
    }

    // The right button: the menu for the selection, or at the point clicked outside of it
    private fun showContextMenu(listener: EditListener, point: PagePoint) {
        val inSelection = editorState?.selection.orEmpty().any { area ->
            val xs = area.points.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = area.points.filterIndexed { i, _ -> i % 2 == 1 }
            area.page == point.page && point.xMm in xs.min()..xs.max() && point.yMm in ys.min()..ys.max()
        }
        if (inSelection || visibleDrawings().isNotEmpty()) {
            showActionMenu(insertion = false)
        } else if (!readOnly) {
            listener.onTouch(TouchAction.DOWN, point.page, point.xMm, point.yMm, 1)
            listener.onTouch(TouchAction.UP, point.page, point.xMm, point.yMm, 1)
            showKeyboard()
            showActionMenu(insertion = true)
        }
    }

    // Dragging a selection past the top or bottom scrolls on
    private fun scrollAtEdges(event: MotionEvent) {
        val edge = 32 * density
        val dy = when {
            event.y > height - edge -> 16 * density
            event.y < edge -> -16 * density
            else -> 0f
        }
        if (dy != 0f) scrollTo(scrollPosX, scrollPosY + dy)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL || !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            return super.onGenericMotionEvent(event)
        }
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (event.isCtrlDown) {
            zoomTo(zoom * (1f + WHEEL_ZOOM_STEP * vertical), event.x, event.y)
        } else {
            val step = android.view.ViewConfiguration.get(context).scaledVerticalScrollFactor
            if (event.isShiftDown) {
                scrollTo(scrollPosX - vertical * step, scrollPosY)
            } else {
                scrollTo(scrollPosX + horizontal * step, scrollPosY - vertical * step)
            }
        }
        actionMode?.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
        removeCallbacks(detailsAfterWheel)
        postDelayed(detailsAfterWheel, WHEEL_DETAILS_DELAY_MS)
        return true
    }

    // Text cursor over pages, crosshair over the handles of pictures and shapes, move over their frame
    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val point = toPage(event.getX(pointerIndex), event.getY(pointerIndex))
        if (editListener == null || point == null || point.xMm < 0 || point.yMm < 0 ||
            point.xMm > pageSizes[point.page].widthMm || point.yMm > pageSizes[point.page].heightMm
        ) {
            return super.onResolvePointerIcon(event, pointerIndex)
        }
        val grab = visibleDrawings().takeIf { !readOnly && it.isNotEmpty() }
            ?.let { drawingHandles.hitTest(it, event.getX(pointerIndex), event.getY(pointerIndex), toScreen) }
        val type = when {
            grab == null -> PointerIcon.TYPE_TEXT
            grab.immediate -> PointerIcon.TYPE_CROSSHAIR
            else -> PointerIcon.TYPE_ALL_SCROLL
        }
        return PointerIcon.getSystemIcon(context, type)
    }

    // ---------------------------------------------------------------------------------
    // Pictures and shapes: dragging their handles or frame

    private fun visibleDrawings() = editorState?.drawings.orEmpty().filter { it.page in pageSizes.indices }

    // True if the event belongs to a drag of a picture or shape
    private fun handleDrawingTouch(event: MotionEvent): Boolean {
        val listener = editListener?.takeIf { !readOnly } ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawingDragging = false
                drawingGrab = visibleDrawings().takeIf { it.isNotEmpty() }?.let { drawingHandles.hitTest(it, event.x, event.y, toScreen) }
                val grab = drawingGrab ?: return false
                grabX = event.x
                grabY = event.y
                if (grab.immediate) {
                    startDrawingDrag(listener, grab)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val grab = drawingGrab ?: return false
                if (!drawingDragging) {
                    if (hypot(event.x - grabX, event.y - grabY) < touchSlop) return false
                    // A drag, not a tap: the gestures don't get the rest
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    gestureDetector.onTouchEvent(cancel)
                    cancel.recycle()
                    startDrawingDrag(listener, grab)
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastMoveTime < MOVE_INTERVAL_MS) return true
                lastMoveTime = now
                val (x, y) = dragTarget(grab, event)
                listener.onTouch(TouchAction.MOVE, grab.page, x, y, 1)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val grab = drawingGrab
                drawingGrab = null
                if (grab == null || !drawingDragging) return false
                drawingDragging = false
                val (x, y) = dragTarget(grab, event)
                listener.onTouch(TouchAction.UP, grab.page, x, y, 1)
                menuAfterUpdate = true
                return true
            }
        }
        return drawingDragging
    }

    private fun startDrawingDrag(listener: EditListener, grab: DrawingHandles.Grab) {
        drawingDragging = true
        hideActionMenu()
        parent?.requestDisallowInterceptTouchEvent(true)
        listener.onTouch(TouchAction.DOWN, grab.page, grab.xMm, grab.yMm, 1)
    }

    // The grabbed point moved by the finger's offset, in page millimeters
    private fun dragTarget(grab: DrawingHandles.Grab, event: MotionEvent): Pair<Double, Double> {
        val scale = scale(grab.page)
        return (grab.xMm + (event.x - grabX) / scale) to (grab.yMm + (event.y - grabY) / scale)
    }

    // ---------------------------------------------------------------------------------
    // Selection handles

    /** Handles at the start and end of the selection, or null without a selection. */
    private fun selectionHandles(): Pair<Handle, Handle>? {
        val areas = editorState?.selection?.filter { it.page in pageSizes.indices }
        if (editListener == null || usingMouse || areas.isNullOrEmpty()) return null
        // Areas are in document order; corners clockwise from the top left
        val first = areas.first().points
        val last = areas.last().points
        return Handle(areas.first().page, first[6], first[1], first[7]) to
            Handle(areas.last().page, last[4], last[3], last[5])
    }

    // Teardrops as on Android: the start handle hangs to the left of its line, the end handle to the right
    private fun handleCenterX(handle: Handle, isStart: Boolean) =
        screenX(handle.page, handle.xMm) + if (isStart) -handleRadius else handleRadius

    private fun handleCenterY(handle: Handle) = screenY(handle.page, handle.bottomMm) + handleRadius

    private fun startHandleDrag(event: MotionEvent): Boolean {
        val (start, end) = selectionHandles() ?: return false
        fun distance(handle: Handle) = hypot(event.x - handleCenterX(handle, handle === start), event.y - handleCenterY(handle))
        val dragged = if (distance(start) <= distance(end)) start else end
        if (distance(dragged) > handleTouchRadius) return false
        fixedHandle = if (dragged === start) end else start
        // The finger is below the text: keep the distance to the line the handle points at
        handleOffsetX = screenX(dragged.page, dragged.xMm) - event.x
        handleOffsetY = screenY(dragged.page, dragged.middleMm) - event.y
        hideActionMenu()
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun handleHandleDrag(event: MotionEvent) {
        val fixed = fixedHandle ?: return
        val listener = editListener ?: return
        val isUp = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (event.actionMasked != MotionEvent.ACTION_MOVE && !isUp) return
        val now = SystemClock.uptimeMillis()
        if (!isUp && now - lastMoveTime < MOVE_INTERVAL_MS) return
        lastMoveTime = now
        val point = toPage(event.x + handleOffsetX, event.y + handleOffsetY) ?: return
        listener.onSelect(fixed.page, fixed.xMm, fixed.middleMm, point.page, point.xMm, point.yMm)
        if (isUp) {
            fixedHandle = null
            menuAfterUpdate = true
        }
    }

    // ---------------------------------------------------------------------------------
    // Menu: cut, copy, paste, select all

    private fun showActionMenu(insertion: Boolean) {
        insertionMenu = insertion
        val mode = actionMode
        if (mode != null) {
            mode.invalidate()
            mode.invalidateContentRect()
        } else {
            actionMode = startActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)
        }
    }

    private fun hideActionMenu() {
        actionMode?.finish()
    }

    private val actionModeCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // After the spelling actions
            val first = MAX_SPELLING_ACTIONS
            menu.add(Menu.NONE, android.R.id.cut, first, android.R.string.cut)
            menu.add(Menu.NONE, android.R.id.copy, first + 1, android.R.string.copy)
            menu.add(Menu.NONE, android.R.id.paste, first + 2, android.R.string.paste)
            menu.add(Menu.NONE, android.R.id.selectAll, first + 3, android.R.string.selectAll)
            menu.add(Menu.NONE, MENU_DELETE, first + 4, Resources.string.editors_delete)
            selectionActions.forEachIndexed { index, (label, _) -> menu.add(Menu.NONE, MENU_EXTRA + index, first + 5 + index, label) }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            for (index in 0 until MAX_SPELLING_ACTIONS) menu.removeItem(MENU_SPELLING + index)
            if (!readOnly && visibleDrawings().isEmpty()) {
                spellingActions.take(MAX_SPELLING_ACTIONS).forEachIndexed { index, (label, _) ->
                    menu.add(Menu.NONE, MENU_SPELLING + index, index, label)
                }
            }
            val hasDrawings = !insertionMenu && visibleDrawings().isNotEmpty()
            val hasSelection = !insertionMenu && (editorState?.selection?.isNotEmpty() == true || hasDrawings)
            val canPaste = context.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true
            menu.findItem(android.R.id.cut).isVisible = hasSelection && !readOnly
            menu.findItem(android.R.id.copy).isVisible = hasSelection
            menu.findItem(android.R.id.paste).isVisible = canPaste && !readOnly
            menu.findItem(android.R.id.selectAll).isVisible = !hasDrawings && !readOnly
            menu.findItem(MENU_DELETE).isVisible = hasDrawings && !readOnly
            for (index in selectionActions.indices) menu.findItem(MENU_EXTRA + index)?.isVisible = hasSelection && !hasDrawings
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val listener = editListener ?: return false
            when (item.itemId) {
                android.R.id.cut -> listener.onCut()
                android.R.id.copy -> listener.onCopy()
                android.R.id.paste -> listener.onPaste()
                android.R.id.selectAll -> {
                    listener.onSelectAll()
                    menuAfterUpdate = true
                }
                MENU_DELETE -> listener.onKey(KEY_DELETE, false, false)
                in MENU_EXTRA until MENU_EXTRA + selectionActions.size -> selectionActions[item.itemId - MENU_EXTRA].second()
                in MENU_SPELLING until MENU_SPELLING + spellingActions.size -> spellingActions[item.itemId - MENU_SPELLING].second()
                else -> return false
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            insertionMenu = false
        }

        // Above the selection or the cursor
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val state = editorState
            val bounds = RectF()
            val areas = state?.selection?.filter { it.page in pageSizes.indices }.orEmpty()
            val frames = visibleDrawings()
            if (!insertionMenu && areas.isEmpty() && frames.isNotEmpty()) {
                val xs = frames.flatMap { frame -> (0 until 4).map { screenX(frame.page, frame.points[2 * it]) } }
                val ys = frames.flatMap { frame -> (0 until 4).map { screenY(frame.page, frame.points[2 * it + 1]) } }
                bounds.set(xs.min(), ys.min(), xs.max(), ys.max())
            } else if (!insertionMenu && areas.isNotEmpty()) {
                val xs = areas.flatMap { area -> (0 until 4).map { screenX(area.page, area.points[2 * it]) } }
                val ys = areas.flatMap { area -> (0 until 4).map { screenY(area.page, area.points[2 * it + 1]) } }
                bounds.set(xs.min(), ys.min(), xs.max(), ys.max())
                // Room for the handles below
                bounds.bottom += 2 * handleRadius
            } else {
                val cursor = state?.cursor
                if (cursor != null && cursor.page in pageSizes.indices) {
                    bounds.set(screenX(cursor.page, cursor.x1), screenY(cursor.page, cursor.y1), screenX(cursor.page, cursor.x2), screenY(cursor.page, cursor.y2))
                    bounds.sort()
                }
            }
            bounds.intersect(0f, 0f, width.toFloat(), height.toFloat())
            bounds.roundOut(outRect)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX.toFloat(), scroller.currY.toFloat())
            if (scroller.isFinished) requestDetails() else postInvalidateOnAnimation()
        }
    }

    private fun maxScrollX() = max(0f, width * zoom - width)

    private fun maxScrollY() = max(0f, contentHeight * zoom - height)

    private fun scrollTo(x: Float, y: Float) {
        scrollPosX = x.coerceIn(0f, maxScrollX())
        scrollPosY = y.coerceIn(0f, maxScrollY())
        invalidateAccessibility()
        val page = visiblePage()
        if (page != lastVisiblePage) {
            lastVisiblePage = page
            onVisiblePageChanged?.invoke(page)
        }
        invalidate()
    }

    private fun zoomTo(newZoom: Float, focusX: Float, focusY: Float) {
        val target = newZoom.coerceIn(1f, maxZoom)
        val factor = target / zoom
        zoom = target
        details = emptyList()
        scrollTo((scrollPosX + focusX) * factor - focusX, (scrollPosY + focusY) * factor - focusY)
    }

    // ---------------------------------------------------------------------------------
    // Keyboard

    override fun onCheckIsTextEditor(): Boolean = editListener != null && !readOnly

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (editListener == null || readOnly) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return DocumentInputConnection()
    }

    /** Takes the focus and shows the keyboard, for typing at the cursor. */
    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (readOnly || editListener == null) return viewingKey(event) || super.onKeyDown(keyCode, event)
        return sendKey(editListener!!, event) || super.onKeyDown(keyCode, event)
    }

    // Viewing: copying the selection, and keys that scroll
    private fun viewingKey(event: KeyEvent): Boolean {
        val listener = editListener
        if (event.isCtrlPressed && listener != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_C -> listener.onCopy()
                KeyEvent.KEYCODE_A -> listener.onSelectAll()
                else -> return false
            }
            return true
        }
        val line = 48 * density
        val page = height - 2 * line
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> scrollTo(scrollPosX, scrollPosY - line)
            KeyEvent.KEYCODE_DPAD_DOWN -> scrollTo(scrollPosX, scrollPosY + line)
            KeyEvent.KEYCODE_DPAD_LEFT -> scrollTo(scrollPosX - line, scrollPosY)
            KeyEvent.KEYCODE_DPAD_RIGHT -> scrollTo(scrollPosX + line, scrollPosY)
            KeyEvent.KEYCODE_PAGE_UP -> scrollTo(scrollPosX, scrollPosY - page)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> scrollTo(scrollPosX, scrollPosY + if (event.isShiftPressed) -page else page)
            KeyEvent.KEYCODE_MOVE_HOME -> scrollTo(scrollPosX, 0f)
            KeyEvent.KEYCODE_MOVE_END -> scrollTo(scrollPosX, maxScrollY())
            else -> return false
        }
        removeCallbacks(detailsAfterWheel)
        postDelayed(detailsAfterWheel, WHEEL_DETAILS_DELAY_MS)
        return true
    }

    private fun sendKey(listener: EditListener, event: KeyEvent): Boolean {
        hideActionMenu()
        // Clipboard shortcuts: sdkjs leaves the clipboard to the app
        if (event.isCtrlPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_X -> listener.onCut()
                KeyEvent.KEYCODE_C -> listener.onCopy()
                KeyEvent.KEYCODE_V -> listener.onPaste()
                KeyEvent.KEYCODE_A -> listener.onSelectAll()
            }
            if (event.keyCode in CLIPBOARD_KEYS) return true
        }
        val webKeyCode = webKeyCode(event.keyCode)
        if (webKeyCode != null && (event.isCtrlPressed || event.keyCode !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z)) {
            listener.onKey(webKeyCode, event.isShiftPressed, event.isCtrlPressed)
            return true
        }
        val char = event.unicodeChar
        if (char != 0) {
            listener.onCommitText(String(Character.toChars(char)))
            return true
        }
        return false
    }

    /** Talks to the soft keyboard; text goes to the document, not to an Android text buffer. */
    private inner class DocumentInputConnection : BaseInputConnection(this@DocumentView, false) {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            hideActionMenu()
            editListener?.onCommitText(text.toString())
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            editListener?.onComposingText(text.toString())
            return true
        }

        override fun finishComposingText(): Boolean {
            editListener?.onFinishComposingText()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val listener = editListener ?: return true
            repeat(beforeLength) { listener.onKey(KEY_BACKSPACE, false, false) }
            repeat(afterLength) { listener.onKey(KEY_DELETE, false, false) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val listener = editListener ?: return true
            if (event.action == KeyEvent.ACTION_DOWN) sendKey(listener, event)
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            editListener?.onKey(KEY_ENTER, false, false)
            return true
        }
    }

    // ---------------------------------------------------------------------------------
    // Screen readers: the blocks of text on the visible pages are virtual views; a double tap on
    // one places the cursor there, scrolling goes by screens and tells the page

    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)

    private fun invalidateAccessibility() {
        if (textBlocks != null && accessibilityManager?.isEnabled == true) {
            removeCallbacks(invalidateAccessibilityRoot)
            postDelayed(invalidateAccessibilityRoot, ACCESSIBILITY_DELAY_MS)
        }
    }

    private val invalidateAccessibilityRoot = Runnable { accessibility.invalidateRoot() }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        (textBlocks != null && accessibility.dispatchHoverEvent(event)) || super.dispatchHoverEvent(event)

    // The blocks of a page, or null while they are loaded
    private fun blocksOf(page: Int): List<TextBlock>? {
        blocks[page]?.let { return it }
        val load = textBlocks ?: return null
        if (blockJobs[page]?.isActive != true) {
            blockJobs[page] = scope?.launch {
                blocks[page] = runCatching { load(page) }.getOrDefault(emptyList())
                accessibility.invalidateRoot()
            } ?: return null
        }
        return null
    }

    private fun blockRect(page: Int, block: TextBlock) = RectF(
        screenX(page, block.left), screenY(page, block.top), screenX(page, block.right), screenY(page, block.bottom)
    )

    private fun visiblePages(): List<Int> = pageSizes.indices.filter { page ->
        page < pageTops.size && pageTop(page) < height && pageTop(page) + pageHeights[page] * zoom > 0
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (pageSizes.isEmpty()) return
        info.contentDescription = context.getString(Resources.string.editors_pages_page_of, visiblePage() + 1, pageSizes.size)
        info.isScrollable = true
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val step = height - 96 * density
        val dy = when (action) {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> step
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -step
            else -> return super.performAccessibilityAction(action, arguments)
        }
        val before = scrollPosY
        scrollTo(scrollPosX, scrollPosY + dy)
        requestDetails()
        if (scrollPosY == before) return false
        announceForAccessibility(context.getString(Resources.string.editors_pages_page_of, visiblePage() + 1, pageSizes.size))
        return true
    }

    private inner class DocumentAccessibility : ExploreByTouchHelper(this@DocumentView) {

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            for (page in visiblePages()) {
                val index = blocksOf(page)?.indexOfFirst { blockRect(page, it).contains(x, y) } ?: -1
                if (index >= 0) return page * MAX_BLOCKS + index
            }
            return INVALID_ID
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
            for (page in visiblePages()) {
                blocksOf(page)?.forEachIndexed { index, block ->
                    if (index < MAX_BLOCKS && RectF.intersects(blockRect(page, block), screen)) ids += page * MAX_BLOCKS + index
                }
            }
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val page = id / MAX_BLOCKS
            val block = blocks[page]?.getOrNull(id % MAX_BLOCKS)
            if (block == null) {
                // Gone since: an empty node
                node.text = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.text = block.text
            val bounds = Rect()
            blockRect(page, block).apply { intersect(0f, 0f, width.toFloat(), height.toFloat()) }.roundOut(bounds)
            if (bounds.isEmpty) bounds.set(0, 0, 1, 1)
            node.setBoundsInParent(bounds)
            if (editListener != null && !readOnly) node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || editListener == null || readOnly) return false
            val page = id / MAX_BLOCKS
            val block = blocks[page]?.getOrNull(id % MAX_BLOCKS) ?: return false
            // The cursor at the start of the block
            val listener = editListener ?: return false
            listener.onTouch(TouchAction.DOWN, page, block.left + 0.5, (block.top + block.bottom) / 2, 1)
            listener.onTouch(TouchAction.UP, page, block.left + 0.5, (block.top + block.bottom) / 2, 1)
            showKeyboard()
            return true
        }
    }

    // ---------------------------------------------------------------------------------
    // Drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        if (pageSizes.isEmpty()) return

        canvas.save()
        canvas.translate(-scrollPosX, -scrollPosY)
        canvas.scale(zoom, zoom)
        val visibleTop = scrollPosY / zoom
        val visibleBottom = (scrollPosY + height) / zoom
        for (page in pageSizes.indices) {
            val top = pageTops[page]
            val bottom = top + pageHeights[page]
            if (bottom < visibleTop || top > visibleBottom) continue

            val rect = RectF(pageMargin, top, pageMargin + pageWidth, bottom)
            val bitmap = pageBitmaps.get(page)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
            } else {
                canvas.drawRect(rect, paperPaint)
            }
            if (bitmap == null || page in stalePages) requestPage(page)
        }
        canvas.restore()

        // Sharp renders of the visible areas
        canvas.save()
        canvas.translate(-scrollPosX, -scrollPosY)
        for (detail in details) {
            if (detail.zoom == zoom) canvas.drawBitmap(detail.bitmap, null, detail.contentRect, null)
        }
        canvas.restore()

        drawEditorState(canvas)
    }

    private fun drawEditorState(canvas: Canvas) {
        val state = editorState ?: return
        for (area in state.selection) {
            if (area.page !in pageSizes.indices) continue
            val path = Path()
            for (i in 0 until 4) {
                val x = screenX(area.page, area.points[2 * i])
                val y = screenY(area.page, area.points[2 * i + 1])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, selectionPaint)
        }
        // Selected pictures and shapes: their frame with handles
        if (editListener != null && !readOnly) drawingHandles.draw(canvas, visibleDrawings(), toScreen)
        selectionHandles()?.let { (start, end) ->
            for (handle in listOf(start, end)) {
                val isStart = handle === start
                val x = screenX(handle.page, handle.xMm)
                val bottom = screenY(handle.page, handle.bottomMm)
                val centerX = handleCenterX(handle, isStart)
                canvas.drawLine(x, screenY(handle.page, handle.topMm), x, bottom, handlePaint)
                canvas.drawCircle(centerX, handleCenterY(handle), handleRadius, handlePaint)
                // The corner pointing at the line
                canvas.drawRect(min(x, centerX), bottom, max(x, centerX), bottom + handleRadius, handlePaint)
            }
        }
        state.coEditing?.cursors?.forEach { drawForeignCursor(canvas, it) }
        val cursor = state.cursor ?: return
        if (cursorShown && cursor.page in pageSizes.indices && editListener != null) {
            canvas.drawLine(
                screenX(cursor.page, cursor.x1), screenY(cursor.page, cursor.y1),
                screenX(cursor.page, cursor.x2), screenY(cursor.page, cursor.y2),
                cursorPaint
            )
        }
    }

    private fun drawForeignCursor(canvas: Canvas, cursor: ForeignCursor) {
        if (cursor.page !in pageSizes.indices) return
        foreignCursorPaint.color = runCatching { Color.parseColor(cursor.color) }.getOrDefault(Color.GRAY)
        val x1 = screenX(cursor.page, cursor.x1)
        val y1 = screenY(cursor.page, cursor.y1)
        canvas.drawLine(x1, y1, screenX(cursor.page, cursor.x2), screenY(cursor.page, cursor.y2), foreignCursorPaint)
        if (cursor.name.isEmpty()) return
        val padding = 3 * density
        val textWidth = foreignNamePaint.measureText(cursor.name)
        val textHeight = foreignNamePaint.fontMetrics.let { it.descent - it.ascent }
        val tag = RectF(x1 - foreignCursorPaint.strokeWidth / 2, y1 - textHeight - 2 * padding, x1 + textWidth + 2 * padding, y1)
        canvas.drawRect(tag, foreignCursorPaint)
        canvas.drawText(cursor.name, tag.left + padding, tag.bottom - padding - foreignNamePaint.fontMetrics.descent, foreignNamePaint)
    }

    private fun restartBlink() {
        removeCallbacks(blink)
        cursorShown = true
        if (editorState?.cursor != null && editListener != null) postDelayed(blink, BLINK_MS)
    }

    // ---------------------------------------------------------------------------------
    // Rendering

    private fun requestPage(page: Int) {
        val renderer = renderer ?: return
        if (pageJobs[page]?.isActive == true) return
        val width = pageWidth.roundToInt()
        val height = pageHeights[page].roundToInt()
        val version = pageVersions[page] ?: 0
        pageJobs[page] = scope?.launch {
            val bitmap = renderer.render(page, width, height, 0, 0, width, height)
            if (bitmap != null && width == pageWidth.roundToInt() && page in pageSizes.indices) {
                pageBitmaps.put(page, bitmap)
                if (version == (pageVersions[page] ?: 0)) stalePages.remove(page)
                invalidate()
            }
        } ?: return
    }

    // Renders the visible part of each visible page at the current zoom
    private fun requestDetails() {
        val renderer = renderer ?: return
        detailJob?.cancel()
        if (zoom <= 1.05f) {
            details = emptyList()
            invalidate()
            return
        }

        val requestZoom = zoom
        val scrollX = scrollPosX.roundToInt()
        val scrollY = scrollPosY.roundToInt()
        val viewport = Rect(0, 0, width, height)
        val requests = pageSizes.indices.mapNotNull { page ->
            // Page rectangle in screen pixels
            val left = (pageMargin * zoom).roundToInt() - scrollX
            val top = (pageTops[page] * zoom).roundToInt() - scrollY
            val pageRect = Rect(left, top, left + (pageWidth * zoom).roundToInt(), top + (pageHeights[page] * zoom).roundToInt())
            val visible = Rect(pageRect)
            if (!visible.intersect(viewport)) null else Triple(page, pageRect, visible)
        }

        detailJob = scope?.launch {
            val rendered = mutableListOf<Detail>()
            for ((page, pageRect, visible) in requests) {
                val bitmap = renderer.render(
                    page, pageRect.width(), pageRect.height(),
                    visible.left - pageRect.left, visible.top - pageRect.top, visible.width(), visible.height()
                ) ?: continue
                visible.offset(scrollX, scrollY)
                rendered.add(Detail(requestZoom, visible, bitmap))
            }
            if (requestZoom == zoom) {
                details = rendered
                invalidate()
            }
        }
    }

    private fun pageCacheBytes(): Int = min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6).toInt()

    private companion object {
        const val BLINK_MS = 500L
        const val MOVE_INTERVAL_MS = 40L
        const val ACCESSIBILITY_DELAY_MS = 200L
        // Virtual view ids of text blocks: page * MAX_BLOCKS + index
        const val MAX_BLOCKS = 10_000
        const val DOUBLE_CLICK_MS = 400L
        const val WHEEL_ZOOM_STEP = 0.1f
        const val WHEEL_DETAILS_DELAY_MS = 150L
        const val MENU_DELETE = 1
        // The first of selectionActions
        const val MENU_EXTRA = 100
        // The first of spellingActions
        const val MENU_SPELLING = 200
        const val MAX_SPELLING_ACTIONS = 8
        val CLIPBOARD_KEYS = setOf(KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_A)

        const val KEY_BACKSPACE = 8
        const val KEY_ENTER = 13
        const val KEY_DELETE = 46

        // Android key codes to the web key codes sdkjs expects
        fun webKeyCode(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_DEL -> KEY_BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> KEY_DELETE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> KEY_ENTER
            KeyEvent.KEYCODE_TAB -> 9
            KeyEvent.KEYCODE_ESCAPE -> 27
            KeyEvent.KEYCODE_PAGE_UP -> 33
            KeyEvent.KEYCODE_PAGE_DOWN -> 34
            KeyEvent.KEYCODE_MOVE_END -> 35
            KeyEvent.KEYCODE_MOVE_HOME -> 36
            KeyEvent.KEYCODE_DPAD_LEFT -> 37
            KeyEvent.KEYCODE_DPAD_UP -> 38
            KeyEvent.KEYCODE_DPAD_RIGHT -> 39
            KeyEvent.KEYCODE_DPAD_DOWN -> 40
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 65 + keyCode - KeyEvent.KEYCODE_A
            else -> null
        }
    }
}
