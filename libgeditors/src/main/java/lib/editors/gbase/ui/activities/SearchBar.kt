package lib.editors.gbase.ui.activities

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import lib.editors.base.engine.SearchState
import lib.editors.gbase.ui.commands.EditorTheme
import lib.editors.resources.R as Resources

/**
 * Find and replace, above the document (Compose Material 3): the text to find, the matches,
 * previous and next, and (behind the options button) match case, whole words and, if the document
 * can be edited, the replacement. Results come back through [update].
 */
internal class SearchBar(private val context: Context, private val target: Target) {

    interface Target {
        val canReplace: Boolean
        fun find(text: String, matchCase: Boolean, wholeWords: Boolean, forward: Boolean)
        fun replace(text: String, replacement: String, matchCase: Boolean, wholeWords: Boolean, all: Boolean)
        fun close()
    }

    private var findText by mutableStateOf(TextFieldValue(""))
    private var replaceText by mutableStateOf("")
    private var counter by mutableStateOf("")
    private var showsOptions by mutableStateOf(false)
    private var matchCase by mutableStateOf(false)
    private var wholeWords by mutableStateOf(false)
    private var canReplace by mutableStateOf(false)
    // Each showing focuses the field anew
    private var shown by mutableIntStateOf(0)
    // The search the results are for; typing starts a new one
    private var searched: String? = null

    val view: View = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        isVisible = false
        setContent { EditorTheme { Bar() } }
    }

    val isShown get() = view.isVisible

    fun show() {
        // Replacing only while the document can be edited
        canReplace = target.canReplace
        view.isVisible = true
        // The text found before, selected: typing replaces it
        findText = findText.copy(selection = TextRange(0, findText.text.length))
        shown++
    }

    fun hide() {
        if (!view.isVisible) return
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        view.isVisible = false
        searched = null
        counter = ""
        target.close()
    }

    /** The matches after a search; null when there is none. */
    fun update(search: SearchState?) {
        if (search == null || searched == null) {
            counter = ""
            return
        }
        counter = when {
            search.replaced >= 0 -> context.getString(Resources.string.editors_search_replaced, search.replaced)
            search.count == 0 -> context.getString(Resources.string.editors_search_none)
            search.current >= 0 -> context.getString(Resources.string.editors_search_count, search.current + 1, search.count)
            else -> search.count.toString()
        }
    }

    private fun find(forward: Boolean) {
        val text = findText.text
        if (text.isEmpty()) return
        searched = text
        target.find(text, matchCase, wholeWords, forward)
    }

    private fun replace(all: Boolean) {
        val text = findText.text
        if (text.isEmpty()) return
        searched = text
        target.replace(text, replaceText, matchCase, wholeWords, all)
    }

    // A changed search isn't the one the results are for
    private fun changed() {
        searched = null
        counter = ""
    }

    // ---------------------------------------------------------------------------------

    @Composable
    private fun Bar() {
        val title = stringResource(Resources.string.editors_search)
        val focus = remember { FocusRequester() }
        LaunchedEffect(shown) {
            if (shown > 0) focus.requestFocus()
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.semantics { paneTitle = title }) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = findText,
                        onValueChange = {
                            if (it.text != findText.text) changed()
                            findText = it
                        },
                        placeholder = { Text(stringResource(Resources.string.editors_search_find)) },
                        leadingIcon = { Icon(painterResource(Resources.drawable.ic_editor_search), null) },
                        suffix = if (counter.isNotEmpty()) {
                            { Text(counter, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
                        } else null,
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { find(forward = true) }),
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp).focusRequester(focus)
                            // Hardware Enter finds the next one
                            .onPreviewKeyEvent { event ->
                                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                                if (enter && event.type == KeyEventType.KeyDown) find(forward = true)
                                enter
                            }
                    )
                    IconButton(onClick = { find(forward = false) }) {
                        Icon(painterResource(Resources.drawable.ic_editor_up), stringResource(Resources.string.editors_search_previous))
                    }
                    IconButton(onClick = { find(forward = true) }) {
                        Icon(painterResource(Resources.drawable.ic_editor_down), stringResource(Resources.string.editors_search_next))
                    }
                    IconToggleButton(checked = showsOptions, onCheckedChange = { showsOptions = it }) {
                        Icon(painterResource(Resources.drawable.ic_editor_find_replace), stringResource(Resources.string.editors_search_options))
                    }
                    IconButton(onClick = ::hide) {
                        Icon(painterResource(Resources.drawable.ic_editor_close), stringResource(Resources.string.editors_search_close))
                    }
                }
                AnimatedVisibility(showsOptions) { Options() }
            }
        }
    }

    @Composable
    private fun Options() {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = matchCase, onClick = {
                    matchCase = !matchCase
                    changed()
                }, label = { Text(stringResource(Resources.string.editors_search_match_case)) })
                FilterChip(selected = wholeWords, onClick = {
                    wholeWords = !wholeWords
                    changed()
                }, label = { Text(stringResource(Resources.string.editors_search_whole_words)) })
            }
            if (canReplace) {
                OutlinedTextField(
                    value = replaceText,
                    onValueChange = { replaceText = it },
                    placeholder = { Text(stringResource(Resources.string.editors_search_replace_with)) },
                    leadingIcon = { Icon(painterResource(Resources.drawable.ic_editor_find_replace), null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { replace(all = false) }) { Text(stringResource(Resources.string.editors_search_replace)) }
                    TextButton(onClick = { replace(all = true) }) { Text(stringResource(Resources.string.editors_search_replace_all)) }
                }
            }
        }
    }
}
