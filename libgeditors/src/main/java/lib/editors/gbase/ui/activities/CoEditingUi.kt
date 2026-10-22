package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import lib.editors.base.engine.CoEditingState
import lib.editors.base.engine.Participant
import lib.editors.resources.R as Resources

/**
 * Co-editing in the editor's UI: a bar while the connection is lost (sdkjs blocks editing then,
 * and only allows viewing once it can't reconnect), and the people in the document behind the
 * toolbar's people button. Others' cursors are drawn by the document view.
 */
internal class CoEditingUi(
    private val context: Context,
    /** The bar (editor_coediting_bar), above the toolbar. */
    private val bar: TextView,
    private val button: ImageButton,
    /** sdkjs turned to viewing: the editor stops editing too. */
    private val onDisconnected: () -> Unit
) {

    private var state: CoEditingState? = null
    private var disconnected = false

    init {
        button.isVisible = true
        button.setOnClickListener { showPeople() }
    }

    fun update(state: CoEditingState?) {
        state ?: return
        val previous = this.state
        this.state = state
        if (previous?.participants != state.participants) {
            val others = state.participants.count { !it.me }
            button.contentDescription = if (others == 0) {
                context.getString(Resources.string.editors_coediting_people)
            } else {
                context.resources.getQuantityString(Resources.plurals.editors_coediting_others, others, others)
            }
            button.isSelected = state.coEditors.isNotEmpty()
        }
        when {
            state.disconnected -> show(Resources.string.editors_coediting_disconnected, Resources.color.editors_coediting_warning)
            state.reconnecting -> show(Resources.string.editors_coediting_reconnecting, Resources.color.editors_coediting_reconnecting)
            else -> bar.isVisible = false
        }
        if (state.disconnected && !disconnected) {
            disconnected = true
            onDisconnected()
        }
    }

    private fun show(text: Int, color: Int) {
        bar.setText(text)
        bar.setBackgroundColor(ContextCompat.getColor(context, color))
        bar.isVisible = true
    }

    private fun showPeople() {
        val people = state?.participants.orEmpty().sortedBy { !it.me }
        val density = context.resources.displayMetrics.density
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
        }
        for (person in people) list.addView(row(person, density))
        if (people.none { !it.me }) {
            list.addView(TextView(context).apply {
                setText(Resources.string.editors_coediting_alone)
                setPadding(0, (8 * density).toInt(), 0, 0)
            })
        }
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_coediting_people)
            .setView(list)
            .setPositiveButton(Resources.string.editors_close, null)
            .show()
    }

    private fun row(person: Participant, density: Float): View = TextView(context).apply {
        text = when {
            person.me -> context.getString(Resources.string.editors_coediting_you, person.name)
            person.view -> context.getString(Resources.string.editors_coediting_viewing, person.name)
            else -> person.name
        }
        gravity = Gravity.CENTER_VERTICAL
        minHeight = (40 * density).toInt()
        compoundDrawablePadding = (12 * density).toInt()
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(runCatching { Color.parseColor(person.color) }.getOrDefault(Color.GRAY))
            setSize((12 * density).toInt(), (12 * density).toInt())
        }
        setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
    }
}
