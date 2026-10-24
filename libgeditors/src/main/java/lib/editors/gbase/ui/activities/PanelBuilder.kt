package lib.editors.gbase.ui.activities

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * The views of the format panels ([FormatPanel], [CellFormatPanel]): sections, rows of buttons,
 * groups of icon buttons (as web-apps' toolbar groups them), choices and color swatches. What
 * shows the current format [F] adds itself to [updaters].
 */
internal abstract class PanelBuilder<F>(protected val context: Context) {

    protected val density = context.resources.displayMetrics.density
    protected val updaters = mutableListOf<(F) -> Unit>()

    protected fun dp(value: Int) = (value * density).toInt()

    protected fun LinearLayout.caption(text: Int) {
        addView(TextView(context).apply {
            setText(text)
            setPaddingRelative(0, dp(8), 0, dp(4))
        })
    }

    protected fun LinearLayout.row(build: LinearLayout.() -> Unit) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            build()
        })
    }

    // A row wider than the panel scrolls instead of squeezing its buttons
    protected fun LinearLayout.scrollRow(build: LinearLayout.() -> Unit) {
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                build()
            })
        })
    }

    protected fun LinearLayout.button(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 15f
        minWidth = dp(48)
        minHeight = dp(48)
        setPaddingRelative(dp(10), 0, dp(10), 0)
        setBackgroundResource(R.drawable.editor_tool_background)
        setOnClickListener { onClick() }
    }.also { addView(it) }

    protected fun LinearLayout.toggle(text: String, isOn: (F) -> Boolean, onClick: () -> Unit): TextView =
        button(text, onClick).also { view ->
            Accessibility.toggle(view)
            updaters += { view.isSelected = isOn(it) }
        }

    // Icon buttons side by side in an outlined group, with some space after it
    protected fun LinearLayout.group(build: LinearLayout.() -> Unit) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = outline(dp(12))
            setPadding(dp(2), dp(2), dp(2), dp(2))
            build()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
    }

    // A value ([value] of the format) between buttons that lower and raise it, named [name] to TalkBack
    protected fun LinearLayout.stepper(name: Int, value: (F) -> String, onLess: () -> Unit, onMore: () -> Unit) = row {
        val label = context.getString(name)
        button("−", onLess).contentDescription = label + ", " + context.getString(Resources.string.editors_format_less)
        val text = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            minWidth = dp(72)
        }.also { addView(it) }
        updaters += {
            text.text = value(it)
            text.contentDescription = label + " " + text.text
        }
        button("+", onMore).contentDescription = label + ", " + context.getString(Resources.string.editors_format_more)
    }

    // An icon button; with isOn, a toggle marked while its format is on. Its name is said and
    // shown on a long press.
    protected fun LinearLayout.icon(icon: Int, name: Int, isOn: ((F) -> Boolean)? = null, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(icon)
            contentDescription = context.getString(name)
            TooltipCompat.setTooltipText(this, contentDescription)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.editor_tool_background)
            setOnClickListener { onClick() }
            if (isOn != null) {
                Accessibility.toggle(this)
                updaters += { isSelected = isOn(it) }
            }
        }.also { addView(it, LinearLayout.LayoutParams(dp(48), dp(48))) }

    // A field showing a setting's value (font, style) that opens the list to choose another; in
    // a row, it takes the room the others leave
    protected fun LinearLayout.selector(name: Int, value: (F) -> String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            textSize = 15f
            minHeight = dp(44)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPaddingRelative(dp(14), 0, dp(8), 0)
            background = framed()
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, ContextCompat.getDrawable(context, Resources.drawable.ic_editor_dropdown), null)
            setOnClickListener { onClick() }
            updaters += {
                text = value(it)
                contentDescription = context.getString(name) + ", " + text
            }
        }.also {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }

    // A row that opens more settings in their own panel (pictures of table styles, ...), with
    // what is set now ([value] of the format, none if empty) and its picture
    protected fun LinearLayout.action(text: Int, value: ((F) -> String)? = null, icon: Int? = null, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPaddingRelative(dp(14), 0, dp(8), 0)
            background = framed()
            setOnClickListener { onClick() }
            val label = context.getString(text)
            addView(TextView(context).apply {
                this.text = label
                textSize = 15f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            contentDescription = label
            if (value != null) {
                val current = TextView(context).apply {
                    textSize = 14f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(160)
                    setTextColor(ContextCompat.getColor(context, Resources.color.editors_icon))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }.also { addView(it) }
                updaters += {
                    current.text = value(it)
                    contentDescription = if (current.text.isEmpty()) label else label + ", " + current.text
                }
            }
            if (icon != null) {
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, Resources.color.editors_icon))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) })
            }
            addView(ImageView(context).apply {
                setImageResource(Resources.drawable.ic_editor_chevron_right)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(4) })
        }.also {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }

    // A setting that is on or off ([isOn] of the format), as a switch with its name
    protected fun LinearLayout.switchRow(text: Int, isOn: (F) -> Boolean, onChange: (Boolean) -> Unit): SwitchCompat =
        SwitchCompat(context).apply {
            setText(text)
            textSize = 15f
            minHeight = dp(48)
            // Clicks only: the format's updates set it without changing anything
            setOnClickListener { onChange(isChecked) }
            updaters += { isChecked = isOn(it) }
        }.also { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

    // The panel's sections by their titles: which are open. The main one ([openSections]) is
    // open; while it stays the main one, the others stay as they were opened or closed.
    private val expandedSections = mutableMapOf<Int, Boolean>()
    private var mainSection = 0

    protected fun openSections(main: Int) {
        if (main == mainSection) return
        mainSection = main
        expandedSections.clear()
        expandedSections[main] = true
    }

    // A section of the panel under its [title] that opens and closes (see openSections)
    protected fun LinearLayout.section(title: Int, build: LinearLayout.() -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            expandable(title, expandedSections[title] == true, { expandedSections[title] = it }, build)
        }.also { addView(it) }

    // A section under a heading that opens and closes it ([expanded] at first, [onToggle] after)
    protected fun LinearLayout.expandable(title: Int, expanded: Boolean, onToggle: (Boolean) -> Unit, build: LinearLayout.() -> Unit) {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            build()
            isVisible = expanded
        }
        val chevron = ImageView(context).apply {
            setImageResource(Resources.drawable.ic_editor_chevron_right)
            rotation = if (expanded) 90f else 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPaddingRelative(0, dp(8), 0, 0)
            setBackgroundResource(R.drawable.editor_tool_background)
            addView(TextView(context).apply {
                setText(title)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams(dp(24), dp(24)))
            contentDescription = context.getString(title)
            fun describe() {
                ViewCompat.setStateDescription(this, context.getString(if (body.isVisible) Resources.string.editors_expanded else Resources.string.editors_collapsed))
            }
            describe()
            setOnClickListener {
                body.isVisible = !body.isVisible
                chevron.rotation = if (body.isVisible) 90f else 0f
                describe()
                onToggle(body.isVisible)
            }
        })
        addView(body)
    }

    // Settings of one of several parts: [tabs] on a frame around the [selected] one's settings,
    // so it shows which part they change
    protected fun LinearLayout.tabbed(tabs: List<String>, selected: Int, onTab: (Int) -> Unit, build: LinearLayout.() -> Unit) {
        val accent = ContextCompat.getColor(context, Resources.color.editors_accent)
        val line = ContextCompat.getColor(context, Resources.color.editors_sheet_header_line)
        val frame = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = outline(dp(12))
        }
        frame.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tabs.forEachIndexed { index, tab ->
                val isOn = index == selected
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.editor_tool_background)
                    addView(TextView(context).apply {
                        text = tab
                        gravity = Gravity.CENTER
                        textSize = 14f
                        minHeight = dp(44)
                        setTextColor(if (isOn) accent else ContextCompat.getColor(context, Resources.color.editors_icon))
                        if (isOn) setTypeface(typeface, Typeface.BOLD)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    // The chosen one's mark, over the line under the tabs
                    addView(View(context).apply { setBackgroundColor(if (isOn) accent else line) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isOn) 3 else 1)).apply { topMargin = dp(if (isOn) 0 else 2) })
                    contentDescription = tab
                    isSelected = isOn
                    Accessibility.toggle(this)
                    setOnClickListener { if (!isOn) onTab(index) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        })
        frame.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(12), dp(4), dp(12), dp(12))
            build()
        })
        addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })
    }

    // Outlined, and marked while pressed as the tools are
    private fun framed() = LayerDrawable(arrayOf(outline(dp(12)), ContextCompat.getDrawable(context, R.drawable.editor_tool_background)))

    // One of several choices: a pill, filled while chosen, with an optional icon before its text
    protected fun LinearLayout.choice(text: String, isOn: (F) -> Boolean, icon: Int? = null, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            minHeight = dp(36)
            setPaddingRelative(dp(if (icon != null) 10 else 14), 0, dp(14), 0)
            if (icon != null) {
                // The 24 dp icons, smaller in the pill
                val drawable = ContextCompat.getDrawable(context, icon)?.mutate()?.apply { setBounds(0, 0, dp(18), dp(18)) }
                setCompoundDrawablesRelative(drawable, null, null, null)
                compoundDrawablePadding = dp(6)
            }
            Accessibility.toggle(this)
            setOnClickListener { onClick() }
            updaters += {
                isSelected = isOn(it)
                background = if (isSelected) filled(dp(18)) else outline(dp(18))
                setTextColor(ContextCompat.getColor(context, if (isSelected) Resources.color.editors_accent else Resources.color.editors_icon))
            }
            background = outline(dp(18))
        }.also {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
                topMargin = dp(6)
                bottomMargin = dp(6)
            })
        }

    // A row of color swatches; "" is automatic or none
    protected fun LinearLayout.colors(colors: List<String>, current: (F) -> String, onColor: (String?) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (color in colors) {
            val swatch = View(context).apply {
                // 48 dp to touch, 36 dp to see
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setOnClickListener { onColor(color.ifEmpty { null }) }
                contentDescription = Accessibility.colorName(context, color)
            }
            updaters += {
                swatch.isSelected = current(it).equals(color, ignoreCase = true)
                swatch.background = InsetDrawable(swatchDrawable(context, color, swatch.isSelected), dp(6))
            }
            row.addView(swatch)
        }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    private fun outline(radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setStroke(dp(1), ContextCompat.getColor(context, Resources.color.editors_sheet_header_line))
    }

    private fun filled(radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        color = ColorStateList.valueOf(ContextCompat.getColor(context, Resources.color.editors_tool_selected))
        setStroke(dp(1), ContextCompat.getColor(context, Resources.color.editors_selection_handle))
    }
}
