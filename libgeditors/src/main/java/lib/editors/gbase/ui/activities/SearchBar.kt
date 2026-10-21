package lib.editors.gbase.ui.activities

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import lib.editors.base.engine.SearchState
import lib.editors.gbase.R
import lib.editors.resources.R as Resources

/**
 * Find and replace, above the document: the text to find, the matches, previous and next, and
 * (behind the options button) match case, whole words and, if the document can be edited, the
 * replacement. Results come back through [update].
 */
internal class SearchBar(private val context: Context, private val target: Target) {

    interface Target {
        val canReplace: Boolean
        fun find(text: String, matchCase: Boolean, wholeWords: Boolean, forward: Boolean)
        fun replace(text: String, replacement: String, matchCase: Boolean, wholeWords: Boolean, all: Boolean)
        fun close()
    }

    private val density = context.resources.displayMetrics.density
    private var matchCase = false
    private var wholeWords = false
    // The search the results are for; typing starts a new one
    private var searched: String? = null

    private lateinit var findText: EditText
    private lateinit var replaceText: EditText
    private lateinit var counter: TextView
    private lateinit var options: View
    private lateinit var matchCaseButton: TextView
    private lateinit var wholeWordsButton: TextView
    private val replaceViews = mutableListOf<View>()

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // As the toolbar
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, background, true)
        setBackgroundColor(background.data)
        elevation = 4 * density
        isVisible = false
        addView(mainRow())
        options = optionsRow()
        addView(options)
    }

    val isShown get() = view.isVisible

    fun show() {
        // Replacing only while the document can be edited
        replaceViews.forEach { it.isVisible = target.canReplace }
        view.isVisible = true
        findText.requestFocus()
        findText.selectAll()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(findText, 0)
    }

    fun hide() {
        if (!view.isVisible) return
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        view.isVisible = false
        searched = null
        counter.text = ""
        target.close()
    }

    /** The matches after a search; null when there is none. */
    fun update(search: SearchState?) {
        if (search == null || searched == null) {
            counter.text = ""
            return
        }
        counter.text = when {
            search.replaced >= 0 -> context.getString(Resources.string.editors_search_replaced, search.replaced)
            search.count == 0 -> context.getString(Resources.string.editors_search_none)
            search.current >= 0 -> context.getString(Resources.string.editors_search_count, search.current + 1, search.count)
            else -> search.count.toString()
        }
    }

    private fun find(forward: Boolean) {
        val text = findText.text.toString()
        if (text.isEmpty()) return
        searched = text
        target.find(text, matchCase, wholeWords, forward)
    }

    private fun replace(all: Boolean) {
        val text = findText.text.toString()
        if (text.isEmpty()) return
        searched = text
        target.replace(text, replaceText.text.toString(), matchCase, wholeWords, all)
    }

    // ---------------------------------------------------------------------------------
    // Views

    private fun dp(value: Int) = (value * density).toInt()

    private fun mainRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, 0, 0)
        findText = field(Resources.string.editors_search_find).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, event ->
                // Hardware Enter comes as a key down and up and may follow as an action: only the down counts
                val isAction = event == null && actionId == EditorInfo.IME_ACTION_SEARCH
                val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (isAction || isEnter) find(forward = true)
                isAction || event?.keyCode == KeyEvent.KEYCODE_ENTER
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (s.toString() != searched) {
                        searched = null
                        counter.text = ""
                    }
                }
            })
        }
        addView(findText)
        counter = TextView(context).apply {
            setPadding(dp(4), 0, dp(4), 0)
        }
        addView(counter)
        addView(iconButton(Resources.drawable.ic_editor_up, Resources.string.editors_search_previous) { find(forward = false) })
        addView(iconButton(Resources.drawable.ic_editor_down, Resources.string.editors_search_next) { find(forward = true) })
        addView(iconButton(Resources.drawable.ic_editor_find_replace, Resources.string.editors_search_options) {
            options.isVisible = !options.isVisible
        })
        addView(iconButton(Resources.drawable.ic_editor_close, Resources.string.editors_search_close) { hide() })
    }

    private fun optionsRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), dp(4))
        isVisible = false
        matchCaseButton = textButton(context.getString(Resources.string.editors_search_match_case)) {
            matchCase = !matchCase
            matchCaseButton.isSelected = matchCase
            searched = null
        }
        addView(matchCaseButton)
        wholeWordsButton = textButton(context.getString(Resources.string.editors_search_whole_words)) {
            wholeWords = !wholeWords
            wholeWordsButton.isSelected = wholeWords
            searched = null
        }
        addView(wholeWordsButton)
        replaceText = field(Resources.string.editors_search_replace_with)
        addView(replaceText)
        replaceViews += replaceText
        replaceViews += textButton(context.getString(Resources.string.editors_search_replace)) { replace(all = false) }.also { addView(it) }
        replaceViews += textButton(context.getString(Resources.string.editors_search_replace_all)) { replace(all = true) }.also { addView(it) }
    }

    private fun field(hint: Int) = EditText(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setHint(hint)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        textSize = 16f
    }

    private fun iconButton(icon: Int, description: Int, onClick: () -> Unit) = ImageButton(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(48))
        setImageResource(icon)
        contentDescription = context.getString(description)
        setPadding(dp(10), dp(12), dp(10), dp(12))
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setBackgroundResource(R.drawable.editor_tool_background)
        setOnClickListener { onClick() }
    }

    private fun textButton(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 14f
        minWidth = dp(40)
        minHeight = dp(40)
        setPadding(dp(8), 0, dp(8), 0)
        setBackgroundResource(R.drawable.editor_tool_background)
        setOnClickListener { onClick() }
    }
}
