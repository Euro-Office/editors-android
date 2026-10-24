package lib.editors.gbase.ui.activities

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.SlideTransition
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * The transition to the current slide, as web-apps sets it, in a panel (see [PanelHost]): the
 * effect (its icons from web-apps) and its option, the duration, and how the slideshow goes on
 * to the next slide (on a tap, after a delay). Changes apply at once; all slides can take them.
 */
internal class TransitionPanel(
    context: Context,
    private val panels: PanelHost,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    /** After a change (the slides' strip shows transitions). */
    private val onChanged: () -> Unit
) : PanelBuilder<SlideTransition>(context) {

    private var current = DEFAULT

    /** Shows the panel for the current slide's transition ([transition], none if null); [onBack] to the panel it was opened from. */
    fun show(transition: SlideTransition?, onBack: (() -> Unit)? = null) {
        current = transition?.let {
            it.copy(
                type = EFFECTS.firstOrNull { effect -> effect.type == it.type }?.type ?: 0,
                durationMs = it.durationMs.takeIf { ms -> ms > 0 } ?: DEFAULT_DURATION_MS
            )
        } ?: DEFAULT
        updaters.clear()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(8), dp(16), dp(24))

            caption(Resources.string.editors_slides_effect)
            effects()
            val option = action(Resources.string.editors_slides_option, { optionLabel(it) }) { chooseOption(onBack) }
            updaters += { option.isVisible = OPTIONS[it.type].orEmpty().isNotEmpty() }

            caption(Resources.string.editors_slides_duration)
            stepper(Resources.string.editors_slides_duration, { seconds(it.durationMs) }, {
                apply(current.copy(durationMs = step(current.durationMs, DURATION_STEP_MS, false).coerceIn(DURATION_STEP_MS, MAX_MS)))
            }, {
                apply(current.copy(durationMs = step(current.durationMs, DURATION_STEP_MS, true).coerceIn(DURATION_STEP_MS, MAX_MS)))
            })

            scrollRow {
                choice(context.getString(Resources.string.editors_slides_start_on_tap), { it.onClick }) { apply(current.copy(onClick = !current.onClick)) }
                choice(context.getString(Resources.string.editors_slides_delay), { it.advanceAfter }) { apply(current.copy(advanceAfter = !current.advanceAfter)) }
            }
            val delay = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                caption(Resources.string.editors_slides_delay_after)
                stepper(Resources.string.editors_slides_delay_after, { seconds(it.afterMs) }, {
                    apply(current.copy(afterMs = step(current.afterMs, DELAY_STEP_MS, false).coerceIn(0, MAX_MS)))
                }, {
                    apply(current.copy(afterMs = step(current.afterMs, DELAY_STEP_MS, true).coerceIn(0, MAX_MS)))
                })
            }
            addView(delay)
            updaters += { delay.isVisible = it.advanceAfter }

            scrollRow {
                button(context.getString(Resources.string.editors_slides_apply_all)) { save(all = true) }
            }
        }
        panels.show(context.getString(Resources.string.editors_slides_transition), content, onBack, modal = false) {}
        updaters.forEach { it(current) }
    }

    // The effects: an icon with its name each, the chosen one marked
    private fun LinearLayout.effects() {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (effect in EFFECTS) {
            row.addView(LinearLayout(context).apply {
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
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                setOnClickListener {
                    val options = OPTIONS[effect.type].orEmpty()
                    val option = current.option.takeIf { value -> options.any { it.second == value } } ?: options.firstOrNull()?.second ?: 0
                    apply(current.copy(type = effect.type, option = option))
                }
                updaters += { isSelected = it.type == effect.type }
            }, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    private fun chooseOption(onBack: (() -> Unit)?) {
        val options = OPTIONS[current.type].orEmpty()
        val reopen = { show(current, onBack) }
        panels.choose(
            context.getString(Resources.string.editors_slides_option), options.map { context.getString(it.first) },
            options.indexOfFirst { it.second == current.option }, onBack = reopen
        ) { index -> apply(current.copy(option = options[index].second)) }
    }

    private fun optionLabel(transition: SlideTransition) =
        OPTIONS[transition.type]?.firstOrNull { it.second == transition.option }?.let { context.getString(it.first) }.orEmpty()

    // To this slide at once
    private fun apply(transition: SlideTransition) {
        current = transition
        updaters.forEach { it(current) }
        save(all = false)
    }

    private fun save(all: Boolean) {
        val transition = current
        edit {
            setTransition(transition.type, transition.option, transition.durationMs, all)
            setSlideAdvance(transition.onClick, transition.advanceAfter, transition.afterMs, all)
        }
        onChanged()
    }

    private fun seconds(ms: Int): String {
        val value = ms / 1000.0
        val number = if (value == Math.floor(value)) value.toInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.', ',')
        return context.getString(Resources.string.editors_slides_seconds, number)
    }

    // The next multiple of size above or below value
    private fun step(value: Int, size: Int, up: Boolean) = if (up) (value / size + 1) * size else ((value + size - 1) / size - 1) * size

    private class Effect(val type: Int, val label: Int, val icon: Int)

    private companion object {
        const val DEFAULT_DURATION_MS = 1000
        const val DURATION_STEP_MS = 250
        const val DELAY_STEP_MS = 1000
        const val MAX_MS = 600_000
        val DEFAULT = SlideTransition(0, 0, DEFAULT_DURATION_MS, onClick = true, advanceAfter = false, afterMs = 0)

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
