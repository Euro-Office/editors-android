package lib.editors.gbase.ui.activities

import android.content.Context
import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import app.documents.shared.MessengerClient
import app.documents.shared.models.SharedUser
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
import lib.editors.gbase.ui.commands.composePanel
import lib.editors.gbase.ui.commands.showComposeDialog
import lib.editors.resources.R as Resources

/**
 * Comments in a panel (see [PanelHost]), in Compose: all comments in the document's order; a
 * tap on one, or on it in the document, shows it with its replies, where it is, and the way to
 * the previous and next one. With [canComment], comments can be added (on the selection),
 * answered, resolved and deleted. In DocSpace files ([fileId]), "@" suggests the people the file
 * is shared with and mentioned people are notified.
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

    private val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var dialog: PanelHost.Panel? = null
    private var users: List<SharedUser> = emptyList()
    private var counting: Job? = null
    // What the panel shows: it is drawn anew as they change
    private var comments by mutableStateOf(emptyList<Comment>())
    // The comment shown on its own (with its replies), null while the list is shown
    private var threadId by mutableStateOf<String?>(null)
    private val messenger: MessengerClient? = fileId?.let { id ->
        MessengerClient(activity).also { client ->
            client.init(activity)
            scope.launch { users = runCatching { client.getSharedUsers(id).first() }.getOrDefault(emptyList()) }
        }
    }

    /** All comments. */
    fun show() {
        open(activity.getString(Resources.string.editors_comments), composePanel(activity) { CommentList() }, null)
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
        // Shown already: drawn anew for the other comment
        if (showing) return
        open(activity.getString(Resources.string.editors_comment_title), composePanel(activity) { Thread() }, id)
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
        scope.launch { load() }
    }

    // ---------------------------------------------------------------------------------
    // The list and a comment

    @Composable
    private fun CommentList() {
        Column(Modifier.padding(bottom = 24.dp)) {
            if (canComment) {
                FilledTonalButton(onClick = { compose(null) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Icon(painterResource(Resources.drawable.ic_editor_comment), null, Modifier.padding(end = 8.dp))
                    Text(stringResource(Resources.string.editors_comments_add))
                }
            }
            val all = ordered()
            if (all.isEmpty()) None()
            all.forEachIndexed { index, comment ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Column(Modifier.fillMaxWidth().clickable { showThread(comment.id) }.padding(horizontal = 16.dp, vertical = 12.dp).alpha(if (comment.solved) 0.6f else 1f)) {
                    Header(comment.user, comment.time)
                    if (comment.quote.isNotEmpty()) Quote(comment.quote)
                    Text(comment.text, Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (comment.replies.isNotEmpty()) {
                        Text(
                            pluralStringResource(Resources.plurals.editors_comments_replies, comment.replies.size, comment.replies.size),
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }

    // The comment, its replies and what can be done with it; above, the way through the comments
    @Composable
    private fun Thread() {
        val id = threadId ?: return
        val all = ordered()
        val index = all.indexOfFirst { it.id == id }
        val comment = all.getOrNull(index)
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::show) { Text(stringResource(Resources.string.editors_comments_all)) }
                Box(Modifier.weight(1f))
                if (all.size > 1 && index >= 0) {
                    IconButton(onClick = { showThread(all[(index - 1 + all.size) % all.size].id) }) {
                        // The arrow to the right, mirrored
                        Icon(painterResource(Resources.drawable.ic_editor_chevron_right), stringResource(Resources.string.editors_comments_previous), Modifier.graphicsLayer(scaleX = -1f))
                    }
                    Text(stringResource(Resources.string.editors_comments_position, index + 1, all.size), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { showThread(all[(index + 1) % all.size].id) }) {
                        Icon(painterResource(Resources.drawable.ic_editor_chevron_right), stringResource(Resources.string.editors_comments_next))
                    }
                }
            }
            if (comment == null) {
                None()
                return@Column
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Header(comment.user, comment.time)
                if (comment.quote.isNotEmpty()) Quote(comment.quote)
                Text(comment.text, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyLarge)
                for (reply in comment.replies) {
                    Column(Modifier.padding(start = 16.dp, top = 8.dp)) {
                        Header(reply.user, reply.time)
                        Text(reply.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (canComment) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { edit { resolveComment(comment.id, !comment.solved) } }) {
                        Text(stringResource(if (comment.solved) Resources.string.editors_comments_reopen else Resources.string.editors_comments_resolve))
                    }
                    TextButton(onClick = {
                        // On to the next comment, or the list after the last one
                        val next = all.getOrNull(index + 1) ?: all.getOrNull(index - 1)
                        edit { deleteComment(comment.id) }
                        if (next != null) showThread(next.id) else show()
                    }) { Text(stringResource(Resources.string.editors_comments_delete)) }
                }
                ReplyField(comment.id)
            }
        }
    }

    // A field and a button to answer the comment shown
    @Composable
    private fun ReplyField(id: String) {
        var text by remember(id) { mutableStateOf(TextFieldValue("")) }
        val mentioned = remember(id) { mutableMapOf<String, String>() }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MentionField(text, { text = it }, mentioned, Resources.string.editors_comments_reply_hint, Modifier.weight(1f), maxLines = 4)
            TextButton(onClick = {
                val typed = text.text.trim()
                if (typed.isNotEmpty()) post(id, typed, mentioned) { text = TextFieldValue("") }
            }) { Text(stringResource(Resources.string.editors_comments_post)) }
        }
    }

    @Composable
    private fun None() {
        Text(stringResource(Resources.string.editors_comments_none), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun Quote(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }

    @Composable
    private fun Header(user: String, time: Long) {
        val date = if (time > 0) DateUtils.getRelativeTimeSpanString(time).toString() else ""
        val text = listOf(user.ifEmpty { stringResource(Resources.string.editors_comments_unknown) }, date).filter { it.isNotEmpty() }.joinToString(" · ")
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }

    /**
     * A text field where "@" and the start of a name suggest the people the file is shared with;
     * a choice puts "@Name " in the text and goes to [mentioned] (name to email).
     */
    @Composable
    private fun MentionField(
        value: TextFieldValue, onValue: (TextFieldValue) -> Unit, mentioned: MutableMap<String, String>, hint: Int,
        modifier: Modifier = Modifier, minLines: Int = 1, maxLines: Int = Int.MAX_VALUE, focus: FocusRequester? = null
    ) {
        val cursor = value.selection.start
        val at = value.text.lastIndexOf('@', (cursor - 1).coerceAtLeast(0))
        val query = if (at >= 0 && cursor > at) value.text.substring(at + 1, cursor) else null
        val matches = if (users.isEmpty() || query == null || query.length > 30 || query.contains('\n')) emptyList() else users.filter { it.filterByNameOrEmail(query) }.take(6)
        Box(modifier) {
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                placeholder = { Text(stringResource(hint)) },
                minLines = minLines,
                maxLines = maxLines,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().let { if (focus != null) it.focusRequester(focus) else it }
            )
            // Not taking the focus: typing goes on
            DropdownMenu(expanded = matches.isNotEmpty(), onDismissRequest = {}, properties = PopupProperties(focusable = false)) {
                for (user in matches) {
                    DropdownMenuItem(text = { Text(user.displayName) }, onClick = {
                        val inserted = "@${user.displayName} "
                        onValue(TextFieldValue(value.text.replaceRange(at, cursor, inserted), TextRange(at + inserted.length)))
                        mentioned[user.displayName] = user.email
                    })
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Writing: a new comment (on the selection) or a reply; the author's name is asked once

    private fun compose(replyTo: String?) {
        val name = preferences.getString(KEY_NAME, null)
        if (name.isNullOrBlank()) {
            askName { compose(replyTo) }
            return
        }
        showComposeDialog(activity) { dismiss ->
            var text by remember { mutableStateOf(TextFieldValue("")) }
            val mentioned = remember { mutableMapOf<String, String>() }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(if (replyTo == null) Resources.string.editors_comments_add else Resources.string.editors_comments_reply)) },
                text = {
                    MentionField(text, { text = it }, mentioned,
                        if (users.isEmpty()) Resources.string.editors_comments_hint else Resources.string.editors_comments_hint_mentions, minLines = 3, focus = focus)
                },
                confirmButton = {
                    TextButton(onClick = {
                        dismiss()
                        val typed = text.text.trim()
                        if (typed.isNotEmpty()) post(replyTo, typed, mentioned)
                    }) { Text(stringResource(Resources.string.editors_comments_post)) }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } }
            )
        }
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

    private fun askName(onName: () -> Unit) {
        showComposeDialog(activity) { dismiss ->
            var name by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(Resources.string.editors_comments_name)) },
                text = {
                    Column {
                        Text(stringResource(Resources.string.editors_comments_name_message))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val typed = name.trim()
                        if (typed.isEmpty()) return@TextButton
                        dismiss()
                        preferences.edit()
                            .putString(KEY_NAME, typed)
                            .putString(KEY_ID, preferences.getString(KEY_ID, null) ?: java.util.UUID.randomUUID().toString())
                            .apply()
                        onName()
                    }) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } }
            )
        }
    }

    private companion object {
        const val PREFERENCES = "lib.editors.comments"
        const val KEY_NAME = "user_name"
        const val KEY_ID = "user_id"
        const val COUNT_DELAY_MS = 1000L
    }
}
