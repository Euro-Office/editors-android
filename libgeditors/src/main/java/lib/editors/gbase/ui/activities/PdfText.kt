package lib.editors.gbase.ui.activities

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.PageLink
import lib.editors.base.engine.PageText
import lib.editors.base.engine.PagedDocument
import lib.editors.base.engine.SearchState
import lib.editors.base.engine.SelectionArea
import lib.editors.base.engine.ToolbarState
import lib.editors.base.engine.TouchAction
import lib.editors.gbase.ui.views.DocumentView

/**
 * PDF, XPS and DjVu files: selecting and copying text, finding it and the links in it, from the
 * text of the pages ([PagedDocument.pageText]). The selection goes to the view as an [EditorState].
 */
internal class PdfText(
    private val scope: CoroutineScope,
    private val document: PagedDocument,
    private val pageCount: Int,
    private val onState: (EditorState) -> Unit,
    /** A tap on a link (its address) or elsewhere (null). */
    private val onLink: (String?) -> Unit,
    private val onCopy: (String) -> Unit
) : DocumentView.EditListener, SearchBar.Target {

    private class Position(val page: Int, val index: Int) : Comparable<Position> {
        override fun compareTo(other: Position) = compareValuesBy(this, other, { it.page }, { it.index })
    }

    private class Match(val page: Int, val start: Int, val end: Int)

    // Pages are read on the engine's thread, one command at a time, in order
    private val mutex = Mutex()
    private val texts = HashMap<Int, PageText>()
    private val links = HashMap<Int, List<PageLink>>()

    // The selection (start and end characters, inclusive) and, while extending it by words, the first word
    private var selectionStart: Position? = null
    private var selectionEnd: Position? = null
    private var anchorStart: Position? = null
    private var anchorEnd: Position? = null

    private var searchKey: String? = null
    private var matches: List<Match> = emptyList()
    private var current = -1
    private var search: SearchState? = null

    // After the view's touch handling (not immediately), so that it can ask for the menu first
    private fun run(block: suspend () -> Unit) {
        scope.launch(Dispatchers.Main) { mutex.withLock { block() } }
    }

    private suspend fun text(page: Int): PageText? {
        if (page !in 0 until pageCount) return null
        texts[page]?.let { return it }
        val text = withContext(EditorEngine.dispatcher) { document.pageText(page) } ?: return null
        texts[page] = text
        return text
    }

    private suspend fun linksOf(page: Int): List<PageLink> {
        if (page !in 0 until pageCount) return emptyList()
        return links.getOrPut(page) { withContext(EditorEngine.dispatcher) { document.links(page) } }
    }

    // ---------------------------------------------------------------------------------
    // Selection

    override fun onTouch(action: TouchAction, page: Int, xMm: Double, yMm: Double, clickCount: Int, addToSelection: Boolean) = run {
        if (clickCount == 1) {
            // A tap: clears the selection; a link shows its address
            if (action != TouchAction.UP) return@run
            select(null, null)
            onLink(linksOf(page).firstOrNull { it.contains(xMm.toFloat(), yMm.toFloat()) }?.url)
            return@run
        }
        // Long press: the word, then extended by words while dragging
        val text = text(page) ?: return@run
        val index = text.indexAt(xMm.toFloat(), yMm.toFloat())
        if (index < 0) return@run
        val (start, end) = wordAt(text, index)
        when (action) {
            TouchAction.DOWN -> {
                anchorStart = Position(page, start)
                anchorEnd = Position(page, end)
                select(anchorStart, anchorEnd)
            }
            TouchAction.MOVE -> {
                val first = anchorStart ?: return@run
                val last = anchorEnd ?: return@run
                select(minOf(first, Position(page, start)), maxOf(last, Position(page, end)))
            }
            // Again, for the view to show the menu for it
            TouchAction.UP -> publish()
        }
    }

    // Selection handles: from the fixed one to the dragged one
    override fun onSelect(startPage: Int, startXMm: Double, startYMm: Double, endPage: Int, endXMm: Double, endYMm: Double) = run {
        val startIndex = text(startPage)?.indexAt(startXMm.toFloat(), startYMm.toFloat()) ?: -1
        val endIndex = text(endPage)?.indexAt(endXMm.toFloat(), endYMm.toFloat()) ?: -1
        if (startIndex < 0 || endIndex < 0) return@run
        val a = Position(startPage, startIndex)
        val b = Position(endPage, endIndex)
        select(minOf(a, b), maxOf(a, b))
    }

    override fun onCopy() = run {
        val start = selectionStart ?: return@run
        val end = selectionEnd ?: return@run
        val result = StringBuilder()
        for (page in start.page..end.page) {
            val text = text(page)?.text ?: continue
            val from = if (page == start.page) start.index else 0
            val to = if (page == end.page) end.index else text.length - 1
            if (page != start.page) result.append('\n')
            if (from <= to && to < text.length) result.append(text, from, to + 1)
        }
        onCopy(result.toString())
    }

    override fun onKey(keyCode: Int, shift: Boolean, ctrl: Boolean) = Unit
    override fun onCommitText(text: String) = Unit
    override fun onComposingText(text: String) = Unit
    override fun onFinishComposingText() = Unit
    override fun onCut() = Unit
    override fun onPaste() = Unit
    override fun onSelectAll() = Unit

    // The word (not spaces) around the character
    private fun wordAt(text: PageText, index: Int): Pair<Int, Int> {
        val chars = text.text
        if (chars[index].isWhitespace()) return index to index
        var start = index
        var end = index
        while (start > 0 && !chars[start - 1].isWhitespace()) start--
        while (end < chars.length - 1 && !chars[end + 1].isWhitespace()) end++
        return start to end
    }

    private suspend fun select(start: Position?, end: Position?) {
        selectionStart = start
        selectionEnd = end
        publish()
    }

    // The selection as areas, one per line
    private suspend fun publish() {
        val areas = ArrayList<SelectionArea>()
        val start = selectionStart
        val end = selectionEnd
        if (start != null && end != null) {
            for (page in start.page..end.page) {
                val text = text(page) ?: continue
                val from = if (page == start.page) start.index else 0
                val to = if (page == end.page) end.index else text.text.length - 1
                areas += lineAreas(page, text, from, to)
            }
        }
        onState(
            EditorState(
                cursor = null, selection = areas, drawings = emptyList(), dirtyPages = emptySet(),
                layoutChanged = false, layoutPending = false, currentPage = -1, modified = false,
                toolbar = ToolbarState(), search = search
            )
        )
    }

    private fun lineAreas(page: Int, text: PageText, from: Int, to: Int): List<SelectionArea> {
        val areas = ArrayList<SelectionArea>()
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        var open = false
        fun close() {
            if (open) areas += SelectionArea(page, doubleArrayOf(
                left.toDouble(), top.toDouble(), right.toDouble(), top.toDouble(),
                right.toDouble(), bottom.toDouble(), left.toDouble(), bottom.toDouble()
            ))
            open = false
        }
        for (i in from..minOf(to, text.text.length - 1)) {
            if (text.text[i] == '\n') {
                close()
                continue
            }
            if (text.right(i) <= text.left(i)) continue
            // A character beside the line, not on it, starts another one
            if (open && (text.top(i) >= bottom || text.bottom(i) <= top || text.left(i) < left)) close()
            if (!open) {
                left = text.left(i)
                top = text.top(i)
                right = text.right(i)
                bottom = text.bottom(i)
                open = true
            } else {
                right = maxOf(right, text.right(i))
                top = minOf(top, text.top(i))
                bottom = maxOf(bottom, text.bottom(i))
            }
        }
        close()
        return areas
    }

    // ---------------------------------------------------------------------------------
    // Find

    override val canReplace = false

    override fun find(text: String, matchCase: Boolean, wholeWords: Boolean, forward: Boolean) = run {
        val key = "$matchCase $wholeWords $text"
        if (key != searchKey) {
            searchKey = key
            matches = findAll(text, matchCase, wholeWords)
            current = if (matches.isEmpty()) -1 else if (forward) 0 else matches.size - 1
        } else if (matches.isNotEmpty()) {
            current = (current + if (forward) 1 else -1).mod(matches.size)
        }
        search = SearchState(matches.size, current, -1)
        val match = matches.getOrNull(current)
        if (match == null) {
            select(null, null)
        } else {
            select(Position(match.page, match.start), Position(match.page, match.end))
        }
    }

    private suspend fun findAll(query: String, matchCase: Boolean, wholeWords: Boolean): List<Match> {
        val result = ArrayList<Match>()
        for (page in 0 until pageCount) {
            // Lines are joined by spaces, so that words across them are found
            val content = text(page)?.text?.replace('\n', ' ') ?: continue
            var index = content.indexOf(query, 0, ignoreCase = !matchCase)
            while (index >= 0) {
                val end = index + query.length
                val isWhole = (index == 0 || !content[index - 1].isLetterOrDigit()) &&
                    (end == content.length || !content[end].isLetterOrDigit())
                if (!wholeWords || isWhole) result += Match(page, index, end - 1)
                index = content.indexOf(query, index + 1, ignoreCase = !matchCase)
            }
        }
        return result
    }

    override fun replace(text: String, replacement: String, matchCase: Boolean, wholeWords: Boolean, all: Boolean) = Unit

    override fun close() = run {
        searchKey = null
        matches = emptyList()
        current = -1
        search = null
        select(null, null)
    }
}
