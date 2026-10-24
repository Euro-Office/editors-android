package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.SlideInfo
import lib.editors.resources.R as Resources

/**
 * Presentations: a strip of slide thumbnails above the toolbar, or beside the slides on large
 * screens ([vertical]), with a button for a new slide at its end; a tap shows the slide, a long
 * press its menu: duplicate, move, hide, layout, transition, notes, delete. And the slideshow.
 */
internal class SlidesUi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    /** The slide at the given size in pixels. */
    private val render: suspend (page: Int, width: Int, height: Int) -> Bitmap?,
    private val slideAspect: () -> Float,
    /** Whether slides can be changed (the menu, new slides); viewing shows only the slides. */
    editable: Boolean,
    /** Thumbnails below each other (beside the slides) instead of next to each other. */
    private val vertical: Boolean = false,
    /** Pictures of the layouts to choose from. */
    private val previews: PreviewPicker? = null,
    /** Where the transition's settings open. */
    private val panels: PanelHost? = null
) {

    var editable: Boolean = editable
        set(value) {
            field = value
            addButton.visibility = if (value) View.VISIBLE else View.GONE
        }

    private val density = context.resources.displayMetrics.density
    private val thumbnailWidth = dp(if (vertical) 144 else 96)
    private var slides: List<SlideInfo> = emptyList()
    private var currentSlide = 0
    private val thumbnails = LinearLayout(context).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setPaddingRelative(dp(4), dp(4), dp(4), dp(4))
    }

    // A new slide after the current one, of the layout chosen
    private val addButton = ImageView(context).apply {
        setImageResource(Resources.drawable.ic_editor_slide_add)
        imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(Resources.color.editors_icon))
        scaleType = ImageView.ScaleType.CENTER
        setBackgroundResource(lib.editors.gbase.R.drawable.editor_tool_background)
        contentDescription = context.getString(Resources.string.editors_slides_new)
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(4), dp(4), dp(8), dp(4)) }
        visibility = if (editable) View.VISIBLE else View.GONE
        setOnClickListener { chooseLayout(forNewSlide = true) }
    }

    /** The strip; above the toolbar, or beside the slides when [vertical]. */
    val strip: ViewGroup = (if (vertical) ScrollView(context) else HorizontalScrollView(context)).apply {
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, background, true)
        setBackgroundColor(background.data)
        elevation = 2 * density
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(thumbnails)
            addView(addButton)
        })
        Accessibility.pane(this, context.getString(Resources.string.editors_slides))
    }

    /** After a command: the current slide, and thumbnails of changed slides. */
    fun update(state: EditorState) {
        val current = state.currentPage
        val countChanged = state.layoutChanged
        if (countChanged || state.dirtyPages.isNotEmpty() || slides.isEmpty()) {
            reload(if (countChanged || slides.isEmpty()) null else state.dirtyPages)
        }
        if (current >= 0 && current != currentSlide) {
            currentSlide = current
            highlight()
        }
    }

    // Slide information and thumbnails (null: all of them)
    private fun reload(changed: Set<Int>?) {
        val document = document() ?: return
        scope.launch {
            val list = withContext(EditorEngine.dispatcher) { runCatching { document.slides() }.getOrNull() } ?: return@launch
            val countChanged = list.size != slides.size
            slides = list
            // Views only for a new count: a menu may be open at one
            if (countChanged || thumbnails.childCount != list.size) {
                thumbnails.removeAllViews()
                list.indices.forEach { thumbnails.addView(thumbnailView(it)) }
            }
            highlight()
            val height = (thumbnailWidth / slideAspect()).toInt()
            for (index in list.indices) {
                if (!countChanged && changed != null && index !in changed) continue
                val bitmap = render(index, thumbnailWidth, height) ?: continue
                (thumbnails.getChildAt(index) as? FrameLayout)?.let { frame ->
                    (frame.getChildAt(0) as ImageView).setImageBitmap(bitmap)
                    frame.alpha = if (slides.getOrNull(index)?.hidden == true) 0.4f else 1f
                }
            }
        }
    }

    private fun thumbnailView(index: Int) = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(thumbnailWidth + dp(6), (thumbnailWidth / slideAspect()).toInt() + dp(6)).apply {
            if (vertical) bottomMargin = dp(4) else marginEnd = dp(4)
        }
        setPaddingRelative(dp(3), dp(3), dp(3), dp(3))
        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY })
        addView(TextView(context).apply {
            text = "%d".format(index + 1)
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPaddingRelative(dp(3), 0, dp(3), 0)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        contentDescription = context.getString(Resources.string.editors_slides_slide, index + 1)
        setOnClickListener { edit { gotoSlide(index) } }
        setOnLongClickListener {
            if (!editable) return@setOnLongClickListener false
            edit { gotoSlide(index) }
            showSlideMenu(this, index)
            true
        }
    }

    private fun highlight() {
        for (i in 0 until thumbnails.childCount) {
            thumbnails.getChildAt(i).isSelected = i == currentSlide
            thumbnails.getChildAt(i).background = if (i == currentSlide) GradientDrawable().apply {
                setStroke(dp(3), context.getColor(Resources.color.editors_selection_handle))
            } else null
        }
        thumbnails.getChildAt(currentSlide)?.let { child ->
            // Not if the strip was built anew meanwhile (another slide count)
            strip.post { if (child.parent === thumbnails) strip.requestChildFocus(thumbnails, child) }
        }
    }

    // ---------------------------------------------------------------------------------
    // Menu

    private fun showSlideMenu(anchor: View, index: Int) {
        val hidden = slides.getOrNull(index)?.hidden == true
        val actions = listOf<Pair<Int, () -> Unit>>(
            Resources.string.editors_slides_new to { chooseLayout(forNewSlide = true) },
            Resources.string.editors_slides_duplicate to { edit { duplicateSlide() } },
            Resources.string.editors_slides_move_left to { edit { moveSlide(index - 1) } },
            Resources.string.editors_slides_move_right to { edit { moveSlide(index + 1) } },
            (if (hidden) Resources.string.editors_slides_show else Resources.string.editors_slides_hide) to { edit { hideSlide(!hidden) } },
            Resources.string.editors_slides_layout to { chooseLayout(forNewSlide = false) },
            Resources.string.editors_slides_transition to { chooseTransition() },
            Resources.string.editors_slides_notes to { editNotes(index) },
            Resources.string.editors_slides_delete to { edit { deleteSlide() } }
        )
        PopupMenu(context, anchor).apply {
            actions.forEachIndexed { i, (label, _) -> menu.add(Menu.NONE, i, i, label) }
            setOnMenuItemClickListener { actions[it.itemId].second(); true }
        }.show()
    }

    fun chooseLayout(forNewSlide: Boolean, onBack: (() -> Unit)? = null) {
        val title = context.getString(if (forNewSlide) Resources.string.editors_slides_new else Resources.string.editors_slides_layout)
        if (previews != null) {
            previews.show(title, "layouts", columnWidthDp = 136, withNames = true, onBack = onBack) { layout ->
                val index = layout.id.toIntOrNull() ?: return@show
                edit { if (forNewSlide) addSlide(index) else setLayout(index) }
            }
            return
        }
        val document = document() ?: return
        scope.launch {
            val layouts = withContext(EditorEngine.dispatcher) { runCatching { document.layouts() }.getOrNull() } ?: return@launch
            AlertDialog.Builder(context)
                .setTitle(if (forNewSlide) Resources.string.editors_slides_new else Resources.string.editors_slides_layout)
                .setItems(layouts.map { it.name }.toTypedArray()) { _, position ->
                    val layout = layouts[position].index
                    edit { if (forNewSlide) addSlide(layout) else setLayout(layout) }
                }
                .show()
        }
    }

    private val transitionPanel by lazy { panels?.let { TransitionPanel(context, it, edit) { reload(null) } } }

    /** The current slide's transition; [onBack] to the panel it was opened from. */
    fun chooseTransition(onBack: (() -> Unit)? = null) {
        transitionPanel?.show(slides.getOrNull(currentSlide)?.transition, onBack)
    }

    private fun editNotes(index: Int) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = Gravity.TOP
            setText(slides.getOrNull(index)?.notes.orEmpty())
        }
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_slides_notes)
            .setView(FrameLayout(context).apply {
                setPaddingRelative(dp(24), dp(8), dp(24), 0)
                addView(input)
            })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                edit { setNotes(input.text.toString()) }
                reload(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------------------
    // Slideshow

    fun startSlideshow() {
        val document = document() ?: return
        scope.launch {
            val list = withContext(EditorEngine.dispatcher) { runCatching { document.slides() }.getOrNull() } ?: return@launch
            Slideshow(context, scope, list, render, slideAspect(), currentSlide).start()
        }
    }

    // ---------------------------------------------------------------------------------

    private fun dp(value: Int) = (value * density).toInt()
}
