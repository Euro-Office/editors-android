package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import lib.editors.base.engine.PageSize
import lib.editors.resources.R as Resources

/**
 * Page thumbnails of a PDF, XPS or DjVu file in a strip above the toolbar: a tap shows the page.
 * Thumbnails are rendered when they are first scrolled into view.
 */
internal class PageStrip(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pages: List<PageSize>,
    /** The page at the given size in pixels. */
    private val render: suspend (page: Int, width: Int, height: Int) -> Bitmap?,
    private val onPage: (Int) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val thumbnailHeight = dp(88)
    private val rendered = HashSet<Int>()
    private var renderJob: Job? = null
    private var current = -1
    private val thumbnails = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    val view: HorizontalScrollView = HorizontalScrollView(context).apply {
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, background, true)
        setBackgroundColor(background.data)
        elevation = 2 * density
        isHorizontalScrollBarEnabled = false
        isVisible = false
        addView(thumbnails)
        setOnScrollChangeListener { _, _, _, _, _ -> renderVisible() }
    }

    init {
        pages.forEachIndexed { index, size ->
            val width = (thumbnailHeight * size.widthMm / size.heightMm).toInt().coerceAtLeast(dp(24))
            thumbnails.addView(FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(width + dp(6), thumbnailHeight + dp(6)).apply { marginEnd = dp(4) }
                setPadding(dp(3), dp(3), dp(3), dp(3))
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    setBackgroundColor(Color.WHITE)
                })
                addView(TextView(context).apply {
                    text = (index + 1).toString()
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0x88000000.toInt())
                    setPadding(dp(3), 0, dp(3), 0)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
                contentDescription = context.getString(Resources.string.editors_pages_page, index + 1)
                setOnClickListener { onPage(index) }
            })
        }
    }

    fun toggle() {
        view.isVisible = !view.isVisible
        if (view.isVisible) view.post { renderVisible() }
    }

    /** Marks the page shown in the document. */
    fun setCurrent(page: Int) {
        if (page == current) return
        thumbnails.getChildAt(current)?.background = null
        current = page
        val child = thumbnails.getChildAt(page) ?: return
        child.background = GradientDrawable().apply { setStroke(dp(3), context.getColor(Resources.color.editors_selection_handle)) }
        if (view.isVisible) view.post { view.smoothScrollTo((child.left - view.width / 2 + child.width / 2).coerceAtLeast(0), 0) }
    }

    private fun renderVisible() {
        if (renderJob?.isActive == true) return
        renderJob = scope.launch {
            while (true) {
                val left = view.scrollX
                val right = left + view.width
                val next = (0 until thumbnails.childCount).firstOrNull { index ->
                    val child = thumbnails.getChildAt(index)
                    index !in rendered && child.right >= left && child.left <= right
                } ?: break
                rendered += next
                val image = (thumbnails.getChildAt(next) as FrameLayout).getChildAt(0) as ImageView
                val width = image.width.takeIf { it > 0 } ?: break
                render(next, width, thumbnailHeight)?.let { image.setImageBitmap(it) }
            }
        }
    }

    private fun dp(value: Int) = (value * density).toInt()
}
