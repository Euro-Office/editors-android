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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
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
 * screens ([vertical]); a tap shows the slide, a long press its menu: duplicate, move, hide,
 * layout, transition, notes, delete. And the slideshow.
 */
internal class SlidesUi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    /** The slide at the given size in pixels. */
    private val render: suspend (page: Int, width: Int, height: Int) -> Bitmap?,
    private val slideAspect: () -> Float,
    /** Whether slides can be changed (the menu); viewing shows only the slides. */
    var editable: Boolean,
    /** Thumbnails below each other (beside the slides) instead of next to each other. */
    private val vertical: Boolean = false
) {

    private val density = context.resources.displayMetrics.density
    private val thumbnailWidth = dp(if (vertical) 144 else 96)
    private var slides: List<SlideInfo> = emptyList()
    private var currentSlide = 0
    private val thumbnails = LinearLayout(context).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setPaddingRelative(dp(4), dp(4), dp(4), dp(4))
    }

    /** The strip; above the toolbar, or beside the slides when [vertical]. */
    val strip: ViewGroup = (if (vertical) ScrollView(context) else HorizontalScrollView(context)).apply {
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, background, true)
        setBackgroundColor(background.data)
        elevation = 2 * density
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        addView(thumbnails)
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

    private fun chooseLayout(forNewSlide: Boolean) {
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

    private fun chooseTransition() {
        val current = slides.getOrNull(currentSlide)?.transition
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(24), dp(8), dp(24), 0)
        }
        val types = choices(content, Resources.string.editors_slides_effect, TRANSITIONS.map { context.getString(it.first) },
            TRANSITIONS.indexOfFirst { it.second == (current?.type ?: 0) }, vertical = true)
        val directions = choices(content, Resources.string.editors_slides_direction, DIRECTIONS.map { context.getString(it.first) },
            DIRECTIONS.indexOfFirst { it.second == current?.option })
        val durations = choices(content, Resources.string.editors_slides_duration, DURATIONS.map { "%.1f s".format(it / 1000f) },
            DURATIONS.indexOf(current?.durationMs ?: 0))
        val all = CheckBox(context).apply { setText(Resources.string.editors_slides_apply_all) }
        content.addView(all)
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_slides_transition)
            .setView(android.widget.ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val type = TRANSITIONS.getOrNull(types())?.second ?: 0
                // Fade, split and zoom have their own options (smoothly, vertical out, in); the others take the direction
                val option = when (type) {
                    1 -> 0
                    4 -> 11
                    8 -> 17
                    else -> DIRECTIONS.getOrNull(directions())?.second ?: 2
                }
                val duration = DURATIONS.getOrNull(durations()) ?: 1000
                edit { setTransition(type, option, duration, all.isChecked) }
                reload(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun choices(parent: LinearLayout, title: Int, labels: List<String>, selected: Int, vertical: Boolean = false): () -> Int {
        parent.addView(TextView(context).apply {
            setText(title)
            setPaddingRelative(0, dp(12), 0, dp(4))
        })
        val group = RadioGroup(context).apply { orientation = if (vertical) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL }
        labels.forEachIndexed { index, label ->
            group.addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = label
                isChecked = index == selected
            })
        }
        parent.addView(if (vertical) group else HorizontalScrollView(context).apply { addView(group) })
        return { (0 until group.childCount).firstOrNull { (group.getChildAt(it) as RadioButton).isChecked } ?: -1 }
    }

    private companion object {
        // Asc.c_oAscSlideTransitionTypes
        val TRANSITIONS = listOf(
            Resources.string.editors_slides_effect_none to 0,
            Resources.string.editors_slides_effect_fade to 1,
            Resources.string.editors_slides_effect_push to 2,
            Resources.string.editors_slides_effect_wipe to 3,
            Resources.string.editors_slides_effect_split to 4,
            Resources.string.editors_slides_effect_uncover to 5,
            Resources.string.editors_slides_effect_cover to 6,
            Resources.string.editors_slides_effect_zoom to 8
        )
        // Asc.c_oAscSlideTransitionParams
        val DIRECTIONS = listOf(
            Resources.string.editors_slides_from_left to 2,
            Resources.string.editors_slides_from_top to 3,
            Resources.string.editors_slides_from_right to 4,
            Resources.string.editors_slides_from_bottom to 5
        )
        val DURATIONS = listOf(500, 1000, 2000)
    }
}
