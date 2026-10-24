package lib.editors.gbase.ui.activities

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import lib.editors.base.engine.CsvDelimiter
import lib.editors.base.engine.TextEncoding
import lib.editors.base.engine.TextOptions
import java.io.File
import lib.editors.resources.R as Resources

/**
 * The encoding (and a csv's delimiter) to read a plain-text file with, as the web editors ask
 * for when opening one, with a preview of its start. [onChosen] gets the options to open it
 * again with; [discards] says that this loses unsaved changes.
 */
internal class TextOptionsDialog(
    private val activity: Activity,
    private val file: File,
    private val current: TextOptions,
    private val discards: Boolean,
    private val onChosen: (TextOptions) -> Unit
) {
    private val isCsv = current.delimiter >= 0
    private val start: ByteArray = runCatching {
        file.inputStream().use { input -> ByteArray(PREVIEW_BYTES).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
    }.getOrDefault(ByteArray(0))

    fun show() {
        val density = activity.resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(padding, (8 * density).toInt(), padding, 0)
        }
        fun label(text: Int) = TextView(activity).apply {
            setText(text)
            setPaddingRelative(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }

        val encodings = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, TextEncoding.ALL.map { it.label })
            setSelection(TextEncoding.ALL.indexOfFirst { it.index == current.encoding }.coerceAtLeast(0))
        }
        layout.addView(label(Resources.string.editors_text_encoding))
        layout.addView(encodings)

        val delimiterLabels = mapOf(
            CsvDelimiter.COMMA to Resources.string.editors_text_delimiter_comma,
            CsvDelimiter.SEMICOLON to Resources.string.editors_text_delimiter_semicolon,
            CsvDelimiter.TAB to Resources.string.editors_text_delimiter_tab,
            CsvDelimiter.COLON to Resources.string.editors_text_delimiter_colon,
            CsvDelimiter.SPACE to Resources.string.editors_text_delimiter_space
        )
        val delimiters = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, CsvDelimiter.entries.map { activity.getString(delimiterLabels.getValue(it)) })
            setSelection(CsvDelimiter.entries.indexOf(CsvDelimiter.of(current.delimiter) ?: CsvDelimiter.COMMA))
        }
        if (isCsv) {
            layout.addView(label(Resources.string.editors_text_delimiter))
            layout.addView(delimiters)
        }

        val preview = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            maxLines = PREVIEW_LINES
            setPaddingRelative((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(activity.getColor(Resources.color.editors_document_background))
        }
        layout.addView(label(Resources.string.editors_text_preview))
        layout.addView(preview)
        if (discards) layout.addView(label(Resources.string.editors_text_discards))

        fun chosen() = TextOptions(
            TextEncoding.ALL[encodings.selectedItemPosition].index,
            if (isCsv) CsvDelimiter.entries[delimiters.selectedItemPosition].value else -1
        )
        fun updatePreview() {
            preview.text = previewText(TextEncoding.ALL[encodings.selectedItemPosition], CsvDelimiter.entries[delimiters.selectedItemPosition])
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updatePreview()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        encodings.onItemSelectedListener = listener
        delimiters.onItemSelectedListener = listener
        updatePreview()

        AlertDialog.Builder(activity)
            .setTitle(if (isCsv) Resources.string.editors_text_options_csv else Resources.string.editors_text_options)
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton(Resources.string.editors_text_reopen) { _, _ ->
                if (chosen() != current) onChosen(chosen())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // The file's first lines read with encoding; a csv's cells apart (quotes aren't considered)
    private fun previewText(encoding: TextEncoding, delimiter: CsvDelimiter): String {
        val charset = encoding.charset ?: return ""
        val text = String(start, charset).removePrefix(BYTE_ORDER_MARK).lines().take(PREVIEW_LINES).joinToString("\n")
        return if (isCsv) text.replace(delimiter.char.toString(), CELL_SEPARATOR) else text
    }

    private companion object {
        const val PREVIEW_BYTES = 4096
        const val PREVIEW_LINES = 8
        const val CELL_SEPARATOR = " │ "
        const val BYTE_ORDER_MARK = "\uFEFF"
    }
}
