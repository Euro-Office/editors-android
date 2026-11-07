package lib.editors.gbase.ui.activities

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import lib.editors.gbase.R
import lib.editors.gbase.ui.commands.composePanel
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

    /**
     * Where panels open as pages instead of in a sheet of their own: the command sheet (opened
     * for them if it is closed, and closed with them then).
     */
    interface Pages {
        val isOpen: Boolean

        /** Shows [content] under [title] in place of the commands; [onBack] the page before, or the commands. */
        fun showPage(title: String, content: View, onBack: () -> Unit)

        /** Back to the commands. */
        fun closePage()
    }

    var pages: Pages? = null

    /** Closes the panel shown, if any. */
    fun dismiss() {
        current?.dismiss()
    }

    private var current: Panel? = null

    // Where the panels shown since one opened were scrolled to, by their titles: a panel shown
    // again (built anew, or back from one opened from it) keeps its place
    private val scrolled = mutableMapOf<String, Int>()
    private var shownScroll: Pair<String, NestedScrollView>? = null

    private val isSide get() = sidePane != null && context.resources.getBoolean(R.bool.editor_side_panels)
    private val density = context.resources.displayMetrics.density

    /** The width panels have, in pixels. */
    val width: Int
        get() = if (isSide) context.resources.getDimensionPixelSize(R.dimen.editor_side_pane_width) else context.resources.displayMetrics.widthPixels

    /**
     * Shows [content] (scrolled) under [title]; [onBack] shows the panel this one was opened
     * from (a back button), [onDismiss] runs when it closes, however that happens. A bottom
     * sheet that isn't [modal] leaves the document usable (scrolling, tapping), takes half the
     * screen (the document shows what it formats above it) and has a close button instead; the
     * panels opened from it stay so.
     */
    fun show(title: String, content: View, onBack: (() -> Unit)? = null, modal: Boolean = true, onDismiss: () -> Unit): Panel {
        // Nested: in a bottom sheet, scrolling back up scrolls the panel before it moves the sheet
        val scroll = NestedScrollView(context).apply { addView(content) }
        shownScroll?.let { (shownTitle, shown) -> scrolled[shownTitle] = shown.scrollY }
        shownScroll = title to scroll
        scrolled[title]?.let { y -> scroll.post { scroll.scrollTo(0, y) } }
        val pages = pages?.takeIf { !isSide }
        if (pages != null) return showPage(pages, title, scroll, onBack, onDismiss)
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
        val choose = { index: Int ->
            onChoose(index)
            if (onBack != null) onBack() else panel.dismiss()
        }
        val shownSections = sections.filter { it.options.isNotEmpty() }
        val content = composePanel(context) {
            ChoiceList(options, selected, searchHint, shownSections, allTitle, choose)
        }
        panel = show(title, content, onBack) {}
    }

    // The options as rows with radio buttons, the chosen one scrolled to (if nothing is before them)
    @Composable
    private fun ChoiceList(options: List<String>, selected: Int, searchHint: String?, sections: List<Section>, allTitle: String?, onChoose: (Int) -> Unit) {
        var query by remember { mutableStateOf("") }
        val view = LocalView.current
        var scrolled by remember { mutableStateOf(false) }
        Column(Modifier.padding(bottom = 16.dp)) {
            if (searchHint != null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(searchHint) },
                    leadingIcon = { Icon(painterResource(Resources.drawable.ic_editor_search), null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            val searching = query.isNotBlank()
            if (!searching) {
                for (section in sections) {
                    Heading(section.title)
                    section.options.forEach { Option(options[it], it == selected) { onChoose(it) } }
                }
                if (sections.isNotEmpty() && allTitle != null) Heading(allTitle)
            }
            options.forEachIndexed { index, option ->
                if (searching && !option.contains(query.trim(), ignoreCase = true)) return@forEachIndexed
                val chosen = index == selected
                Option(option, chosen, Modifier.onGloballyPositioned { coordinates ->
                    // The chosen one in view once, a row above it showing
                    if (chosen && !scrolled && sections.isEmpty() && searchHint == null) {
                        scrolled = true
                        val top = coordinates.positionInParent().y.toInt()
                        (view.parent as? NestedScrollView)?.post { (view.parent as? NestedScrollView)?.scrollTo(0, top - dp(56)) }
                    }
                }) { onChoose(index) }
            }
        }
    }

    @Composable
    private fun Heading(text: String) {
        Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }

    @Composable
    private fun Option(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(text) },
            leadingContent = { RadioButton(selected = selected, onClick = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        )
    }

    private fun showSheet(
        open: BottomSheetDialog?, openFrame: FrameLayout?, title: String, content: View, onBack: (() -> Unit)?, modal: Boolean,
        onDismiss: () -> Unit
    ): Panel {
        val sheet = open ?: BottomSheetDialog(context)
        val frame = openFrame ?: FrameLayout(context).also { sheet.setContentView(it) }
        lateinit var panel: SheetPanel
        frame.removeAllViews()
        // Not modal: half the screen whatever the content, so closing a section doesn't move it
        val half = context.resources.displayMetrics.heightPixels / 2
        frame.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header(title, onBack, if (modal) null else ({ panel.dismiss() })))
            if (modal) addView(content) else addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (modal) ViewGroup.LayoutParams.WRAP_CONTENT else half))
        setModal(sheet, modal)
        sheet.behavior.maxHeight = if (modal) -1 else half
        if (!modal) {
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        panel = SheetPanel(sheet, frame, modal, onDismiss)
        sheet.setOnDismissListener {
            val shown = current as? SheetPanel
            if (shown?.sheet === sheet) {
                current = null
                forgetScrolling()
                shown.onDismiss()
            }
        }
        if (open == null) sheet.show()
        current = panel
        return panel
    }

    // In the command sheet: a page in place of its commands, back to the panel before or to them
    private fun showPage(pages: Pages, title: String, content: View, onBack: (() -> Unit)?, onDismiss: () -> Unit): Panel {
        (current as? PagePanel)?.let { shown ->
            current = null
            shown.onDismiss()
        }
        current?.dismiss()
        lateinit var panel: PagePanel
        panel = PagePanel(pages, onDismiss)
        pages.showPage(title, content, onBack ?: { panel.dismiss() })
        current = panel
        return panel
    }

    private inner class PagePanel(val pages: Pages, val onDismiss: () -> Unit) : Panel {
        override fun dismiss() {
            if (current !== this) return
            current = null
            forgetScrolling()
            pages.closePage()
            onDismiss()
        }
    }

    private fun forgetScrolling() {
        scrolled.clear()
        shownScroll = null
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
                forgetScrolling()
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
