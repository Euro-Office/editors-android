package lib.editors.gbase.ui.activities

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.SlideTransition
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * The transition to the current slide, as web-apps sets it: the effect (its icons from
 * web-apps) and its option, the duration, and how the slideshow goes on to the next slide (on a
 * tap, after a delay); for this slide or all of them.
 */
internal class TransitionDialog(
    private val context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val onChanged: () -> Unit
) {

    private val density = context.resources.displayMetrics.density

    fun show(current: SlideTransition?) {
        var type = EFFECTS.firstOrNull { it.type == current?.type }?.type ?: 0
        var option = current?.option ?: 0
        var durationMs = current?.durationMs?.takeIf { it > 0 } ?: DEFAULT_DURATION_MS
        var delayMs = current?.afterMs ?: 0

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(24), dp(8), dp(24), 0)
        }
        // Effects: an icon with its name each, the chosen one marked
        content.addView(caption(Resources.string.editors_slides_effect))
        val effects = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(effects)
        })
        val optionCaption = caption(Resources.string.editors_slides_option).also { content.addView(it) }
        val options = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val optionRow = HorizontalScrollView(context).apply { addView(options) }.also { content.addView(it) }

        fun showOptions() {
            val choices = OPTIONS[type].orEmpty()
            if (choices.none { it.second == option }) option = choices.firstOrNull()?.second ?: 0
            options.removeAllViews()
            for ((label, value) in choices) {
                options.addView(RadioButton(context).apply {
                    id = android.view.View.generateViewId()
                    setText(label)
                    isChecked = value == option
                    setOnClickListener { option = value }
                })
            }
            optionCaption.isVisible = choices.isNotEmpty()
            optionRow.isVisible = choices.isNotEmpty()
        }
        val effectViews = EFFECTS.map { effect ->
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setBackgroundResource(R.drawable.editor_tool_background)
                Accessibility.toggle(this)
                contentDescription = context.getString(effect.label)
                TooltipCompat.setTooltipText(this, contentDescription)
                addView(ImageView(context).apply { setImageResource(effect.icon) }, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(TextView(context).apply {
                    setText(effect.label)
                    textSize = 12f
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            }.also { effects.addView(it, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }
        fun markEffect() = effectViews.forEachIndexed { i, view -> view.isSelected = EFFECTS[i].type == type }
        effectViews.forEachIndexed { i, view ->
            view.setOnClickListener {
                type = EFFECTS[i].type
                markEffect()
                showOptions()
            }
        }
        markEffect()
        showOptions()

        content.addView(caption(Resources.string.editors_slides_duration))
        content.addView(stepper(Resources.string.editors_slides_duration, { seconds(durationMs) }) { up ->
            durationMs = step(durationMs, DURATION_STEP_MS, up).coerceIn(DURATION_STEP_MS, MAX_MS)
        })

        val onTap = CheckBox(context).apply {
            setText(Resources.string.editors_slides_start_on_tap)
            isChecked = current?.onClick != false
        }
        content.addView(onTap)
        val delay = CheckBox(context).apply {
            setText(Resources.string.editors_slides_delay)
            isChecked = current?.advanceAfter == true
        }
        content.addView(delay)
        val delayRow = stepper(Resources.string.editors_slides_delay_after, { seconds(delayMs) }) { up ->
            delayMs = step(delayMs, DELAY_STEP_MS, up).coerceIn(0, MAX_MS)
        }
        content.addView(delayRow)
        delayRow.isVisible = delay.isChecked
        delay.setOnCheckedChangeListener { _, checked -> delayRow.isVisible = checked }

        val all = CheckBox(context).apply { setText(Resources.string.editors_slides_apply_all) }
        content.addView(all)

        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_slides_transition)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                edit {
                    setTransition(type, option, durationMs, all.isChecked)
                    setSlideAdvance(onTap.isChecked, delay.isChecked, delayMs, all.isChecked)
                }
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun caption(text: Int) = TextView(context).apply {
        setText(text)
        setPaddingRelative(0, dp(12), 0, dp(4))
    }

    // A value between buttons that lower and raise it ([change] with up), named [name] to TalkBack
    private fun stepper(name: Int, value: () -> String, change: (up: Boolean) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val label = context.getString(name)
        val text = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            minWidth = dp(72)
        }
        fun update() {
            text.text = value()
            text.contentDescription = label + " " + text.text
        }
        fun button(sign: String, up: Boolean, description: Int) = TextView(context).apply {
            this.text = sign
            gravity = Gravity.CENTER
            textSize = 18f
            minWidth = dp(48)
            minHeight = dp(48)
            setBackgroundResource(R.drawable.editor_tool_background)
            contentDescription = label + ", " + context.getString(description)
            setOnClickListener {
                change(up)
                update()
            }
        }
        addView(button("−", false, Resources.string.editors_format_less))
        addView(text)
        addView(button("+", true, Resources.string.editors_format_more))
        update()
    }

    private fun seconds(ms: Int): String {
        val value = ms / 1000.0
        val number = if (value == Math.floor(value)) value.toInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.', ',')
        return context.getString(Resources.string.editors_slides_seconds, number)
    }

    // The next multiple of size above or below value
    private fun step(value: Int, size: Int, up: Boolean) = if (up) (value / size + 1) * size else ((value + size - 1) / size - 1) * size

    private fun dp(value: Int) = (value * density).toInt()

    private class Effect(val type: Int, val label: Int, val icon: Int)

    private companion object {
        const val DEFAULT_DURATION_MS = 1000
        const val DURATION_STEP_MS = 250
        const val DELAY_STEP_MS = 1000
        const val MAX_MS = 600_000

        // Asc.c_oAscSlideTransitionTypes (Morph isn't played by the slideshow)
        val EFFECTS = listOf(
            Effect(0, Resources.string.editors_slides_effect_none, Resources.drawable.ic_editor_transition_none),
            Effect(1, Resources.string.editors_slides_effect_fade, Resources.drawable.ic_editor_transition_fade),
            Effect(2, Resources.string.editors_slides_effect_push, Resources.drawable.ic_editor_transition_push),
            Effect(3, Resources.string.editors_slides_effect_wipe, Resources.drawable.ic_editor_transition_wipe),
            Effect(4, Resources.string.editors_slides_effect_split, Resources.drawable.ic_editor_transition_split),
            Effect(5, Resources.string.editors_slides_effect_uncover, Resources.drawable.ic_editor_transition_uncover),
            Effect(6, Resources.string.editors_slides_effect_cover, Resources.drawable.ic_editor_transition_cover),
            Effect(7, Resources.string.editors_slides_effect_clock, Resources.drawable.ic_editor_transition_clock),
            Effect(8, Resources.string.editors_slides_effect_zoom, Resources.drawable.ic_editor_transition_zoom)
        )

        // Asc.c_oAscSlideTransitionParams of each effect, as web-apps offers them
        private val SIDES = listOf(
            Resources.string.editors_slides_from_left to 2,
            Resources.string.editors_slides_from_top to 3,
            Resources.string.editors_slides_from_right to 4,
            Resources.string.editors_slides_from_bottom to 5
        )
        private val SIDES_AND_CORNERS = SIDES + listOf(
            Resources.string.editors_slides_from_top_left to 6,
            Resources.string.editors_slides_from_top_right to 7,
            Resources.string.editors_slides_from_bottom_left to 8,
            Resources.string.editors_slides_from_bottom_right to 9
        )
        val OPTIONS = mapOf(
            1 to listOf(Resources.string.editors_slides_smoothly to 0, Resources.string.editors_slides_through_black to 1),
            2 to SIDES,
            3 to SIDES_AND_CORNERS,
            4 to listOf(
                Resources.string.editors_slides_vertical_in to 10,
                Resources.string.editors_slides_vertical_out to 11,
                Resources.string.editors_slides_horizontal_in to 12,
                Resources.string.editors_slides_horizontal_out to 13
            ),
            5 to SIDES_AND_CORNERS,
            6 to SIDES_AND_CORNERS,
            7 to listOf(
                Resources.string.editors_slides_clockwise to 14,
                Resources.string.editors_slides_counterclockwise to 15,
                Resources.string.editors_slides_wedge to 16
            ),
            8 to listOf(
                Resources.string.editors_slides_zoom_in to 17,
                Resources.string.editors_slides_zoom_out to 18,
                Resources.string.editors_slides_zoom_rotate to 19
            )
        )
    }
}
