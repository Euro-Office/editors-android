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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * Comments in a panel (see [PanelHost]): all comments in the document's order; a tap on one, or
 * on it in the document, shows it with its replies, where it is, and the way to the previous
 * and next one. With [canComment], comments can be added (on the selection), answered, resolved
 * and deleted. In DocSpace files ([fileId]), "@" suggests the people the file is shared with
 * and mentioned people are notified.
 */
internal class CommentsUi(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    val canComment: Boolean,
    private val fileId: String?,
    private val panels: PanelHost,
    /** The document shows the comment above the panel (going to it). */
    private val reveal: (Comment) -> Unit = {},
    /** The document's comments after they changed: for the comments button and the marks of them. */
    private val onComments: (List<Comment>) -> Unit = {}
) {

    private val density = activity.resources.displayMetrics.density
    private val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var dialog: PanelHost.Panel? = null
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var users: List<SharedUser> = emptyList()
    private var counting: Job? = null
    private var comments: List<Comment> = emptyList()
    // The comment shown on its own (with its replies), null while the list is shown
    private var threadId: String? = null
    private val thread = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val messenger: MessengerClient? = fileId?.let { id ->
        MessengerClient(activity).also { client ->
            client.init(activity)
            scope.launch { users = runCatching { client.getSharedUsers(id).first() }.getOrDefault(emptyList()) }
        }
    }

    /** All comments. */
    fun show() {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), 0, dp(16), dp(24))
            if (canComment) addView(action(Resources.string.editors_comments_add) { compose(null) })
            (list.parent as? android.view.ViewGroup)?.removeView(list)
            addView(list)
        }
        open(activity.getString(Resources.string.editors_comments), content, null)
    }

    /**
     * One comment with its replies, a field to reply and the way to the previous and next
     * comment; the document shows where it is.
     */
    fun showThread(id: String) {
        if (dialog != null && threadId == id) return
        val showing = dialog != null && threadId != null
        threadId = id
        edit { gotoComment(id) }
        comments.firstOrNull { it.id == id }?.let(reveal)
        if (showing) {
            render()
            return
        }
        val reply = replyField()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), 0, dp(16), dp(24))
            (thread.parent as? android.view.ViewGroup)?.removeView(thread)
            addView(thread)
            if (canComment) addView(reply)
        }
        open(activity.getString(Resources.string.editors_comment_title), content, id)
    }

    // A new panel replaces the one shown, whose closing (maybe later, after its animation)
    // then leaves the new one alone
    private fun open(title: String, content: android.view.View, thread: String?) {
        lateinit var panel: PanelHost.Panel
        // The document stays scrollable beside the comments
        panel = panels.show(title, content, modal = false) {
            if (dialog === panel) {
                dialog = null
                threadId = null
            }
        }
        dialog = panel
        threadId = thread
        reload()
    }

    /** A new comment on the selection (the selection's menu). */
    fun add() {
        if (canComment) compose(null)
    }

    /** After a command: the panel again, if it is shown; the comments at most once a second otherwise. */
    fun onState() {
        if (dialog != null) {
            reload()
            return
        }
        if (counting != null) return
        counting = scope.launch {
            delay(COUNT_DELAY_MS)
            counting = null
            load()
        }
    }

    private suspend fun load(): List<Comment>? {
        val document = document() ?: return null
        val loaded = withContext(EditorEngine.dispatcher) { runCatching { document.comments() }.getOrNull() } ?: return null
        if (loaded != comments) {
            comments = loaded
            onComments(loaded)
        }
        return loaded
    }

    // In the document's order: by sheet, page, then from top to bottom
    private fun ordered() = comments.sortedWith(
        compareBy<Comment>({ it.sheet }, { it.page }, { if (it.sheet >= 0) it.row.toDouble() else it.y }, { if (it.sheet >= 0) it.col.toDouble() else it.x }, { it.time })
    )

    private fun reload() {
        scope.launch {
            load() ?: return@launch
            render()
        }
    }

    private fun render() {
        val id = threadId
        if (id != null) {
            renderThread(id)
            return
        }
        list.removeAllViews()
        if (comments.isEmpty()) {
            list.addView(TextView(activity).apply {
                setText(Resources.string.editors_comments_none)
                setPaddingRelative(0, dp(16), 0, dp(16))
            })
        }
        ordered().forEach { list.addView(commentView(it)) }
    }

    // The comment, its replies and what can be done with it; above, the way through the comments
    private fun renderThread(id: String) {
        thread.removeAllViews()
        val all = ordered()
        val index = all.indexOfFirst { it.id == id }
        val comment = all.getOrNull(index)
        thread.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(action(Resources.string.editors_comments_all) { show() })
            addView(android.view.View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            if (all.size > 1 && index >= 0) {
                addView(arrow("‹", Resources.string.editors_comments_previous) { showThread(all[(index - 1 + all.size) % all.size].id) })
                addView(TextView(activity).apply {
                    text = activity.getString(Resources.string.editors_comments_position, index + 1, all.size)
                    setPaddingRelative(dp(4), 0, dp(4), 0)
                })
                addView(arrow("›", Resources.string.editors_comments_next) { showThread(all[(index + 1) % all.size].id) })
            }
        })
        if (comment == null) {
            thread.addView(TextView(activity).apply {
                setText(Resources.string.editors_comments_none)
                setPaddingRelative(0, dp(16), 0, dp(16))
            })
            return
        }
        thread.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(0, dp(8), 0, dp(4))
            addView(header(comment.user, comment.time))
            if (comment.quote.isNotEmpty()) addView(quote(comment.quote))
            addView(TextView(activity).apply {
                text = comment.text
                textSize = 16f
                setPaddingRelative(0, dp(4), 0, dp(4))
            })
            comment.replies.forEach { reply ->
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPaddingRelative(dp(16), dp(8), 0, 0)
                    addView(header(reply.user, reply.time))
                    addView(TextView(activity).apply { text = reply.text; textSize = 15f })
                })
            }
        })
        if (canComment) {
            thread.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(action(if (comment.solved) Resources.string.editors_comments_reopen else Resources.string.editors_comments_resolve) {
                    edit { resolveComment(comment.id, !comment.solved) }
                })
                addView(action(Resources.string.editors_comments_delete) {
                    // On to the next comment, or the list after the last one
                    val next = all.getOrNull(index + 1) ?: all.getOrNull(index - 1)
                    edit { deleteComment(comment.id) }
                    if (next != null) showThread(next.id) else show()
                })
            })
        }
    }

    // A field and a button to answer the comment shown; kept while the comment is drawn again
    private fun replyField() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(0, dp(8), 0, 0)
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            setHint(Resources.string.editors_comments_reply_hint)
        }
        val mentioned = mutableMapOf<String, String>()
        if (users.isNotEmpty()) suggestMentions(input, mentioned)
        addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(action(Resources.string.editors_comments_post) {
            val text = input.text.toString().trim()
            val id = threadId
            if (text.isEmpty() || id == null) return@action
            post(id, text, mentioned) { input.text.clear() }
        })
    }

    private fun arrow(text: String, description: Int, onClick: () -> Unit) = TextView(activity).apply {
        this.text = text
        textSize = 22f
        gravity = Gravity.CENTER
        minWidth = dp(48)
        minHeight = dp(48)
        contentDescription = activity.getString(description)
        setBackgroundResource(R.drawable.editor_tool_background)
        setOnClickListener { onClick() }
    }

    private fun quote(text: String) = TextView(activity).apply {
        this.text = text
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTypeface(typeface, Typeface.ITALIC)
        alpha = 0.7f
    }

    // A comment in the list: a tap shows it with its replies
    private fun commentView(comment: Comment) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(4), dp(12), dp(4), dp(12))
        alpha = if (comment.solved) 0.6f else 1f
        setBackgroundResource(R.drawable.editor_tool_background)
        addView(header(comment.user, comment.time))
        if (comment.quote.isNotEmpty()) addView(quote(comment.quote))
        addView(TextView(activity).apply {
            text = comment.text
            textSize = 15f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPaddingRelative(0, dp(2), 0, dp(2))
        })
        if (comment.replies.isNotEmpty()) {
            addView(TextView(activity).apply {
                text = activity.resources.getQuantityString(Resources.plurals.editors_comments_replies, comment.replies.size, comment.replies.size)
                setTextColor(activity.getColor(Resources.color.editors_selection_handle))
            })
        }
        setOnClickListener { showThread(comment.id) }
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
                if (text.isNotEmpty()) post(replyTo, text, mentioned)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    // A new comment or a reply ([replyTo]) by the user, whose name is asked once; mentions
    // still in the text are notified
    private fun post(replyTo: String?, text: String, mentioned: Map<String, String>, onPosted: () -> Unit = {}) {
        val name = preferences.getString(KEY_NAME, null)
        if (name.isNullOrBlank()) {
            askName { post(replyTo, text, mentioned, onPosted) }
            return
        }
        edit {
            setCommentUser(preferences.getString(KEY_ID, null).orEmpty(), name)
            if (replyTo == null) addComment(text) else replyComment(replyTo, text)
        }
        val emails = mentioned.filterKeys { text.contains("@$it") }.values.toSet()
        if (emails.isNotEmpty() && fileId != null) messenger?.sendMentionNotifications(fileId, emails, text)
        onPosted()
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
        const val COUNT_DELAY_MS = 1000L
    }
}
