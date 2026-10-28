package lib.editors.gbase.ui.activities

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * Pictures to choose one from, in groups (chart types, shapes, SmartArt; see [InsertCatalog]), in
 * a panel (see [PanelHost]). Their names are read by screen readers and shown on a long press.
 */
internal class IconPicker(private val context: Context, private val panels: PanelHost) {

    private val density = context.resources.displayMetrics.density

    /**
     * Shows [groups] under [title], [selected] highlighted; [onPick] gets the chosen one's value.
     * [pictures]: the icons are pictures in their own colors (SmartArt), shown larger. After a
     * choice, back to the panel it was opened from ([onBack]), if any.
     */
    fun <T> show(
        title: Int, groups: List<InsertCatalog.Group<T>>, selected: T? = null, pictures: Boolean = false,
        onBack: (() -> Unit)? = null, onPick: (T) -> Unit
    ) {
        lateinit var panel: PanelHost.Panel
        val cell = dp(if (pictures) 96 else 64)
        val columns = ((panels.width - dp(16)) / cell).coerceAtLeast(3)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(8), 0, dp(8), dp(16))
        }
        val tint = if (pictures) null else ColorStateList.valueOf(ContextCompat.getColor(context, Resources.color.editors_icon))
        for (group in groups) {
            content.addView(TextView(context).apply {
                setText(group.label)
                setTextColor(ContextCompat.getColor(context, Resources.color.editors_accent))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 14f
                setPaddingRelative(dp(8), dp(16), dp(8), dp(4))
            })
            val grid = GridLayout(context).apply { columnCount = columns }
            for (item in group.items) {
                grid.addView(ImageView(context).apply {
                    setImageResource(item.icon)
                    imageTintList = tint
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val padding = dp(if (pictures) 8 else 12)
                    setPadding(padding, padding, padding, padding)
                    setBackgroundResource(R.drawable.editor_tool_background)
                    isSelected = item.value == selected
                    val label = context.getString(item.label)
                    contentDescription = label
                    TooltipCompat.setTooltipText(this, label)
                    setOnClickListener {
                        if (onBack == null) panel.dismiss()
                        onPick(item.value)
                        onBack?.invoke()
                    }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = cell
                        height = cell
                    }
                })
            }
            content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel = panels.show(context.getString(title), content, onBack) {}
    }

    private fun dp(value: Int) = (value * density).toInt()
}
