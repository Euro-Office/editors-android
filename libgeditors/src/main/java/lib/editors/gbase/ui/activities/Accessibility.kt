package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ToggleButton
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import lib.editors.resources.R as Resources

/** Helpers for screen readers (TalkBack). */
internal object Accessibility {

    /** The view is read as a switch that is on while it is selected (bold, alignment, ...). */
    fun toggle(view: View) {
        ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = ToggleButton::class.java.name
                info.isCheckable = true
                info.isChecked = host.isSelected
                info.isSelected = false
            }
        })
    }

    /** A bar that appears over the document is announced with its [title] when it appears. */
    fun pane(view: View, title: String) {
        ViewCompat.setAccessibilityPaneTitle(view, title)
    }

    /** Changes of the text are read out (search results, the state of a form). */
    fun live(view: View) {
        view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /**
     * A name for a color given as "RRGGBB" ("" for none): its hue, made lighter or darker, such
     * as "Dark red" or "Light yellow".
     */
    fun colorName(context: Context, hex: String): String {
        if (hex.isEmpty()) return context.getString(Resources.string.editors_color_none)
        val color = runCatching { Color.parseColor("#$hex") }.getOrNull() ?: return hex
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val (hue, saturation, value) = hsv.toList()
        val base = when {
            value < 0.15f -> return context.getString(Resources.string.editors_color_black)
            saturation < 0.12f && value > 0.93f -> return context.getString(Resources.string.editors_color_white)
            saturation < 0.12f -> Resources.string.editors_color_gray
            hue < 15 || hue >= 340 -> Resources.string.editors_color_red
            hue < 40 -> Resources.string.editors_color_orange
            hue < 70 -> Resources.string.editors_color_yellow
            hue < 165 -> Resources.string.editors_color_green
            hue < 195 -> Resources.string.editors_color_turquoise
            hue < 255 -> Resources.string.editors_color_blue
            hue < 290 -> Resources.string.editors_color_purple
            else -> Resources.string.editors_color_pink
        }
        val name = context.getString(base)
        val lightness = (2 - saturation) * value / 2
        return when {
            lightness < 0.3f -> context.getString(Resources.string.editors_color_dark, name)
            lightness > 0.8f -> context.getString(Resources.string.editors_color_light, name)
            else -> name
        }
    }
}
