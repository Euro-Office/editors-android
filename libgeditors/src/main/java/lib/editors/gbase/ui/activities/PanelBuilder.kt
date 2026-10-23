package lib.editors.gbase.ui.activities

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * The views of the format panels ([FormatPanel], [CellFormatPanel]): headings, rows of buttons,
 * groups of icon buttons (as web-apps' toolbar groups them), choices and color swatches. What
 * shows the current format [F] adds itself to [updaters].
 */
internal abstract class PanelBuilder<F>(protected val context: Context) {

    protected val density = context.resources.displayMetrics.density
    protected val updaters = mutableListOf<(F) -> Unit>()

    protected fun dp(value: Int) = (value * density).toInt()

    protected fun LinearLayout.heading(text: Int) {
        addView(TextView(context).apply {
            setText(text)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPaddingRelative(0, dp(16), 0, dp(4))
        })
    }

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

    // A row that opens more settings in their own panel (pictures of table styles, ...)
    protected fun LinearLayout.action(text: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            setText(text)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            textSize = 15f
            minHeight = dp(48)
            setPaddingRelative(dp(14), 0, dp(8), 0)
            background = framed()
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, ContextCompat.getDrawable(context, Resources.drawable.ic_editor_chevron_right), null)
            setOnClickListener { onClick() }
        }.also {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
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
                swatch.background = InsetDrawable(swatchDrawable(color, swatch.isSelected), dp(6))
            }
            row.addView(swatch)
        }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    protected fun swatchDrawable(color: String, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (color.isEmpty()) Color.TRANSPARENT else Color.parseColor("#$color"))
        val border = ContextCompat.getColor(context, if (selected) Resources.color.editors_selection_handle else Resources.color.editors_sheet_header_line)
        setStroke(if (selected) dp(3) else dp(1), border)
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
