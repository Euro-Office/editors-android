package lib.editors.gbase.ui.activities

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
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
     * from (a back button), [onDismiss] runs when it closes, however that happens. A bottom
     * sheet that isn't [modal] leaves the document usable (scrolling, tapping), takes at most
     * half the screen (the document shows what it formats above it) and has a close button
     * instead; the panels opened from it stay so.
     */
    fun show(title: String, content: View, onBack: (() -> Unit)? = null, modal: Boolean = true, onDismiss: () -> Unit): Panel {
        // Nested: in a bottom sheet, scrolling back up scrolls the panel before it moves the sheet
        val scroll = NestedScrollView(context).apply { addView(content) }
        val sheet = (current as? SheetPanel)?.takeIf { !isSide && it.sheet.isShowing }
        if (sheet != null) {
            // In the sheet that is open: no closing and opening again
            current = null
            sheet.onDismiss()
            return showSheet(sheet.sheet, sheet.frame, title, scroll, onBack, modal && sheet.modal, onDismiss)
        }
        current?.dismiss()
        val panel = if (isSide) showSide(title, scroll, onBack, onDismiss) else showSheet(null, null, title, scroll, onBack, modal, onDismiss)
        current = panel
        return panel
    }

    /** Options shown first under their own heading ("Recently used"), by their indices. */
    class Section(val title: String, val options: List<Int>)

    /**
     * [options] to choose one from, the [selected] one marked, in a panel under [title], after
     * [sections] of some of them; with a [searchHint], a field to find them, then [allTitle] the
     * heading of all of them. After a choice, [onBack] (the panel it was opened from) or none.
     */
    fun choose(
        title: String, options: List<String>, selected: Int, onBack: (() -> Unit)? = null,
        searchHint: String? = null, sections: List<Section> = emptyList(), allTitle: String? = null, onChoose: (Int) -> Unit
    ) {
        lateinit var panel: Panel
        fun row(index: Int) = optionRow(options[index], index == selected) {
            onChoose(index)
            if (onBack != null) onBack() else panel.dismiss()
        }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(0, 0, 0, dp(16))
        }
        val first = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (section in sections.filter { it.options.isNotEmpty() }) {
            first.addView(sectionHeading(section.title))
            section.options.forEach { first.addView(row(it)) }
        }
        if (first.childCount > 0 && allTitle != null) first.addView(sectionHeading(allTitle))
        val rows = options.indices.map { row(it) }
        if (searchHint != null) {
            list.addView(EditText(context).apply {
                hint = searchHint
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setCompoundDrawablesRelativeWithIntrinsicBounds(ContextCompat.getDrawable(context, Resources.drawable.ic_editor_search), null, null, null)
                compoundDrawablePadding = dp(8)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(text: Editable?) {
                        val query = text.toString().trim()
                        first.isVisible = query.isEmpty()
                        rows.forEachIndexed { index, view -> view.isVisible = options[index].contains(query, ignoreCase = true) }
                    }
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            })
        }
        list.addView(first)
        rows.forEach { list.addView(it) }
        panel = show(title, list, onBack) {}
        // The chosen one in view, if there is nothing before the list
        if (selected > 0 && first.childCount == 0) {
            list.post { (list.parent as? NestedScrollView)?.scrollTo(0, rows[selected].top - dp(48)) }
        }
    }

    private fun sectionHeading(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, Resources.color.editors_accent))
        setTypeface(typeface, Typeface.BOLD)
        textSize = 14f
        setPaddingRelative(dp(16), dp(16), dp(16), dp(4))
    }

    private fun optionRow(option: String, selected: Boolean, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPaddingRelative(dp(16), 0, dp(12), 0)
            setBackgroundResource(R.drawable.editor_tool_background)
            isSelected = selected
            addView(TextView(context).apply {
                text = option
                textSize = 16f
                if (selected) {
                    setTextColor(ContextCompat.getColor(context, Resources.color.editors_accent))
                    setTypeface(typeface, Typeface.BOLD)
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (selected) {
                addView(ImageView(context).apply {
                    setImageResource(Resources.drawable.ic_editor_check)
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
            }
            setOnClickListener { onClick() }
        }

    private fun showSheet(
        open: BottomSheetDialog?, openFrame: FrameLayout?, title: String, content: View, onBack: (() -> Unit)?, modal: Boolean,
        onDismiss: () -> Unit
    ): Panel {
        val sheet = open ?: BottomSheetDialog(context)
        val frame = openFrame ?: FrameLayout(context).also { sheet.setContentView(it) }
        lateinit var panel: SheetPanel
        frame.removeAllViews()
        frame.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header(title, onBack, if (modal) null else ({ panel.dismiss() })))
            addView(content)
        })
        setModal(sheet, modal)
        sheet.behavior.maxHeight = if (modal) -1 else context.resources.displayMetrics.heightPixels / 2
        if (!modal) {
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        panel = SheetPanel(sheet, frame, modal, onDismiss)
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

    // Not modal: no dimming, and touches beside the sheet go to the document (they would close it)
    private fun setModal(sheet: BottomSheetDialog, modal: Boolean) {
        val window = sheet.window ?: return
        if (modal) window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) else window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val outside = sheet.findViewById<View>(com.google.android.material.R.id.touch_outside) ?: return
        val activity = context as? Activity
        if (modal || activity == null) {
            outside.setOnTouchListener(null)
            return
        }
        outside.setOnTouchListener { _, event ->
            // The activity's window has the screen's coordinates
            val copy = MotionEvent.obtain(event).apply { setLocation(event.rawX, event.rawY) }
            activity.dispatchTouchEvent(copy)
            copy.recycle()
            true
        }
    }

    private inner class SheetPanel(val sheet: BottomSheetDialog, val frame: FrameLayout, val modal: Boolean, val onDismiss: () -> Unit) : Panel {
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
