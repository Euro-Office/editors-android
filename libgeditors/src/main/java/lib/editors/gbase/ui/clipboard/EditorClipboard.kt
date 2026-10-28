package lib.editors.gbase.ui.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import lib.editors.base.engine.ClipboardContent

/**
 * Copied text goes to the system clipboard as plain text. sdkjs' internal format, which keeps
 * the formatting, stays in the app: pasting uses it while the clipboard still holds the text
 * that was copied with it.
 */
object EditorClipboard {

    private var copied: ClipboardContent? = null

    fun put(context: Context, content: ClipboardContent) {
        copied = content
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, content.text))
    }

    /** The clipboard's text and, if it was copied in this app, its internal format. */
    fun get(context: Context): ClipboardContent? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context).toString()
        val internal = copied?.takeIf { it.text == text }?.internal.orEmpty()
        if (text.isEmpty() && internal.isEmpty()) return null
        return ClipboardContent(text, internal)
    }

    private const val CLIP_LABEL = "document"
}
