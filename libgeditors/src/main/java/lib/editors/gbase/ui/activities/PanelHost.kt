package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * Where panels (formatting, comments) open: a bottom sheet on phones; on large screens a pane
 * beside the document ([sidePane]), so the document stays in view and can be edited while the
 * panel is open. One panel at a time: a new one takes the place of the one before (in the same
 * sheet), and a panel opened from another one has a back button to it.
 */
internal class PanelHost(private val context: Context, private val sidePane: ViewGroup?) {

    /** A panel being shown. */
    interface Panel {
        fun dismiss()
    }

    private var current: Panel? = null

    private val isSide get() = sidePane != null && context.resources.getBoolean(R.bool.editor_side_panels)
    private val density = context.resources.displayMetrics.density

    /** The width panels have, in pixels. */
    val width: Int
        get() = if (isSide) context.resources.getDimensionPixelSize(R.dimen.editor_side_pane_width) else context.resources.displayMetrics.widthPixels

    /**
     * Shows [content] (scrolled) under [title]; [onBack] shows the panel this one was opened
     * from (a back button), [onDismiss] runs when it closes, however that happens.
     */
    fun show(title: String, content: View, onBack: (() -> Unit)? = null, onDismiss: () -> Unit): Panel {
        // Nested: in a bottom sheet, scrolling back up scrolls the panel before it moves the sheet
        val scroll = NestedScrollView(context).apply { addView(content) }
        val sheet = (current as? SheetPanel)?.takeIf { !isSide && it.sheet.isShowing }
        if (sheet != null) {
            // In the sheet that is open: no closing and opening again
            current = null
            sheet.onDismiss()
            return showSheet(sheet.sheet, sheet.frame, title, scroll, onBack, onDismiss)
        }
        current?.dismiss()
        val panel = if (isSide) showSide(title, scroll, onBack, onDismiss) else showSheet(null, null, title, scroll, onBack, onDismiss)
        current = panel
        return panel
    }

    /**
     * [options] to choose one from, the [selected] one marked, in a panel under [title]; after
     * a choice, [onBack] (the panel it was opened from) or none.
     */
    fun choose(title: String, options: List<String>, selected: Int, onBack: (() -> Unit)? = null, onChoose: (Int) -> Unit) {
        lateinit var panel: Panel
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(0, 0, 0, dp(16))
        }
        options.forEachIndexed { index, option ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPaddingRelative(dp(16), 0, dp(12), 0)
                setBackgroundResource(R.drawable.editor_tool_background)
                isSelected = index == selected
                addView(TextView(context).apply {
                    text = option
                    textSize = 16f
                    if (index == selected) {
                        setTextColor(ContextCompat.getColor(context, Resources.color.editors_accent))
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (index == selected) {
                    addView(ImageView(context).apply {
                        setImageResource(Resources.drawable.ic_editor_check)
                        contentDescription = null
                    }, LinearLayout.LayoutParams(dp(24), dp(24)))
                }
                setOnClickListener {
                    onChoose(index)
                    if (onBack != null) onBack() else panel.dismiss()
                }
            })
        }
        panel = show(title, list, onBack) {}
        // The chosen one in view
        if (selected > 0) list.post { (list.parent as? NestedScrollView)?.scrollTo(0, list.getChildAt(selected)?.top?.minus(dp(48)) ?: 0) }
    }

    private fun showSheet(
        open: BottomSheetDialog?, openFrame: FrameLayout?, title: String, content: View, onBack: (() -> Unit)?, onDismiss: () -> Unit
    ): Panel {
        val sheet = open ?: BottomSheetDialog(context)
        val frame = openFrame ?: FrameLayout(context).also { sheet.setContentView(it) }
        frame.removeAllViews()
        frame.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header(title, onBack, null))
            addView(content)
        })
        val panel = SheetPanel(sheet, frame, onDismiss)
        sheet.setOnDismissListener {
            val shown = current as? SheetPanel
            if (shown?.sheet === sheet) {
                current = null
                shown.onDismiss()
            }
        }
        if (open == null) sheet.show()
        current = panel
        return panel
    }

    private inner class SheetPanel(val sheet: BottomSheetDialog, val frame: FrameLayout, val onDismiss: () -> Unit) : Panel {
        // A panel that another took the place of has nothing to close
        override fun dismiss() {
            if (current === this) sheet.dismiss()
        }
    }

    private fun showSide(title: String, content: View, onBack: (() -> Unit)?, onDismiss: () -> Unit): Panel {
        val pane = sidePane!!
        lateinit var panel: Panel
        panel = object : Panel {
            override fun dismiss() {
                if (current !== this) return
                current = null
                pane.removeAllViews()
                pane.isVisible = false
                onDismiss()
            }
        }
        pane.removeAllViews()
        pane.addView(header(title, onBack) { panel.dismiss() })
        pane.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Accessibility.pane(pane, title)
        pane.isVisible = true
        return panel
    }

    // The title, with a back button to the panel before and a close button for the side pane
    private fun header(title: String, onBack: (() -> Unit)?, onClose: (() -> Unit)?) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPaddingRelative(dp(if (onBack != null) 4 else 16), dp(8), 0, 0)
        if (onBack != null) addView(headerButton(Resources.drawable.ic_editor_back, Resources.string.editors_back, onBack))
        addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (onClose != null) addView(headerButton(Resources.drawable.ic_editor_close, Resources.string.editors_close, onClose))
    }

    private fun headerButton(icon: Int, description: Int, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        contentDescription = context.getString(description)
        setBackgroundResource(R.drawable.editor_tool_background)
        setPaddingRelative(dp(12), dp(12), dp(12), dp(12))
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * density).toInt()
}
