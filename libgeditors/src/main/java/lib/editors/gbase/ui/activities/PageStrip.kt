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
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import lib.editors.base.engine.PageSize
import lib.editors.resources.R as Resources

/**
 * Page thumbnails of a PDF, XPS or DjVu file in a strip above the toolbar, or beside the pages
 * on large screens ([vertical]): a tap shows the page. Thumbnails are rendered when they are
 * first scrolled into view.
 */
internal class PageStrip(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pages: List<PageSize>,
    /** The page at the given size in pixels. */
    private val render: suspend (page: Int, width: Int, height: Int) -> Bitmap?,
    private val onPage: (Int) -> Unit,
    private val vertical: Boolean = false
) {

    private val density = context.resources.displayMetrics.density
    private val thumbnailHeight = dp(88)
    private val rendered = HashSet<Int>()
    private var renderJob: Job? = null
    private var current = -1
    private val thumbnailWidth = dp(120)
    private val thumbnails = LinearLayout(context).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setPaddingRelative(dp(4), dp(4), dp(4), dp(4))
    }

    val view: FrameLayout = (if (vertical) ScrollView(context) else HorizontalScrollView(context)).apply {
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, background, true)
        setBackgroundColor(background.data)
        elevation = 2 * density
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        isVisible = false
        addView(thumbnails)
        Accessibility.pane(this, context.getString(Resources.string.editors_pages))
        setOnScrollChangeListener { _, _, _, _, _ -> renderVisible() }
    }

    init {
        pages.forEachIndexed { index, size ->
            // Next to each other: all as high; below each other: all as wide
            val width = if (vertical) thumbnailWidth else (thumbnailHeight * size.widthMm / size.heightMm).toInt().coerceAtLeast(dp(24))
            val height = if (vertical) (thumbnailWidth * size.heightMm / size.widthMm).toInt() else thumbnailHeight
            thumbnails.addView(FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(width + dp(6), height + dp(6)).apply {
                    if (vertical) bottomMargin = dp(4) else marginEnd = dp(4)
                }
                setPaddingRelative(dp(3), dp(3), dp(3), dp(3))
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    setBackgroundColor(Color.WHITE)
                })
                addView(TextView(context).apply {
                    text = (index + 1).toString()
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0x88000000.toInt())
                    setPaddingRelative(dp(3), 0, dp(3), 0)
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
        thumbnails.getChildAt(current)?.apply {
            background = null
            isSelected = false
        }
        current = page
        val child = thumbnails.getChildAt(page) ?: return
        child.isSelected = true
        child.background = GradientDrawable().apply { setStroke(dp(3), context.getColor(Resources.color.editors_selection_handle)) }
        if (view.isVisible) view.post {
            if (vertical) {
                (view as ScrollView).smoothScrollTo(0, (child.top - view.height / 2 + child.height / 2).coerceAtLeast(0))
            } else {
                (view as HorizontalScrollView).smoothScrollTo((child.left - view.width / 2 + child.width / 2).coerceAtLeast(0), 0)
            }
        }
    }

    private fun renderVisible() {
        if (renderJob?.isActive == true) return
        renderJob = scope.launch {
            while (true) {
                val start = if (vertical) view.scrollY else view.scrollX
                val end = start + if (vertical) view.height else view.width
                val next = (0 until thumbnails.childCount).firstOrNull { index ->
                    val child = thumbnails.getChildAt(index)
                    val childStart = if (vertical) child.top else child.left
                    val childEnd = if (vertical) child.bottom else child.right
                    index !in rendered && childEnd >= start && childStart <= end
                } ?: break
                rendered += next
                val image = (thumbnails.getChildAt(next) as FrameLayout).getChildAt(0) as ImageView
                val width = image.width.takeIf { it > 0 } ?: break
                render(next, width, image.height)?.let { image.setImageBitmap(it) }
            }
        }
    }

    private fun dp(value: Int) = (value * density).toInt()
}
