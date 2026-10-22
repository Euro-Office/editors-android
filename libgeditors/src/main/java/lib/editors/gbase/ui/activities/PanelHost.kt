package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * Where panels (formatting, comments) open: a bottom sheet on phones; on large screens a pane
 * beside the document ([sidePane]), so the document stays in view and can be edited while the
 * panel is open. One panel at a time: a new one closes the one before.
 */
internal class PanelHost(private val context: Context, private val sidePane: ViewGroup?) {

    /** A panel being shown. */
    interface Panel {
        fun dismiss()
    }

    private var current: Panel? = null

    private val isSide get() = sidePane != null && context.resources.getBoolean(R.bool.editor_side_panels)

    /** The width panels have, in pixels. */
    val width: Int
        get() = if (isSide) context.resources.getDimensionPixelSize(R.dimen.editor_side_pane_width) else context.resources.displayMetrics.widthPixels

    /** Shows [content] (scrolled) under [title]; [onDismiss] runs when it closes, however that happens. */
    fun show(title: String, content: View, onDismiss: () -> Unit): Panel {
        current?.dismiss()
        val scroll = ScrollView(context).apply { addView(content) }
        val panel = if (isSide) showSide(title, scroll, onDismiss) else showSheet(title, scroll, onDismiss)
        current = panel
        return panel
    }

    private fun showSheet(title: String, content: View, onDismiss: () -> Unit): Panel {
        val sheet = BottomSheetDialog(context)
        sheet.setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header(title, null))
            addView(content)
        })
        sheet.setOnDismissListener {
            if (current is SheetPanel && (current as SheetPanel).sheet === sheet) current = null
            onDismiss()
        }
        sheet.show()
        return SheetPanel(sheet)
    }

    private class SheetPanel(val sheet: BottomSheetDialog) : Panel {
        override fun dismiss() = sheet.dismiss()
    }

    private fun showSide(title: String, content: View, onDismiss: () -> Unit): Panel {
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
        pane.addView(header(title) { panel.dismiss() })
        pane.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Accessibility.pane(pane, title)
        pane.isVisible = true
        return panel
    }

    // The title, and a close button for the side pane
    private fun header(title: String, onClose: (() -> Unit)?) = LinearLayout(context).apply {
        val density = context.resources.displayMetrics.density
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (48 * density).toInt()
        setPaddingRelative((16 * density).toInt(), (8 * density).toInt(), 0, 0)
        addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (onClose != null) {
            addView(ImageButton(context).apply {
                setImageResource(Resources.drawable.ic_editor_close)
                contentDescription = context.getString(Resources.string.editors_close)
                setBackgroundResource(R.drawable.editor_tool_background)
                val padding = (12 * density).toInt()
                setPaddingRelative(padding, padding, padding, padding)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                setOnClickListener { onClose() }
            })
        }
    }
}
