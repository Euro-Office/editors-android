package lib.editors.gbase.ui.activities

import android.app.DatePickerDialog
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.FormField
import lib.editors.gbase.R
import java.util.Calendar
import lib.editors.resources.R as Resources

/**
 * Filling forms: a bar with the previous and next field, whether the required fields are
 * filled and clearing all fields, and pickers for list, date and picture fields when one is
 * reached. Text is typed and check boxes are tapped in the document.
 */
internal class FormsUi(
    private val context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    /** A picture for a picture field, from the gallery. */
    private val pickPicture: (FormField) -> Unit,
    /** Shown with "start filling" (StartFilling): sends the form to its roles. */
    private val onStartFilling: (() -> Unit)?
) {

    private val density = context.resources.displayMetrics.density
    private var currentId: String? = null
    private val status = TextView(context).apply {
        textSize = 13f
        setSingleLine()
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    /** Above the toolbar while the document has form fields. */
    val bar: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(8), 0, dp(4), 0)
        minimumHeight = dp(48)
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, background, true)
        setBackgroundColor(background.data)
        elevation = 2 * density
        isVisible = false
        addView(button("‹") { edit { formAction("previous") } }.apply { contentDescription = context.getString(Resources.string.editors_forms_previous) })
        addView(button("›") { edit { formAction("next") } }.apply { contentDescription = context.getString(Resources.string.editors_forms_next) })
        addView(status)
        Accessibility.pane(this, context.getString(Resources.string.editors_forms))
        Accessibility.live(status)
        addView(button(context.getString(Resources.string.editors_forms_clear)) {
            AlertDialog.Builder(context)
                .setMessage(Resources.string.editors_forms_clear_confirm)
                .setPositiveButton(Resources.string.editors_forms_clear) { _, _ -> edit { formAction("clear") } }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        })
        onStartFilling?.let { start -> addView(button(context.getString(Resources.string.editors_forms_start_filling)) { start() }) }
    }

    fun update(state: EditorState) {
        val forms = state.forms
        bar.isVisible = forms != null
        if (forms == null) return
        val current = forms.current
        status.text = when {
            current != null && current.help.isNotEmpty() -> current.help
            current != null -> current.key.ifEmpty { current.placeholder }
            forms.allRequiredFilled -> context.getString(Resources.string.editors_forms_all_filled)
            else -> context.getString(Resources.string.editors_forms_required_missing)
        }
        // A field just reached that is filled with a picker
        if (current?.id != currentId) {
            currentId = current?.id
            if (current != null) choose(current)
        }
    }

    private fun choose(field: FormField) {
        when (field.type) {
            "comboBox", "dropDownList" -> {
                if (field.items.isEmpty()) return
                AlertDialog.Builder(context)
                    .setTitle(field.key.ifEmpty { field.placeholder })
                    .setItems(field.items.map { it.text }.toTypedArray()) { _, index -> edit { setFormValue(field, field.items[index].value) } }
                    .show()
            }
            "date" -> {
                val today = Calendar.getInstance()
                DatePickerDialog(context, { _, year, month, day ->
                    edit { setFormValue(field, "%04d-%02d-%02d".format(year, month + 1, day)) }
                }, today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH)).show()
            }
            "picture" -> pickPicture(field)
        }
    }

    private fun button(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        minWidth = dp(48)
        minHeight = dp(48)
        textSize = if (text.length == 1) 20f else 14f
        setPaddingRelative(dp(8), 0, dp(8), 0)
        setTextColor(context.getColor(Resources.color.editors_selection_handle))
        setBackgroundResource(R.drawable.editor_tool_background)
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * density).toInt()
}
