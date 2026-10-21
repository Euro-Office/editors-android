package lib.editors.gbase.ui.activities

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lib.editors.base.engine.SlideInfo
import lib.editors.base.engine.SlideTransition
import lib.editors.resources.R as Resources

/**
 * A slideshow of the visible slides, full screen: a tap on the right (or a swipe to the left)
 * goes on, on the left back. Transitions are drawn here from the slides' transition settings
 * (fade, push, wipe, split, cover, uncover, zoom; others fade). The presenter view adds the
 * notes, the next slide and the time. Animations of objects aren't played: slides show their
 * final state.
 */
internal class Slideshow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val slides: List<SlideInfo>,
    /** The slide at the given size in pixels. */
    private val render: suspend (page: Int, width: Int, height: Int) -> Bitmap?,
    private val slideAspect: Float,
    startSlide: Int
) {

    private val order = slides.indices.filter { !slides[it].hidden }
    private var position = order.indexOfFirst { it >= startSlide }.coerceAtLeast(0)
    private val density = context.resources.displayMetrics.density
    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val view = SlideView(context)
    private val presenter = LinearLayout(context)
    private val status = TextView(context)
    private val notes = TextView(context)
    private val nextSlide = ImageView(context)
    private val startTime = SystemClock.elapsedRealtime()
    private var advanceJob: Job? = null
    private var clockJob: Job? = null

    fun start() {
        if (order.isEmpty()) return
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(presenterView(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            addView(TextView(context).apply {
                text = context.getString(Resources.string.editors_slides_presenter)
                setTextColor(Color.WHITE)
                setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
                alpha = 0.6f
                setOnClickListener { presenter.isVisible = !presenter.isVisible }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }
        dialog.setContentView(root)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.setOnDismissListener {
            advanceJob?.cancel()
            clockJob?.cancel()
        }
        dialog.show()
        view.post { show(position, forward = true, animate = false) }
        clockJob = scope.launch {
            while (true) {
                updateStatus()
                delay(1000)
            }
        }
    }

    private fun go(delta: Int) {
        val target = position + delta
        if (target < 0) return
        if (target >= order.size) {
            dialog.dismiss()
            return
        }
        show(target, forward = delta > 0, animate = delta > 0)
    }

    private fun show(newPosition: Int, forward: Boolean, animate: Boolean) {
        position = newPosition
        val slide = order[newPosition]
        advanceJob?.cancel()
        advanceJob = scope.launch {
            val (width, height) = view.slideSize()
            val bitmap = render(slide, width, height) ?: return@launch
            view.show(bitmap, if (animate) slides[slide].transition else null)
            updateStatus()
            notes.text = slides[slide].notes.ifEmpty { context.getString(Resources.string.editors_slides_no_notes) }
            nextSlide.setImageBitmap(order.getOrNull(newPosition + 1)?.let { render(it, width / 4, height / 4) })
            // Automatic advance
            val transition = slides[slide].transition
            if (transition != null && transition.advanceAfter) {
                delay(transition.durationMs.toLong() + transition.afterMs)
                go(1)
            }
        }
    }

    private fun updateStatus() {
        val seconds = (SystemClock.elapsedRealtime() - startTime) / 1000
        status.text = context.getString(
            Resources.string.editors_slides_status, position + 1, order.size, seconds / 60, seconds % 60
        )
    }

    private fun presenterView(): View = presenter.apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(0xCC000000.toInt())
        setPaddingRelative(dp(16), dp(12), dp(16), dp(16))
        isVisible = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(status.apply { setTextColor(Color.WHITE); textSize = 16f })
            addView(ScrollView(context).apply {
                addView(notes.apply { setTextColor(Color.WHITE); textSize = 16f; setPaddingRelative(0, dp(8), 0, 0) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(nextSlide.apply { adjustViewBounds = true }, LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12)
        })
    }

    private fun dp(value: Int) = (value * density).toInt()

    // ---------------------------------------------------------------------------------

    private inner class SlideView(context: Context) : View(context) {

        private var current: Bitmap? = null
        private var previous: Bitmap? = null
        private var transition: SlideTransition? = null
        private var progress = 1f
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var animator: ValueAnimator? = null

        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                go(if (e.x < width / 3f) -1 else 1)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY)) return false
                go(if (velocityX < 0) 1 else -1)
                return true
            }
        })

        override fun onTouchEvent(event: MotionEvent): Boolean {
            gestures.onTouchEvent(event)
            return true
        }

        /** The size slides are rendered at: the largest that fits. */
        fun slideSize(): Pair<Int, Int> {
            val area = slideArea()
            return area.width().toInt().coerceAtLeast(1) to area.height().toInt().coerceAtLeast(1)
        }

        private fun slideArea(): RectF {
            val w = width.toFloat()
            val h = height.toFloat()
            return if (w / h > slideAspect) {
                val sw = h * slideAspect
                RectF((w - sw) / 2, 0f, (w + sw) / 2, h)
            } else {
                val sh = w / slideAspect
                RectF(0f, (h - sh) / 2, w, (h + sh) / 2)
            }
        }

        fun show(bitmap: Bitmap, newTransition: SlideTransition?) {
            animator?.cancel()
            previous = current
            current = bitmap
            transition = newTransition
            if (newTransition == null || newTransition.type == 0 || previous == null || newTransition.durationMs <= 0) {
                progress = 1f
                invalidate()
                return
            }
            progress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = newTransition.durationMs.toLong()
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val area = slideArea()
            val now = current ?: return
            val before = previous
            val t = transition
            if (before == null || t == null || progress >= 1f) {
                draw(canvas, now, area, 0f, 0f, 255)
                return
            }
            val p = progress
            // The side the new slide comes from: Asc.c_oAscSlideTransitionParams left 2, top 3, right 4, bottom 5
            val (fx, fy) = when (t.option) {
                2 -> -1f to 0f
                3 -> 0f to -1f
                4 -> 1f to 0f
                else -> if (t.type == 2) 0f to 1f else 1f to 0f
            }
            val w = area.width()
            val h = area.height()
            canvas.save()
            canvas.clipRect(area)
            when (t.type) {
                // Fade: smoothly, or through black (option 1)
                1 -> if (t.option == 1) {
                    if (p < 0.5f) draw(canvas, before, area, 0f, 0f, ((1 - 2 * p) * 255).toInt())
                    else draw(canvas, now, area, 0f, 0f, ((2 * p - 1) * 255).toInt())
                } else {
                    draw(canvas, before, area, 0f, 0f, 255)
                    draw(canvas, now, area, 0f, 0f, (p * 255).toInt())
                }
                // Push: both move
                2 -> {
                    draw(canvas, before, area, -fx * p * w, -fy * p * h, 255)
                    draw(canvas, now, area, fx * (1 - p) * w, fy * (1 - p) * h, 255)
                }
                // Wipe: the new slide is revealed from its side
                3 -> {
                    draw(canvas, before, area, 0f, 0f, 255)
                    canvas.save()
                    when {
                        fx < 0 -> canvas.clipRect(area.left, area.top, area.left + p * w, area.bottom)
                        fx > 0 -> canvas.clipRect(area.right - p * w, area.top, area.right, area.bottom)
                        fy < 0 -> canvas.clipRect(area.left, area.top, area.right, area.top + p * h)
                        else -> canvas.clipRect(area.left, area.bottom - p * h, area.right, area.bottom)
                    }
                    draw(canvas, now, area, 0f, 0f, 255)
                    canvas.restore()
                }
                // Split: from the middle out
                4 -> {
                    draw(canvas, before, area, 0f, 0f, 255)
                    canvas.save()
                    val vertical = t.option == 10 || t.option == 11
                    if (vertical) canvas.clipRect(area.centerX() - p * w / 2, area.top, area.centerX() + p * w / 2, area.bottom)
                    else canvas.clipRect(area.left, area.centerY() - p * h / 2, area.right, area.centerY() + p * h / 2)
                    draw(canvas, now, area, 0f, 0f, 255)
                    canvas.restore()
                }
                // Uncover: the old slide moves away
                5 -> {
                    draw(canvas, now, area, 0f, 0f, 255)
                    draw(canvas, before, area, -fx * p * w, -fy * p * h, 255)
                }
                // Cover: the new slide moves over the old one
                6 -> {
                    draw(canvas, before, area, 0f, 0f, 255)
                    draw(canvas, now, area, fx * (1 - p) * w, fy * (1 - p) * h, 255)
                }
                // Zoom: the new slide grows in
                8 -> {
                    draw(canvas, before, area, 0f, 0f, 255)
                    val scale = 0.3f + 0.7f * p
                    canvas.save()
                    canvas.scale(scale, scale, area.centerX(), area.centerY())
                    draw(canvas, now, area, 0f, 0f, (p * 255).toInt())
                    canvas.restore()
                }
                else -> {
                    draw(canvas, before, area, 0f, 0f, 255)
                    draw(canvas, now, area, 0f, 0f, (p * 255).toInt())
                }
            }
            canvas.restore()
        }

        private fun draw(canvas: Canvas, bitmap: Bitmap, area: RectF, dx: Float, dy: Float, alpha: Int) {
            paint.alpha = alpha.coerceIn(0, 255)
            canvas.drawBitmap(bitmap, null, RectF(area.left + dx, area.top + dy, area.right + dx, area.bottom + dy), paint)
        }
    }
}
