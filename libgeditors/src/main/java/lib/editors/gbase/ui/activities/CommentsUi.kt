package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.Comment
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.gbase.R
import lib.editors.resources.R as Resources
import app.documents.shared.MessengerClient
import app.documents.shared.models.SharedUser

/**
 * Comments in a panel (see [PanelHost]): all comments with their replies; a tap shows one in the
 * document. With [canComment], comments can be added (on the selection), answered, resolved
 * and deleted. In DocSpace files ([fileId]), "@" suggests the people the file is shared with
 * and mentioned people are notified.
 */
internal class CommentsUi(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val canComment: Boolean,
    private val fileId: String?,
    private val panels: PanelHost
) {

    private val density = activity.resources.displayMetrics.density
    private val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var dialog: PanelHost.Panel? = null
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var users: List<SharedUser> = emptyList()
    private val messenger: MessengerClient? = fileId?.let { id ->
        MessengerClient(activity).also { client ->
            client.init(activity)
            scope.launch { users = runCatching { client.getSharedUsers(id).first() }.getOrDefault(emptyList()) }
        }
    }

    fun show() {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), 0, dp(16), dp(24))
            if (canComment) addView(action(Resources.string.editors_comments_add) { compose(null) })
            (list.parent as? android.view.ViewGroup)?.removeView(list)
            addView(list)
        }
        dialog = panels.show(activity.getString(Resources.string.editors_comments), content) { dialog = null }
        reload()
    }

    /** After a command: the list again, if it is shown. */
    fun onState() {
        if (dialog != null) reload()
    }

    private fun reload() {
        val document = document() ?: return
        scope.launch {
            val comments = withContext(EditorEngine.dispatcher) { runCatching { document.comments() }.getOrDefault(emptyList()) }
            list.removeAllViews()
            if (comments.isEmpty()) {
                list.addView(TextView(activity).apply {
                    setText(Resources.string.editors_comments_none)
                    setPaddingRelative(0, dp(16), 0, dp(16))
                })
            }
            comments.sortedBy { it.time }.forEach { list.addView(commentView(it)) }
        }
    }

    private fun commentView(comment: Comment) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(0, dp(12), 0, dp(4))
        alpha = if (comment.solved) 0.6f else 1f
        addView(header(comment.user, comment.time))
        if (comment.quote.isNotEmpty()) {
            addView(TextView(activity).apply {
                text = comment.quote
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.ITALIC)
                alpha = 0.7f
            })
        }
        addView(TextView(activity).apply {
            text = comment.text
            textSize = 15f
            setPaddingRelative(0, dp(2), 0, dp(2))
        })
        comment.replies.forEach { reply ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(dp(16), dp(6), 0, 0)
                addView(header(reply.user, reply.time))
                addView(TextView(activity).apply { text = reply.text })
            })
        }
        if (canComment) {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(action(Resources.string.editors_comments_reply) { compose(comment.id) })
                addView(action(if (comment.solved) Resources.string.editors_comments_reopen else Resources.string.editors_comments_resolve) {
                    edit { resolveComment(comment.id, !comment.solved) }
                })
                addView(action(Resources.string.editors_comments_delete) { edit { deleteComment(comment.id) } })
            })
        }
        setOnClickListener {
            edit { gotoComment(comment.id) }
            dialog?.dismiss()
        }
    }

    private fun header(user: String, time: Long) = TextView(activity).apply {
        val date = if (time > 0) DateUtils.getRelativeTimeSpanString(time).toString() else ""
        text = listOf(user.ifEmpty { activity.getString(Resources.string.editors_comments_unknown) }, date)
            .filter { it.isNotEmpty() }.joinToString(" · ")
        setTypeface(typeface, Typeface.BOLD)
        textSize = 13f
    }

    private fun action(label: Int, onClick: () -> Unit) = TextView(activity).apply {
        setText(label)
        setTextColor(activity.getColor(Resources.color.editors_selection_handle))
        setPaddingRelative(dp(8), dp(10), dp(8), dp(10))
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.editor_tool_background)
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------------------------
    // Writing: a new comment (on the selection) or a reply; the author's name is asked once

    private fun compose(replyTo: String?) {
        val name = preferences.getString(KEY_NAME, null)
        if (name.isNullOrBlank()) {
            askName { compose(replyTo) }
            return
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP
            setHint(if (users.isEmpty()) Resources.string.editors_comments_hint else Resources.string.editors_comments_hint_mentions)
        }
        val mentioned = mutableMapOf<String, String>()
        if (users.isNotEmpty()) suggestMentions(input, mentioned)
        AlertDialog.Builder(activity)
            .setTitle(if (replyTo == null) Resources.string.editors_comments_add else Resources.string.editors_comments_reply)
            .setView(FrameLayout(activity).apply {
                setPaddingRelative(dp(24), dp(8), dp(24), 0)
                addView(input)
            })
            .setPositiveButton(Resources.string.editors_comments_post) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                edit {
                    setCommentUser(preferences.getString(KEY_ID, null).orEmpty(), name)
                    if (replyTo == null) addComment(text) else replyComment(replyTo, text)
                }
                // Mentions still in the text are notified
                val emails = mentioned.filterKeys { text.contains("@$it") }.values.toSet()
                if (emails.isNotEmpty() && fileId != null) messenger?.sendMentionNotifications(fileId, emails, text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    // "@" and the start of a name suggest people; a choice puts "@Name " in the text
    private fun suggestMentions(input: EditText, mentioned: MutableMap<String, String>) {
        val popup = ListPopupWindow(activity).apply {
            anchorView = input
            isModal = false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: return
                val cursor = input.selectionStart
                val at = text.lastIndexOf('@', (cursor - 1).coerceAtLeast(0))
                val query = if (at >= 0 && cursor > at) text.substring(at + 1, cursor) else null
                if (query == null || query.contains(' ') && query.length > 30 || query.contains('\n')) {
                    popup.dismiss()
                    return
                }
                val matches = users.filter { it.filterByNameOrEmail(query) }.take(6)
                if (matches.isEmpty()) {
                    popup.dismiss()
                    return
                }
                popup.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, matches.map { it.displayName }))
                popup.setOnItemClickListener { _, _, position, _ ->
                    val user = matches[position]
                    input.text.replace(at, cursor, "@${user.displayName} ")
                    mentioned[user.displayName] = user.email
                    popup.dismiss()
                }
                popup.show()
            }
        })
    }

    private fun askName(onName: () -> Unit) {
        val input = EditText(activity).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(activity)
            .setTitle(Resources.string.editors_comments_name)
            .setMessage(Resources.string.editors_comments_name_message)
            .setView(FrameLayout(activity).apply {
                setPaddingRelative(dp(24), 0, dp(24), 0)
                addView(input)
            })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                preferences.edit()
                    .putString(KEY_NAME, name)
                    .putString(KEY_ID, preferences.getString(KEY_ID, null) ?: java.util.UUID.randomUUID().toString())
                    .apply()
                onName()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val PREFERENCES = "lib.editors.comments"
        const val KEY_NAME = "user_name"
        const val KEY_ID = "user_id"
    }
}
