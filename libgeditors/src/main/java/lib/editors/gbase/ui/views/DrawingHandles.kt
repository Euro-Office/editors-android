package lib.editors.gbase.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import androidx.core.content.ContextCompat
import lib.editors.base.engine.DrawingFrame
import lib.editors.resources.R as Resources
import kotlin.math.hypot

/**
 * Selected pictures and shapes on screen: their frames with handles, and what a touch grabs.
 *
 * sdkjs moves, resizes and rotates drawings itself, from mouse events at its handles. So a drag
 * presses at the grabbed handle (or on the frame's edge, to move) and moves by the finger's
 * offset; the frames sdkjs reports while dragging are drawn as the preview.
 */
internal class DrawingHandles(context: Context) {

    /** Converts page millimeters to view pixels. */
    fun interface ToScreen {
        fun point(page: Int, xMm: Double, yMm: Double): PointF
    }

    /**
     * What a touch grabbed: the point to press in page millimeters. [immediate] for handles;
     * a move starts only once the finger moves, so a tap still reaches the drawing's text.
     */
    class Grab(val page: Int, val xMm: Double, val yMm: Double, val immediate: Boolean)

    private val density = context.resources.displayMetrics.density
    private val touchRadius = 24 * density
    private val handleRadius = 6 * density

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, Resources.color.editors_selection_handle)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val previewPaint = Paint(framePaint).apply {
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val handleBorder = Paint(framePaint)

    // Corners and edge middles of a frame, in page millimeters (resize handles)
    private fun resizePoints(frame: DrawingFrame): List<Pair<Double, Double>> {
        val p = frame.points
        val corners = (0 until 4).map { p[2 * it] to p[2 * it + 1] }
        val middles = (0 until 4).map {
            val next = (it + 1) % 4
            (p[2 * it] + p[2 * next]) / 2 to (p[2 * it + 1] + p[2 * next + 1]) / 2
        }
        return corners + middles
    }

    fun hitTest(frames: List<DrawingFrame>, x: Float, y: Float, toScreen: ToScreen): Grab? {
        for (frame in frames) {
            if (frame.tracking) continue
            frame.rotateHandle?.let { (rx, ry) ->
                val point = toScreen.point(frame.page, rx, ry)
                if (hypot(x - point.x, y - point.y) < touchRadius) return Grab(frame.page, rx, ry, immediate = true)
            }
            if (frame.canResize) {
                for ((hx, hy) in resizePoints(frame)) {
                    val point = toScreen.point(frame.page, hx, hy)
                    if (hypot(x - point.x, y - point.y) < touchRadius) return Grab(frame.page, hx, hy, immediate = true)
                }
            }
            if (frame.canMove && isInside(frame, x, y, toScreen)) {
                // On the top edge a quarter of the way along: sdkjs moves drawings dragged by
                // their edge (inside a text box, a press selects text), away from the handles
                val p = frame.points
                return Grab(frame.page, p[0] * 0.75 + p[2] * 0.25, p[1] * 0.75 + p[3] * 0.25, immediate = false)
            }
        }
        return null
    }

    fun isInside(frame: DrawingFrame, x: Float, y: Float, toScreen: ToScreen): Boolean =
        isInsidePolygon(frame.page, frame.points, x, y, toScreen)

    /** Whether the view point is inside the quadrilateral (page millimeters). */
    fun isInsidePolygon(page: Int, points: DoubleArray, x: Float, y: Float, toScreen: ToScreen): Boolean {
        val corners = (0 until 4).map { toScreen.point(page, points[2 * it], points[2 * it + 1]) }
        var inside = false
        var j = 3
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun draw(canvas: Canvas, frames: List<DrawingFrame>, toScreen: ToScreen) {
        for (frame in frames) {
            val corners = (0 until 4).map { toScreen.point(frame.page, frame.points[2 * it], frame.points[2 * it + 1]) }
            val path = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                corners.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            if (frame.tracking) {
                canvas.drawPath(path, previewPaint)
                continue
            }
            canvas.drawPath(path, framePaint)
            frame.rotateHandle?.let { (rx, ry) ->
                val handle = toScreen.point(frame.page, rx, ry)
                val top = toScreen.point(frame.page, (frame.points[0] + frame.points[2]) / 2, (frame.points[1] + frame.points[3]) / 2)
                canvas.drawLine(top.x, top.y, handle.x, handle.y, framePaint)
                canvas.drawCircle(handle.x, handle.y, handleRadius, handleFill)
                canvas.drawCircle(handle.x, handle.y, handleRadius, handleBorder)
            }
            if (frame.canResize) {
                for ((hx, hy) in resizePoints(frame)) {
                    val point = toScreen.point(frame.page, hx, hy)
                    canvas.drawCircle(point.x, point.y, handleRadius, handleFill)
                    canvas.drawCircle(point.x, point.y, handleRadius, handleBorder)
                }
            }
        }
    }

    private operator fun DoubleArray.component1() = this[0]
    private operator fun DoubleArray.component2() = this[1]
}
