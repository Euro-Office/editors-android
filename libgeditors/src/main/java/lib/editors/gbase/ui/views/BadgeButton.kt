package lib.editors.gbase.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import lib.editors.resources.R as Resources

/** A toolbar button with a count at its top end (the comments'); none while [count] is 0. */
class BadgeButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.imageButtonStyle
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    var count = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, Resources.color.editors_accent) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, Resources.color.editors_on_accent)
        textSize = 10 * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (count <= 0) return
        val label = if (count > MAX_COUNT) "$MAX_COUNT+" else count.toString()
        val radius = 8 * density
        val halfWidth = maxOf(radius, text.measureText(label) / 2 + 4 * density)
        val centerY = 4 * density + radius
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val centerX = if (isRtl) 4 * density + halfWidth else width - 4 * density - halfWidth
        canvas.drawRoundRect(centerX - halfWidth, centerY - radius, centerX + halfWidth, centerY + radius, radius, radius, circle)
        canvas.drawText(label, centerX, centerY - (text.ascent() + text.descent()) / 2, text)
    }

    private companion object {
        const val MAX_COUNT = 99
    }
}
