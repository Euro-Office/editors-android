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
import lib.editors.base.engine.SheetGeometry
import lib.editors.base.engine.SheetState
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
 * place, scrolling and zoom, the selection and a handle to extend it.
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
    }

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
    private var actionMode: ActionMode? = null

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
    private val selectionFill = Paint().apply { color = ContextCompat.getColor(context, Resources.color.editors_selection) }
    private val selectionBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, Resources.color.editors_selection_handle)
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, Resources.color.editors_selection_handle)
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
    fun setState(sheet: SheetState, changed: Boolean) {
        val previous = state
        state = sheet
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

    private fun viewX(mm: Double): Float = (headerWidth + mm * scale() - scrollPosX).toFloat()

    private fun viewY(mm: Double): Float = (headerHeight + mm * scale() - scrollPosY).toFloat()

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (geometry.colCount == 0 || geometry.rowCount == 0) return null
        val xMm = (x - headerWidth + scrollPosX) / scale()
        val yMm = (y - headerHeight + scrollPosY) / scale()
        return geometry.columnAt(xMm.toDouble()) to geometry.rowAt(yMm.toDouble())
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
        val right = viewX(geometry.cols[col + 1])
        val top = viewY(geometry.rows[row])
        val bottom = viewY(geometry.rows[row + 1])
        if (right > width - margin) dx = right - (width - margin)
        if (left - dx < headerWidth + margin) dx = left - headerWidth - margin
        if (bottom > height - margin) dy = bottom - (height - margin)
        if (top - dy < headerHeight + margin) dy = top - headerHeight - margin
        if (dx != 0f || dy != 0f) scrollTo(scrollPosX + dx, scrollPosY + dy)
    }

    // ---------------------------------------------------------------------------------
    // Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isOnHandle(event.x, event.y)) {
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

    private fun isOnHandle(x: Float, y: Float): Boolean {
        val sheet = state ?: return false
        if (listener == null || sheet.c2 >= geometry.colCount || sheet.r2 >= geometry.rowCount) return false
        val handleX = viewX(geometry.cols[sheet.c2 + 1])
        val handleY = viewY(geometry.rows[sheet.r2 + 1])
        return hypot(x - handleX, y - handleY) < handleTouchRadius
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
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(android.R.id.paste).isVisible =
                context.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val listener = listener ?: return false
            when (item.itemId) {
                android.R.id.cut -> listener.onCut()
                android.R.id.copy -> listener.onCopy()
                android.R.id.paste -> listener.onPaste()
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
        if (fallbackLevel != level) drawTiles(canvas, fallbackLevel)
        drawTiles(canvas, level)
        state?.let { drawSelection(canvas, it) }
        canvas.restore()

        drawHeadings(canvas)
    }

    private fun drawTiles(canvas: Canvas, level: Int) {
        val levelScale = scaleOfLevel(level)
        val factor = scale() / levelScale
        visibleTiles(level).forEach { key ->
            val tile = tiles.get(key) ?: return@forEach
            val left = headerWidth + key.column * tileSize * factor - scrollPosX
            val top = headerHeight + key.row * tileSize * factor - scrollPosY
            canvas.drawBitmap(
                tile.bitmap, null,
                RectF(left, top, left + tile.bitmap.width * factor, top + tile.bitmap.height * factor),
                bitmapPaint
            )
        }
    }

    private fun selectionRect(sheet: SheetState): RectF? {
        if (sheet.c2 >= geometry.colCount || sheet.r2 >= geometry.rowCount) return null
        return RectF(
            viewX(geometry.cols[sheet.c1]), viewY(geometry.rows[sheet.r1]),
            viewX(geometry.cols[sheet.c2 + 1]), viewY(geometry.rows[sheet.r2 + 1])
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
                        viewX(geometry.cols[sheet.col + 1]), viewY(geometry.rows[sheet.row + 1]),
                        Path.Direction.CW
                    )
                }
            }
            canvas.drawPath(path, selectionFill)
        }
        canvas.drawRect(rect, selectionBorder)
        if (listener != null) canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint)
    }

    private fun drawHeadings(canvas: Canvas) {
        val sheet = state
        val textOffset = (headerTextPaint.descent() + headerTextPaint.ascent()) / 2

        // Column headings: A, B, ... at the top
        canvas.drawRect(headerWidth, 0f, width.toFloat(), headerHeight, headerPaint)
        val firstCol = geometry.columnAt(((scrollPosX) / scale()).toDouble())
        var col = firstCol
        while (col < geometry.colCount) {
            val left = viewX(geometry.cols[col])
            if (left > width) break
            val right = viewX(geometry.cols[col + 1])
            if (right - left >= 1f) {
                if (sheet != null && col in sheet.c1..sheet.c2) canvas.drawRect(left, 0f, right, headerHeight, headerSelectedPaint)
                canvas.drawLine(right, 0f, right, headerHeight, headerLinePaint)
                if (right - left > headerTextPaint.textSize) {
                    canvas.drawText(columnName(col), (left + right) / 2, headerHeight / 2 - textOffset, headerTextPaint)
                }
            }
            col++
        }

        // Row headings: 1, 2, ... on the left
        canvas.drawRect(0f, headerHeight, headerWidth, height.toFloat(), headerPaint)
        var row = geometry.rowAt((scrollPosY / scale()).toDouble())
        while (row < geometry.rowCount) {
            val top = viewY(geometry.rows[row])
            if (top > height) break
            val bottom = viewY(geometry.rows[row + 1])
            if (bottom - top >= 1f) {
                if (sheet != null && row in sheet.r1..sheet.r2) canvas.drawRect(0f, top, headerWidth, bottom, headerSelectedPaint)
                canvas.drawLine(0f, bottom, headerWidth, bottom, headerLinePaint)
                if (bottom - top > headerTextPaint.textSize * 0.8f) {
                    canvas.drawText((row + 1).toString(), headerWidth / 2, (top + bottom) / 2 - textOffset, headerTextPaint)
                }
            }
            row++
        }

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

    private fun visibleTiles(level: Int): List<TileKey> {
        val levelScale = scaleOfLevel(level)
        val factor = scale() / levelScale
        // Visible content, in pixels of the level
        val left = scrollPosX / factor
        val top = scrollPosY / factor
        val right = min((scrollPosX + width - headerWidth) / factor, (geometry.widthMm * levelScale).toFloat())
        val bottom = min((scrollPosY + height - headerHeight) / factor, (geometry.heightMm * levelScale).toFloat())
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
                val needed = visibleTiles(level).filter { tiles.get(it)?.version != version }
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

        // A, B, ..., Z, AA, AB, ...
        fun columnName(index: Int): String {
            var number = index + 1
            val name = StringBuilder()
            while (number > 0) {
                val rest = (number - 1) % 26
                name.insert(0, ('A' + rest))
                number = (number - 1) / 26
            }
            return name.toString()
        }
    }
}
