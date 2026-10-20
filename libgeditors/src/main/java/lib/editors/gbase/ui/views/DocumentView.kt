package lib.editors.gbase.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import lib.editors.base.engine.PageSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows document pages below each other, with scrolling and zoom.
 *
 * Each page is rendered once at the width of the view (zoom 1) and cached. When zoomed in,
 * the visible part of each page is rendered again at full resolution once the gesture ends.
 */
class DocumentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Renders the area (x, y, width, height) of a page drawn at pageWidth x pageHeight pixels. */
    fun interface Renderer {
        suspend fun render(page: Int, pageWidth: Int, pageHeight: Int, x: Int, y: Int, width: Int, height: Int): Bitmap?
    }

    // contentRect: in pixels of the zoomed content (screen position plus scroll position)
    private class Detail(val zoom: Float, val contentRect: Rect, val bitmap: Bitmap)

    private val density = resources.displayMetrics.density
    private val pageMargin = 12 * density
    private val maxZoom = 5f

    private var pageSizes: List<PageSize> = emptyList()
    private var renderer: Renderer? = null
    private var scope: CoroutineScope? = null

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
    private var details: List<Detail> = emptyList()
    private var detailJob: Job? = null

    private val paperPaint = Paint().apply { color = Color.WHITE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scroller = OverScroller(context)

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

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomTo(if (zoom > 1.5f) 1f else 2.5f, e.x, e.y)
            requestDetails()
            return true
        }
    })

    fun setPages(sizes: List<PageSize>, renderer: Renderer, scope: CoroutineScope) {
        this.pageSizes = sizes
        this.renderer = renderer
        this.scope = scope
        layoutPages()
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && scroller.isFinished) requestDetails()
        return true
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
        invalidate()
    }

    private fun zoomTo(newZoom: Float, focusX: Float, focusY: Float) {
        val target = newZoom.coerceIn(1f, maxZoom)
        val factor = target / zoom
        zoom = target
        details = emptyList()
        scrollTo((scrollPosX + focusX) * factor - focusX, (scrollPosY + focusY) * factor - focusY)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BACKGROUND)
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
                requestPage(page)
            }
        }
        canvas.restore()

        // Sharp renders of the visible areas
        canvas.save()
        canvas.translate(-scrollPosX, -scrollPosY)
        for (detail in details) {
            if (detail.zoom == zoom) canvas.drawBitmap(detail.bitmap, null, detail.contentRect, null)
        }
        canvas.restore()
    }

    private fun requestPage(page: Int) {
        val renderer = renderer ?: return
        if (pageJobs[page]?.isActive == true) return
        val width = pageWidth.roundToInt()
        val height = pageHeights[page].roundToInt()
        pageJobs[page] = scope?.launch {
            val bitmap = renderer.render(page, width, height, 0, 0, width, height)
            if (bitmap != null && width == pageWidth.roundToInt()) {
                pageBitmaps.put(page, bitmap)
                invalidate()
            }
        } ?: return
    }

    // Renders the visible part of each visible page at the current zoom
    private fun requestDetails() {
        val renderer = renderer ?: return
        detailJob?.cancel()
        details = emptyList()
        if (zoom <= 1.05f) {
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
        val BACKGROUND = Color.rgb(0xE8, 0xE8, 0xE8)
    }
}
