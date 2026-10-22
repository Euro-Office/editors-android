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
import android.util.AttributeSet
import android.util.LruCache
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import lib.editors.base.engine.DrawingFrame
import lib.editors.base.engine.ForeignSelection
import lib.editors.base.engine.SheetGeometry
import lib.editors.base.engine.SheetState
import lib.editors.base.engine.TouchAction
import lib.editors.resources.R as Resources
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Shows a spreadsheet: the cells of the active sheet with column and row headings that stay in
 * place, frozen panes, scrolling and zoom, the selection and a handle to extend it. Headings
 * select whole columns and rows, their borders resize them; a hardware keyboard moves the
 * selection and typing starts editing the active cell. With a mouse, dragging selects cells (or
 * columns and rows on their headings), the right button opens the menu and the wheel scrolls
 * (and zooms with Ctrl).
 *
 * A sheet can be much larger than the screen, so only the visible area is rendered, in tiles at
 * the current zoom (rounded to a few levels; while zooming, tiles of the previous level are
 * scaled). Tiles of changed cells keep their old picture until the new one is ready.
 */
class SheetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Input for the sheet: cells by column and row index. */
    interface SheetListener {
        fun onSelectCells(c1: Int, r1: Int, c2: Int, r2: Int)
        /** A tap on the active cell: edit it. */
        fun onEditCell()
        fun onCut()
        fun onCopy()
        fun onPaste()
        /** Touch on pictures, shapes and charts, in sheet millimeters (see EditorDocument.touchDrawing). */
        fun onTouchDrawing(action: TouchAction, xMm: Double, yMm: Double, addToSelection: Boolean)
        fun onSelectColumns(c1: Int, c2: Int)
        fun onSelectRows(r1: Int, r2: Int)
        /** Long press on a column or row heading. */
        fun onHeadingMenu(isColumn: Boolean, index: Int)
        fun onResizeColumn(col: Int, widthMm: Double)
        fun onResizeRow(row: Int, heightMm: Double)
        /** Keyboard: moves the active cell by the offset; [extend] extends the selection instead. */
        fun onMoveSelection(dCol: Int, dRow: Int, extend: Boolean)
        fun onClearCells()
        /** Text typed while the sheet has the focus: starts editing the active cell with it. */
        fun onTypeText(text: String)
        /** Fills the selection from its filled cells. */
        fun onFillSelection()
        fun onSelectAll()
        /** Ctrl+Z, Ctrl+Y. */
        fun onUndo(redo: Boolean)
        /** Ctrl+B, Ctrl+I, Ctrl+U: switches the style of the selected cells. */
        fun onToggleStyle(style: Style)
    }

    enum class Style { BOLD, ITALIC, UNDERLINE }

    private class TileKey(val level: Int, val column: Int, val row: Int) {
        override fun equals(other: Any?) =
            other is TileKey && other.level == level && other.column == column && other.row == row

        override fun hashCode() = (level * 31 + column) * 1_000_003 + row
    }

    private class Tile(val bitmap: Bitmap, val version: Int)

    private val density = resources.displayMetrics.density
    // Pixels per millimeter at zoom 1: the size the cells have on a desktop screen, a bit smaller
    private val baseScale = 96 / 25.4f * density * 0.8f
    private val minZoom = 0.3f
    private val maxZoom = 3f
    private val tileSize = 512

    private val headerHeight = 24 * density
    private var headerWidth = 40 * density

    private var renderer: DocumentView.Renderer? = null
    private var scope: CoroutineScope? = null
    var listener: SheetListener? = null

    private var geometry = SheetGeometry(doubleArrayOf(0.0), doubleArrayOf(0.0))
    private var state: SheetState? = null

    private var zoom = 1f
    private var scrollPosX = 0f
    private var scrollPosY = 0f

    // Rendering: tiles of a zoom level cover the sheet at that level's scale
    private val tiles = object : LruCache<TileKey, Tile>(cacheBytes()) {
        override fun sizeOf(key: TileKey, value: Tile) = value.bitmap.byteCount
    }
    private var version = 0
    private var renderJob: Job? = null
    // Level shown under the current one while its tiles are rendered (after zooming)
    private var fallbackLevel = levelFor(1f)

    // Extending the selection: by its handle, or by dragging after a long press
    private var dragging = false

    // Resizing a column or row by its heading's border: which one and where the border is now
    private var resizingColumn = -1
    private var resizingRow = -1
    private var resizePosition = 0f
    private var resizeStart = 0f
    private var resizeArmedColumn = -1
    private var resizeArmedRow = -1

    /** Co-editing: cells others selected on this sheet, framed in their colors. */
    var foreignSelections: List<ForeignSelection> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Text of the active cell being edited (in the formula bar), shown in the cell; null when not editing. */
    var editingText: String? = null
        set(value) {
            field = value
            invalidate()
        }

    // Pictures, shapes and charts: the selected ones, what a touch grabbed, whether it's dragged
    private var drawings: List<DrawingFrame> = emptyList()
    private val drawingHandles = DrawingHandles(context)
    private var drawingGrab: DrawingHandles.Grab? = null
    private var drawingDragging = false
    private var grabX = 0f
    private var grabY = 0f
    private var lastMoveTime = 0L
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val toScreen = DrawingHandles.ToScreen { _, xMm, yMm -> android.graphics.PointF(viewX(xMm), viewY(yMm)) }
    private var actionMode: ActionMode? = null

    // Mouse: a button is down on cells, on a heading (columns or rows) or on a drawing; the cell
    // or heading the selection started at; the last click, for double clicks
    private var mouseDown = false
    private var mouseHeading: Boolean? = null
    private var mouseDrawing = false
    private var mouseAnchorCol = 0
    private var mouseAnchorRow = 0
    private var mouseLastCol = -1
    private var mouseLastRow = -1
    private var lastClickTime = 0L

    private val cellBackground = Paint().apply { color = Color.WHITE }
    private val background = ContextCompat.getColor(context, Resources.color.editors_document_background)
    private val headerPaint = Paint().apply { color = ContextCompat.getColor(context, Resources.color.editors_sheet_header) }
    private val headerSelectedPaint = Paint().apply {
        color = ContextCompat.getColor(context, Resources.color.editors_sheet_header_selected)
    }
    private val headerLinePaint = Paint().apply {
        color = ContextCompat.getColor(context, Resources.color.editors_sheet_header_line)
        strokeWidth = density
    }
    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, Resources.color.editors_icon)
        textSize = 12 * density
        textAlign = Paint.Align.CENTER
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val foreignPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * density }
    private val foreignNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11 * resources.displayMetrics.scaledDensity
    }
    private val selectionFill = Paint().apply { color = ContextCompat.getColor(context, Resources.color.editors_selection) }
    private val selectionBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, Resources.color.editors_selection_handle)
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, Resources.color.editors_selection_handle)
    }
    private val freezePaint = Paint().apply {
        color = ContextCompat.getColor(context, Resources.color.editors_sheet_header_line)
        strokeWidth = 2 * density
    }
    private val editTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 13 * density
    }
    private val handleRadius = 6 * density
    private val handleTouchRadius = 28 * density

    private val scroller = OverScroller(context)

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            requestTiles()
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

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val listener = listener ?: return false
            hideActionMenu()
            // The listener moves the focus: typed text must be entered first, and the formula bar keeps it while a formula is edited
            headingAt(e.x, e.y)?.let { (isColumn, index) ->
                if (isColumn) listener.onSelectColumns(index, index) else listener.onSelectRows(index, index)
                return true
            }
            if (objectAt(e.x, e.y)) {
                val (xMm, yMm) = sheetPoint(e.x, e.y)
                listener.onTouchDrawing(TouchAction.DOWN, xMm, yMm, false)
                listener.onTouchDrawing(TouchAction.UP, xMm, yMm, false)
                return true
            }
            val (col, row) = cellAt(e.x, e.y) ?: return true
            val current = state
            if (current != null && col == current.col && row == current.row && current.isSingleCell) {
                listener.onEditCell()
            } else {
                listener.onSelectCells(col, row, col, row)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val listener = listener ?: return
            headingAt(e.x, e.y)?.let { (isColumn, index) ->
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener.onHeadingMenu(isColumn, index)
                return
            }
            // With drawings selected: add the one pressed to them
            if (drawings.isNotEmpty() && objectAt(e.x, e.y)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val (xMm, yMm) = sheetPoint(e.x, e.y)
                listener.onTouchDrawing(TouchAction.DOWN, xMm, yMm, true)
                listener.onTouchDrawing(TouchAction.UP, xMm, yMm, true)
                return
            }
            val (col, row) = cellAt(e.x, e.y) ?: return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val current = state
            // In the selection: the menu for it; elsewhere: select the cell and extend by dragging
            if (current != null && col in current.c1..current.c2 && row in current.r1..current.r2) {
                showActionMenu()
            } else {
                listener.onSelectCells(col, row, col, row)
                dragging = true
            }
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setRenderer(renderer: DocumentView.Renderer, scope: CoroutineScope) {
        this.renderer = renderer
        this.scope = scope
    }

    /** The sheet's state after a command; [changed] if cells may look different. */
    fun setState(sheet: SheetState, changed: Boolean, selectedDrawings: List<DrawingFrame> = emptyList()) {
        val previous = state
        state = sheet
        drawings = selectedDrawings
        sheet.geometry?.let { setGeometry(it) }
        if (previous != null && previous.active != sheet.active) {
            // Another sheet: nothing of the old one fits
            tiles.evictAll()
            scrollPosX = 0f
            scrollPosY = 0f
        }
        if (changed) version++
        if (previous != null && (previous.col != sheet.col || previous.row != sheet.row) && !dragging) showCell(sheet.col, sheet.row)
        if (sheet.isSingleCell && actionMode != null && previous?.let { it.c1 != sheet.c1 || it.r1 != sheet.r1 } == true) hideActionMenu()
        actionMode?.invalidateContentRect()
        requestTiles()
        invalidate()
    }

    private fun setGeometry(newGeometry: SheetGeometry) {
        if (newGeometry.cols.contentEquals(geometry.cols) && newGeometry.rows.contentEquals(geometry.rows)) return
        geometry = newGeometry
        // Room for the longest row number
        headerTextPaint.measureText(newGeometry.rowCount.toString()).let { headerWidth = max(40 * density, it + 16 * density) }
        // Positions of cells changed: tiles no longer match
        tiles.evictAll()
        scrollTo(scrollPosX, scrollPosY)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderJob?.cancel()
        hideActionMenu()
    }

    // ---------------------------------------------------------------------------------
    // Coordinates: sheet millimeters, content pixels (sheet at the zoom) and the view

    private fun scale(): Float = baseScale * zoom

    // Frozen panes: the columns and rows before them don't scroll
    private fun frozenWidthMm(): Double = state?.frozenCol?.takeIf { it in 1..geometry.colCount }?.let { geometry.cols[it] } ?: 0.0

    private fun frozenHeightMm(): Double = state?.frozenRow?.takeIf { it in 1..geometry.rowCount }?.let { geometry.rows[it] } ?: 0.0

    private fun frozenWidth(): Float = (frozenWidthMm() * scale()).toFloat()

    private fun frozenHeight(): Float = (frozenHeightMm() * scale()).toFloat()

    private fun viewX(mm: Double): Float = (headerWidth + mm * scale() - if (mm >= frozenWidthMm()) scrollPosX else 0f).toFloat()

    private fun viewY(mm: Double): Float = (headerHeight + mm * scale() - if (mm >= frozenHeightMm()) scrollPosY else 0f).toFloat()

    // The right and bottom edges of a cell: the edge on the freeze line belongs to the frozen cell before it
    private fun columnRight(col: Int): Float =
        (headerWidth + geometry.cols[col + 1] * scale() - if (col >= (state?.frozenCol ?: 0)) scrollPosX else 0f).toFloat()

    private fun rowBottom(row: Int): Float =
        (headerHeight + geometry.rows[row + 1] * scale() - if (row >= (state?.frozenRow ?: 0)) scrollPosY else 0f).toFloat()

    private fun sheetPoint(x: Float, y: Float): Pair<Double, Double> {
        val scrollX = if (x < headerWidth + frozenWidth()) 0f else scrollPosX
        val scrollY = if (y < headerHeight + frozenHeight()) 0f else scrollPosY
        return ((x - headerWidth + scrollX) / scale()).toDouble() to ((y - headerHeight + scrollY) / scale()).toDouble()
    }

    // A column (true) or row heading at the view point
    private fun headingAt(x: Float, y: Float): Pair<Boolean, Int>? {
        if (geometry.colCount == 0) return null
        return when {
            y < headerHeight && x > headerWidth -> true to geometry.columnAt(sheetPoint(x, headerHeight).first)
            x < headerWidth && y > headerHeight -> false to geometry.rowAt(sheetPoint(headerWidth, y).second)
            else -> null
        }
    }

    // Whether a picture, shape or chart is at the view point
    private fun objectAt(x: Float, y: Float): Boolean =
        state?.objects?.any { drawingHandles.isInsidePolygon(0, it, x, y, toScreen) } == true

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (geometry.colCount == 0 || geometry.rowCount == 0) return null
        val (xMm, yMm) = sheetPoint(x, y)
        return geometry.columnAt(xMm) to geometry.rowAt(yMm)
    }

    private fun maxScrollX() = max(0f, (geometry.widthMm * scale()).toFloat() + headerWidth - width)

    private fun maxScrollY() = max(0f, (geometry.heightMm * scale()).toFloat() + headerHeight - height)

    private fun scrollTo(x: Float, y: Float) {
        scrollPosX = x.coerceIn(0f, maxScrollX())
        scrollPosY = y.coerceIn(0f, maxScrollY())
        requestTiles()
        invalidate()
    }

    private fun zoomTo(newZoom: Float, focusX: Float, focusY: Float) {
        val target = newZoom.coerceIn(minZoom, maxZoom)
        val oldLevel = levelFor(zoom)
        // Content position under the focus stays there
        val contentX = (focusX - headerWidth + scrollPosX) / zoom
        val contentY = (focusY - headerHeight + scrollPosY) / zoom
        zoom = target
        if (levelFor(zoom) != oldLevel) fallbackLevel = oldLevel
        scrollTo(contentX * zoom - focusX + headerWidth, contentY * zoom - focusY + headerHeight)
    }

    /** Scrolls so that the cell is visible. */
    private fun showCell(col: Int, row: Int) {
        if (col >= geometry.colCount || row >= geometry.rowCount) return
        val margin = 8 * density
        var dx = 0f
        var dy = 0f
        val left = viewX(geometry.cols[col])
        val right = columnRight(col)
        val top = viewY(geometry.rows[row])
        val bottom = rowBottom(row)
        if (right > width - margin) dx = right - (width - margin)
        if (col >= (state?.frozenCol ?: 0) && left - dx < headerWidth + frozenWidth() + margin) dx = left - headerWidth - frozenWidth() - margin
        if (bottom > height - margin) dy = bottom - (height - margin)
        if (row >= (state?.frozenRow ?: 0) && top - dy < headerHeight + frozenHeight() + margin) dy = top - headerHeight - frozenHeight() - margin
        if (col < (state?.frozenCol ?: 0)) dx = 0f
        if (row < (state?.frozenRow ?: 0)) dy = 0f
        if (dx != 0f || dy != 0f) scrollTo(scrollPosX + dx, scrollPosY + dy)
    }

    // ---------------------------------------------------------------------------------
    // Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleResize(event)) return true
        if (isMouse(event)) return handleMouse(event)
        if (handleDrawingTouch(event)) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN && drawings.isEmpty() && isOnHandle(event.x, event.y)) {
            dragging = true
            hideActionMenu()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (dragging) {
            extendSelection(event)
            return true
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    // Dragging the border between two column or row headings resizes the column or row before it.
    // A press there is only armed: taps and long presses still reach the gesture detector.
    private fun handleResize(event: MotionEvent): Boolean {
        val listener = listener ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizingColumn = -1
                resizingRow = -1
                resizeArmedColumn = -1
                resizeArmedRow = -1
                resizeArmedColumn = columnBorderAt(event.x, event.y, isMouse(event))
                resizeArmedRow = rowBorderAt(event.x, event.y, isMouse(event))
                resizeStart = if (resizeArmedColumn >= 0) event.x else event.y
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizingColumn < 0 && resizingRow < 0) {
                    if (resizeArmedColumn < 0 && resizeArmedRow < 0) return false
                    val position = if (resizeArmedColumn >= 0) event.x else event.y
                    if (kotlin.math.abs(position - resizeStart) < touchSlop) return false
                    resizingColumn = resizeArmedColumn
                    resizingRow = resizeArmedRow
                    // The gestures must not see the rest as a scroll or long press
                    gestureDetector.onTouchEvent(MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL })
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                resizePosition = if (resizingColumn >= 0) event.x else event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resizeArmedColumn = -1
                resizeArmedRow = -1
                if (resizingColumn < 0 && resizingRow < 0) return false
                val delta = (resizePosition - resizeStart) / scale()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (resizingColumn >= 0) {
                        val widthMm = geometry.cols[resizingColumn + 1] - geometry.cols[resizingColumn] + delta
                        listener.onResizeColumn(resizingColumn, maxOf(2.0, widthMm.toDouble()))
                    } else {
                        val heightMm = geometry.rows[resizingRow + 1] - geometry.rows[resizingRow] + delta
                        listener.onResizeRow(resizingRow, maxOf(2.0, heightMm.toDouble()))
                    }
                }
                resizingColumn = -1
                resizingRow = -1
                invalidate()
                return true
            }
        }
        return false
    }

    // The column whose right border is at the point on the column headings, or -1. A mouse
    // pointer needs to be closer than a finger.
    private fun columnBorderAt(x: Float, y: Float, mouse: Boolean): Int {
        if (y >= headerHeight || x <= headerWidth || geometry.colCount == 0) return -1
        val reach = (if (mouse) 4 else 10) * density
        val col = geometry.columnAt(sheetPoint(x, headerHeight).first)
        // The border on the right of the column, or of the one before
        return listOf(col, col - 1).firstOrNull { it >= 0 && kotlin.math.abs(columnRight(it) - x) < reach } ?: -1
    }

    private fun rowBorderAt(x: Float, y: Float, mouse: Boolean): Int {
        if (x >= headerWidth || y <= headerHeight || geometry.rowCount == 0) return -1
        val reach = (if (mouse) 4 else 10) * density
        val row = geometry.rowAt(sheetPoint(headerWidth, y).second)
        return listOf(row, row - 1).firstOrNull { it >= 0 && kotlin.math.abs(rowBottom(it) - y) < reach } ?: -1
    }

    // ---------------------------------------------------------------------------------
    // Mouse and touchpad

    private fun handleMouse(event: MotionEvent): Boolean {
        val listener = listener ?: return gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                    showContextMenu(listener, event.x, event.y)
                    return true
                }
                if (event.buttonState != 0 && !event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) return true
                // Pressed on a heading's border: that resizes (handleResize)
                if (resizeArmedColumn >= 0 || resizeArmedRow >= 0) return true
                hideActionMenu()
                val doubleClick = event.eventTime - lastClickTime < DOUBLE_CLICK_MS
                lastClickTime = event.eventTime
                mouseDown = true
                mouseHeading = null
                mouseDrawing = false
                val sheet = state
                val heading = headingAt(event.x, event.y)
                if (heading != null) {
                    val (isColumn, index) = heading
                    mouseHeading = isColumn
                    val anchor = if (event.isShiftDown && sheet != null) (if (isColumn) sheet.col else sheet.row) else index
                    mouseAnchorCol = anchor
                    mouseAnchorRow = anchor
                    mouseLastCol = index
                    selectHeadings(listener, isColumn, anchor, index)
                } else if (objectAt(event.x, event.y) || drawings.isNotEmpty() && drawingHandles.hitTest(drawings, event.x, event.y, toScreen) != null) {
                    mouseDrawing = true
                    val (xMm, yMm) = sheetPoint(event.x, event.y)
                    listener.onTouchDrawing(TouchAction.DOWN, xMm, yMm, event.isCtrlDown)
                } else {
                    val (col, row) = cellAt(event.x, event.y) ?: return true
                    if (doubleClick && sheet != null && col == sheet.col && row == sheet.row && sheet.isSingleCell) {
                        mouseDown = false
                        listener.onEditCell()
                        return true
                    }
                    mouseAnchorCol = if (event.isShiftDown && sheet != null) sheet.col else col
                    mouseAnchorRow = if (event.isShiftDown && sheet != null) sheet.row else row
                    mouseLastCol = col
                    mouseLastRow = row
                    listener.onSelectCells(mouseAnchorCol, mouseAnchorRow, col, row)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!mouseDown) return true
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastMoveTime < MOVE_INTERVAL_MS) return true
                lastMoveTime = now
                val isColumn = mouseHeading
                if (mouseDrawing) {
                    val (xMm, yMm) = sheetPoint(event.x, event.y)
                    listener.onTouchDrawing(TouchAction.MOVE, xMm, yMm, event.isCtrlDown)
                } else if (isColumn != null) {
                    val index = if (isColumn) geometry.columnAt(sheetPoint(event.x, headerHeight).first)
                    else geometry.rowAt(sheetPoint(headerWidth, event.y).second)
                    if (index != mouseLastCol) {
                        mouseLastCol = index
                        selectHeadings(listener, isColumn, mouseAnchorCol, index)
                    }
                } else {
                    val (col, row) = cellAt(event.x, event.y) ?: return true
                    if (col != mouseLastCol || row != mouseLastRow) {
                        mouseLastCol = col
                        mouseLastRow = row
                        listener.onSelectCells(mouseAnchorCol, mouseAnchorRow, col, row)
                    }
                }
                scrollAtEdges(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!mouseDown) return true
                mouseDown = false
                if (mouseDrawing) {
                    val (xMm, yMm) = sheetPoint(event.x, event.y)
                    listener.onTouchDrawing(TouchAction.UP, xMm, yMm, event.isCtrlDown)
                }
            }
        }
        return true
    }

    private fun selectHeadings(listener: SheetListener, isColumn: Boolean, from: Int, to: Int) {
        if (isColumn) listener.onSelectColumns(min(from, to), max(from, to)) else listener.onSelectRows(min(from, to), max(from, to))
    }

    // The right button: a heading's menu, or the cut, copy and paste menu for the selection (or
    // for the cell clicked outside of it)
    private fun showContextMenu(listener: SheetListener, x: Float, y: Float) {
        headingAt(x, y)?.let { (isColumn, index) ->
            listener.onHeadingMenu(isColumn, index)
            return
        }
        val (col, row) = cellAt(x, y) ?: return
        val sheet = state
        if (sheet == null || col !in sheet.c1..sheet.c2 || row !in sheet.r1..sheet.r2) listener.onSelectCells(col, row, col, row)
        showActionMenu()
    }

    // Dragging a selection past the edges scrolls on
    private fun scrollAtEdges(event: MotionEvent) {
        val edge = 32 * density
        val dx = when { event.x > width - edge -> 16 * density; event.x < headerWidth + edge && mouseHeading != false -> -16 * density; else -> 0f }
        val dy = when { event.y > height - edge -> 16 * density; event.y < headerHeight + edge && mouseHeading != true -> -16 * density; else -> 0f }
        if (dx != 0f || dy != 0f) scrollTo(scrollPosX + dx, scrollPosY + dy)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL || !event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) {
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
        return true
    }

    // Arrows on the borders of headings, a crosshair on the selection's handle and the handles of
    // drawings, the cell cursor on cells
    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): android.view.PointerIcon? {
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (listener == null) return super.onResolvePointerIcon(event, pointerIndex)
        val grab = drawings.takeIf { it.isNotEmpty() }?.let { drawingHandles.hitTest(it, x, y, toScreen) }
        val type = when {
            columnBorderAt(x, y, true) >= 0 -> android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
            rowBorderAt(x, y, true) >= 0 -> android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
            headingAt(x, y) != null || x < headerWidth || y < headerHeight -> return super.onResolvePointerIcon(event, pointerIndex)
            grab != null -> if (grab.immediate) android.view.PointerIcon.TYPE_CROSSHAIR else android.view.PointerIcon.TYPE_ALL_SCROLL
            objectAt(x, y) -> android.view.PointerIcon.TYPE_ALL_SCROLL
            drawings.isEmpty() && isOnHandle(x, y, 8 * density) -> android.view.PointerIcon.TYPE_CROSSHAIR
            else -> android.view.PointerIcon.TYPE_CELL
        }
        return android.view.PointerIcon.getSystemIcon(context, type)
    }

    // ---------------------------------------------------------------------------------
    // Keyboard: hardware keys and typing start editing (soft keyboard input goes to the formula bar)

    override fun onCheckIsTextEditor(): Boolean = listener != null

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        if (listener == null) return null
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : android.view.inputmethod.BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (text.isNotEmpty()) listener?.onTypeText(text.toString())
                return true
            }

            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) handleKey(event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        handleKey(event) || super.onKeyDown(keyCode, event)

    private fun handleKey(event: android.view.KeyEvent): Boolean {
        val listener = listener ?: return false
        val shift = event.isShiftPressed
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> listener.onMoveSelection(-1, 0, shift)
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> listener.onMoveSelection(1, 0, shift)
            android.view.KeyEvent.KEYCODE_DPAD_UP -> listener.onMoveSelection(0, -1, shift)
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> listener.onMoveSelection(0, 1, shift)
            android.view.KeyEvent.KEYCODE_TAB -> listener.onMoveSelection(if (shift) -1 else 1, 0, false)
            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> listener.onMoveSelection(0, if (shift) -1 else 1, false)
            android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.KEYCODE_FORWARD_DEL -> listener.onClearCells()
            android.view.KeyEvent.KEYCODE_F2 -> listener.onEditCell()
            android.view.KeyEvent.KEYCODE_PAGE_UP -> listener.onMoveSelection(0, -rowsPerPage(), shift)
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> listener.onMoveSelection(0, rowsPerPage(), shift)
            android.view.KeyEvent.KEYCODE_MOVE_HOME -> {
                val sheet = state ?: return true
                if (event.isCtrlPressed) listener.onSelectCells(0, 0, 0, 0) else listener.onMoveSelection(-sheet.col, 0, shift)
            }
            else -> {
                if (event.isCtrlPressed) {
                    when (event.keyCode) {
                        android.view.KeyEvent.KEYCODE_C -> listener.onCopy()
                        android.view.KeyEvent.KEYCODE_X -> listener.onCut()
                        android.view.KeyEvent.KEYCODE_V -> listener.onPaste()
                        android.view.KeyEvent.KEYCODE_A -> listener.onSelectAll()
                        android.view.KeyEvent.KEYCODE_Z -> listener.onUndo(redo = shift)
                        android.view.KeyEvent.KEYCODE_Y -> listener.onUndo(redo = true)
                        android.view.KeyEvent.KEYCODE_B -> listener.onToggleStyle(Style.BOLD)
                        android.view.KeyEvent.KEYCODE_I -> listener.onToggleStyle(Style.ITALIC)
                        android.view.KeyEvent.KEYCODE_U -> listener.onToggleStyle(Style.UNDERLINE)
                        else -> return false
                    }
                    return true
                }
                val char = event.unicodeChar
                if (char == 0) return false
                listener.onTypeText(String(Character.toChars(char)))
            }
        }
        return true
    }

    private fun isOnHandle(x: Float, y: Float, radius: Float = handleTouchRadius): Boolean {
        val sheet = state ?: return false
        if (listener == null || sheet.c2 >= geometry.colCount || sheet.r2 >= geometry.rowCount) return false
        val handleX = columnRight(sheet.c2)
        val handleY = rowBottom(sheet.r2)
        return hypot(x - handleX, y - handleY) < radius
    }

    // Rows that fit on the screen, for Page Up and Page Down
    private fun rowsPerPage(): Int {
        val sheet = state ?: return 1
        val rowHeight = if (sheet.row < geometry.rowCount) rowBottom(sheet.row) - viewY(geometry.rows[sheet.row]) else 0f
        return if (rowHeight > 0) max(1, ((height - headerHeight) / rowHeight).toInt() - 1) else 1
    }

    // The active cell stays, the other corner follows the finger
    private fun extendSelection(event: MotionEvent) {
        val sheet = state ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val (col, row) = cellAt(event.x, event.y) ?: return
                val c2 = if (sheet.col == sheet.c1) sheet.c2 else sheet.c1
                val r2 = if (sheet.row == sheet.r1) sheet.r2 else sheet.r1
                if (col != c2 || row != r2) listener?.onSelectCells(sheet.col, sheet.row, col, row)
                // Near the edges, scroll on
                val edge = 32 * density
                val dx = when { event.x > width - edge -> 16 * density; event.x < headerWidth + edge -> -16 * density; else -> 0f }
                val dy = when { event.y > height - edge -> 16 * density; event.y < headerHeight + edge -> -16 * density; else -> 0f }
                if (dx != 0f || dy != 0f) scrollTo(scrollPosX + dx, scrollPosY + dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (!sheet.isSingleCell) showActionMenu()
            }
        }
    }

    // Dragging the handles or frame of selected drawings, as in DocumentView
    private fun handleDrawingTouch(event: MotionEvent): Boolean {
        val listener = listener ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawingDragging = false
                drawingGrab = drawings.takeIf { it.isNotEmpty() }?.let { drawingHandles.hitTest(it, event.x, event.y, toScreen) }
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
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    gestureDetector.onTouchEvent(cancel)
                    cancel.recycle()
                    startDrawingDrag(listener, grab)
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastMoveTime < MOVE_INTERVAL_MS) return true
                lastMoveTime = now
                listener.onTouchDrawing(TouchAction.MOVE, grab.xMm + (event.x - grabX) / scale(), grab.yMm + (event.y - grabY) / scale(), false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val grab = drawingGrab
                drawingGrab = null
                if (grab == null || !drawingDragging) return false
                drawingDragging = false
                listener.onTouchDrawing(TouchAction.UP, grab.xMm + (event.x - grabX) / scale(), grab.yMm + (event.y - grabY) / scale(), false)
                return true
            }
        }
        return drawingDragging
    }

    private fun startDrawingDrag(listener: SheetListener, grab: DrawingHandles.Grab) {
        drawingDragging = true
        hideActionMenu()
        parent?.requestDisallowInterceptTouchEvent(true)
        listener.onTouchDrawing(TouchAction.DOWN, grab.xMm, grab.yMm, false)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX.toFloat(), scroller.currY.toFloat())
            if (!scroller.isFinished) postInvalidateOnAnimation()
        }
    }

    // ---------------------------------------------------------------------------------
    // Menu: cut, copy, paste

    private fun showActionMenu() {
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
            menu.add(Menu.NONE, android.R.id.cut, 0, android.R.string.cut)
            menu.add(Menu.NONE, android.R.id.copy, 1, android.R.string.copy)
            menu.add(Menu.NONE, android.R.id.paste, 2, android.R.string.paste)
            menu.add(Menu.NONE, MENU_FILL, 3, Resources.string.editors_cells_fill)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(android.R.id.paste).isVisible =
                context.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true
            menu.findItem(MENU_FILL).isVisible = state?.isSingleCell == false
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val listener = listener ?: return false
            when (item.itemId) {
                android.R.id.cut -> listener.onCut()
                android.R.id.copy -> listener.onCopy()
                android.R.id.paste -> listener.onPaste()
                MENU_FILL -> listener.onFillSelection()
                else -> return false
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val sheet = state ?: return
            selectionRect(sheet)?.let { rect ->
                rect.intersect(0f, 0f, width.toFloat(), height.toFloat())
                rect.roundOut(outRect)
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        if (geometry.colCount == 0) return

        // Cells, clipped to the area next to the headings
        canvas.save()
        canvas.clipRect(headerWidth, headerHeight, width.toFloat(), height.toFloat())
        val sheetRight = viewX(geometry.widthMm)
        val sheetBottom = viewY(geometry.heightMm)
        canvas.drawRect(headerWidth, headerHeight, sheetRight, sheetBottom, cellBackground)
        val level = levelFor(zoom)
        for (region in regions()) {
            canvas.save()
            canvas.clipRect(region.area)
            if (fallbackLevel != level) drawTiles(canvas, fallbackLevel, region)
            drawTiles(canvas, level, region)
            canvas.restore()
        }
        if (frozenWidth() > 0) canvas.drawLine(headerWidth + frozenWidth(), headerHeight, headerWidth + frozenWidth(), height.toFloat(), freezePaint)
        if (frozenHeight() > 0) canvas.drawLine(headerWidth, headerHeight + frozenHeight(), width.toFloat(), headerHeight + frozenHeight(), freezePaint)
        foreignSelections.forEach { drawForeignSelection(canvas, it) }
        if (drawings.isEmpty()) {
            state?.let { drawSelection(canvas, it) }
        } else if (listener != null) {
            drawingHandles.draw(canvas, drawings, toScreen)
        }
        drawEditingText(canvas)
        canvas.restore()

        drawHeadings(canvas)
        // Where a resized column or row will end
        if (resizingColumn >= 0) canvas.drawLine(resizePosition, 0f, resizePosition, height.toFloat(), selectionBorder)
        if (resizingRow >= 0) canvas.drawLine(0f, resizePosition, width.toFloat(), resizePosition, selectionBorder)
    }

    // A part of the view and how it is scrolled: frozen columns and rows don't scroll
    private class Region(val area: RectF, val scrollX: Float, val scrollY: Float)

    private fun regions(): List<Region> {
        val left = headerWidth + frozenWidth()
        val top = headerHeight + frozenHeight()
        val w = width.toFloat()
        val h = height.toFloat()
        return listOf(
            Region(RectF(left, top, w, h), scrollPosX, scrollPosY),
            Region(RectF(left, headerHeight, w, top), scrollPosX, 0f),
            Region(RectF(headerWidth, top, left, h), 0f, scrollPosY),
            Region(RectF(headerWidth, headerHeight, left, top), 0f, 0f)
        ).filter { it.area.width() > 0 && it.area.height() > 0 }
    }

    // The text being edited, over the active cell and as far right as it needs
    private fun drawEditingText(canvas: Canvas) {
        val text = editingText ?: return
        val sheet = state ?: return
        if (sheet.col >= geometry.colCount || sheet.row >= geometry.rowCount) return
        val left = viewX(geometry.cols[sheet.col])
        val top = viewY(geometry.rows[sheet.row])
        val right = maxOf(columnRight(sheet.col), left + editTextPaint.measureText(text) + 8 * density)
        val bottom = maxOf(rowBottom(sheet.row), top + editTextPaint.textSize * 1.4f)
        canvas.drawRect(left, top, right, bottom, cellBackground)
        canvas.drawText(text, left + 4 * density, (top + bottom) / 2 - (editTextPaint.descent() + editTextPaint.ascent()) / 2, editTextPaint)
        canvas.drawRect(left, top, right, bottom, selectionBorder)
    }

    private fun drawTiles(canvas: Canvas, level: Int, region: Region) {
        val levelScale = scaleOfLevel(level)
        val factor = scale() / levelScale
        visibleTiles(level, region).forEach { key ->
            val tile = tiles.get(key) ?: return@forEach
            val left = headerWidth + key.column * tileSize * factor - region.scrollX
            val top = headerHeight + key.row * tileSize * factor - region.scrollY
            canvas.drawBitmap(
                tile.bitmap, null,
                RectF(left, top, left + tile.bitmap.width * factor, top + tile.bitmap.height * factor),
                bitmapPaint
            )
        }
    }

    private fun selectionRect(sheet: SheetState): RectF? {
        if (sheet.c1 >= geometry.colCount || sheet.r1 >= geometry.rowCount) return null
        // Whole rows and columns end at the last laid-out cell
        val c2 = minOf(sheet.c2, geometry.colCount - 1)
        val r2 = minOf(sheet.r2, geometry.rowCount - 1)
        return RectF(
            viewX(geometry.cols[sheet.c1]), viewY(geometry.rows[sheet.r1]),
            columnRight(c2), rowBottom(r2)
        )
    }

    private fun drawSelection(canvas: Canvas, sheet: SheetState) {
        val rect = selectionRect(sheet) ?: return
        if (!sheet.isSingleCell) {
            // Shaded, except for the active cell
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(rect, Path.Direction.CW)
                if (sheet.col < geometry.colCount && sheet.row < geometry.rowCount) {
                    addRect(
                        viewX(geometry.cols[sheet.col]), viewY(geometry.rows[sheet.row]),
                        columnRight(sheet.col), rowBottom(sheet.row),
                        Path.Direction.CW
                    )
                }
            }
            canvas.drawPath(path, selectionFill)
        }
        canvas.drawRect(rect, selectionBorder)
        if (listener != null) canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint)
    }

    // Someone else's cells: framed in their color, with their name above the first range
    private fun drawForeignSelection(canvas: Canvas, selection: ForeignSelection) {
        foreignPaint.color = runCatching { Color.parseColor(selection.color) }.getOrDefault(Color.GRAY)
        val rects = selection.ranges.mapNotNull { range ->
            if (range.c1 >= geometry.colCount || range.r1 >= geometry.rowCount) return@mapNotNull null
            val c2 = minOf(range.c2, geometry.colCount - 1)
            val r2 = minOf(range.r2, geometry.rowCount - 1)
            RectF(viewX(geometry.cols[range.c1]), viewY(geometry.rows[range.r1]), columnRight(c2), rowBottom(r2))
        }
        foreignPaint.style = Paint.Style.STROKE
        rects.forEach { canvas.drawRect(it, foreignPaint) }
        val first = rects.firstOrNull() ?: return
        if (selection.name.isEmpty()) return
        val padding = 3 * density
        val textHeight = foreignNamePaint.fontMetrics.let { it.descent - it.ascent }
        val tag = RectF(first.left, first.top - textHeight - 2 * padding, first.left + foreignNamePaint.measureText(selection.name) + 2 * padding, first.top)
        foreignPaint.style = Paint.Style.FILL
        canvas.drawRect(tag, foreignPaint)
        canvas.drawText(selection.name, tag.left + padding, tag.bottom - padding - foreignNamePaint.fontMetrics.descent, foreignNamePaint)
    }

    private fun drawHeadings(canvas: Canvas) {
        val sheet = state
        val textOffset = (headerTextPaint.descent() + headerTextPaint.ascent()) / 2

        val frozenCol = minOf(state?.frozenCol ?: 0, geometry.colCount)
        val frozenRow = minOf(state?.frozenRow ?: 0, geometry.rowCount)

        // Column headings: A, B, ... at the top; the frozen ones, then those scrolled into view
        canvas.drawRect(headerWidth, 0f, width.toFloat(), headerHeight, headerPaint)
        fun drawColumn(col: Int) {
            val left = viewX(geometry.cols[col])
            val right = columnRight(col)
            if (right - left < 1f) return
            if (sheet != null && col in sheet.c1..sheet.c2) canvas.drawRect(left, 0f, right, headerHeight, headerSelectedPaint)
            canvas.drawLine(right, 0f, right, headerHeight, headerLinePaint)
            if (right - left > headerTextPaint.textSize) {
                canvas.drawText(columnName(col), (left + right) / 2, headerHeight / 2 - textOffset, headerTextPaint)
            }
        }
        for (col in 0 until frozenCol) drawColumn(col)
        canvas.save()
        canvas.clipRect(headerWidth + frozenWidth(), 0f, width.toFloat(), headerHeight)
        var col = maxOf(frozenCol, geometry.columnAt(((scrollPosX + frozenWidth()) / scale()).toDouble()))
        while (col < geometry.colCount && viewX(geometry.cols[col]) <= width) drawColumn(col++)
        canvas.restore()

        // Row headings: 1, 2, ... on the left
        canvas.drawRect(0f, headerHeight, headerWidth, height.toFloat(), headerPaint)
        fun drawRow(row: Int) {
            val top = viewY(geometry.rows[row])
            val bottom = rowBottom(row)
            if (bottom - top < 1f) return
            if (sheet != null && row in sheet.r1..sheet.r2) canvas.drawRect(0f, top, headerWidth, bottom, headerSelectedPaint)
            canvas.drawLine(0f, bottom, headerWidth, bottom, headerLinePaint)
            if (bottom - top > headerTextPaint.textSize * 0.8f) {
                canvas.drawText((row + 1).toString(), headerWidth / 2, (top + bottom) / 2 - textOffset, headerTextPaint)
            }
        }
        for (row in 0 until frozenRow) drawRow(row)
        canvas.save()
        canvas.clipRect(0f, headerHeight + frozenHeight(), headerWidth, height.toFloat())
        var row = maxOf(frozenRow, geometry.rowAt(((scrollPosY + frozenHeight()) / scale()).toDouble()))
        while (row < geometry.rowCount && viewY(geometry.rows[row]) <= height) drawRow(row++)
        canvas.restore()

        // Corner and the lines between headings and cells
        canvas.drawRect(0f, 0f, headerWidth, headerHeight, headerPaint)
        canvas.drawLine(headerWidth, 0f, headerWidth, height.toFloat(), headerLinePaint)
        canvas.drawLine(0f, headerHeight, width.toFloat(), headerHeight, headerLinePaint)
    }

    // ---------------------------------------------------------------------------------
    // Rendering

    // Zoom levels: scales a factor of the square root of 2 apart
    private fun levelFor(zoom: Float): Int = floor(ln(zoom.toDouble()) / ln(LEVEL_STEP) + 0.5).toInt()

    private fun scaleOfLevel(level: Int): Float = baseScale * LEVEL_STEP.pow(level).toFloat()

    private fun visibleTiles(level: Int, region: Region): List<TileKey> {
        val levelScale = scaleOfLevel(level)
        val factor = scale() / levelScale
        // Content visible in the region, in pixels of the level
        val left = (region.area.left - headerWidth + region.scrollX) / factor
        val top = (region.area.top - headerHeight + region.scrollY) / factor
        val right = min((region.area.right - headerWidth + region.scrollX) / factor, (geometry.widthMm * levelScale).toFloat())
        val bottom = min((region.area.bottom - headerHeight + region.scrollY) / factor, (geometry.heightMm * levelScale).toFloat())
        if (right <= left || bottom <= top) return emptyList()
        val keys = mutableListOf<TileKey>()
        for (row in (top / tileSize).toInt()..(bottom / tileSize).toInt()) {
            for (column in (left / tileSize).toInt()..(right / tileSize).toInt()) {
                keys.add(TileKey(level, column, row))
            }
        }
        return keys
    }

    // Renders the missing and outdated visible tiles at the current level, one after the other,
    // nearest to the center first; the list is computed again after each tile
    private fun requestTiles() {
        if (renderJob?.isActive == true || width == 0) return
        val renderer = renderer ?: return
        renderJob = scope?.launch {
            while (true) {
                val level = levelFor(zoom)
                val needed = regions().flatMap { visibleTiles(level, it) }.distinct().filter { tiles.get(it)?.version != version }
                if (needed.isEmpty()) {
                    // All tiles of the level are there: the fallback isn't needed any more
                    fallbackLevel = level
                    invalidate()
                    break
                }
                val key = needed.first()
                val levelScale = scaleOfLevel(level)
                val pageWidth = (geometry.widthMm * levelScale).roundToInt()
                val pageHeight = (geometry.heightMm * levelScale).roundToInt()
                val x = key.column * tileSize
                val y = key.row * tileSize
                val tileWidth = min(tileSize, pageWidth - x)
                val tileHeight = min(tileSize, pageHeight - y)
                if (tileWidth <= 0 || tileHeight <= 0) {
                    tiles.put(key, Tile(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), version))
                    continue
                }
                val renderedVersion = version
                val bitmap = renderer.render(0, pageWidth, pageHeight, x, y, tileWidth, tileHeight) ?: break
                tiles.put(key, Tile(bitmap, renderedVersion))
                invalidate()
            }
        }
    }

    private fun cacheBytes(): Int = min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6).toInt()

    private companion object {
        const val LEVEL_STEP = 1.4142135623730951
        const val MOVE_INTERVAL_MS = 40L
        const val DOUBLE_CLICK_MS = 400L
        const val WHEEL_ZOOM_STEP = 0.1f
        const val MENU_FILL = 1

    }
}

/** A, B, ..., Z, AA, AB, ... */
internal fun columnName(index: Int): String {
    var number = index + 1
    val name = StringBuilder()
    while (number > 0) {
        val rest = (number - 1) % 26
        name.insert(0, ('A' + rest))
        number = (number - 1) / 26
    }
    return name.toString()
}

/** A cell reference like "B2", or a range like "B2:D5". */
internal fun cellReference(c1: Int, r1: Int, c2: Int = c1, r2: Int = r1): String {
    val start = columnName(minOf(c1, c2)) + (minOf(r1, r2) + 1)
    return if (c1 == c2 && r1 == r2) start else start + ":" + columnName(maxOf(c1, c2)) + (maxOf(r1, r2) + 1)
}
