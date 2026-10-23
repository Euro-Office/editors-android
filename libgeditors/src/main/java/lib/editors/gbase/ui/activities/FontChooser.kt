package lib.editors.gbase.ui.activities

import android.content.Context
import lib.editors.resources.R as Resources

/**
 * Choosing a font in a panel (see [PanelHost.choose]): a field to find one, the fonts the
 * document uses and the ones chosen last (in any document) first, then all of them.
 */
internal class FontChooser(private val context: Context, private val panels: PanelHost) {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * [installed] the fonts there are, [documentFonts] the document's, [current] the text's;
     * then [onBack]. The document's fonts may not be installed (others are drawn for them, as
     * Liberation Sans for Arial): they are listed too, so that they can be chosen.
     */
    fun choose(installed: List<String>, documentFonts: List<String>, current: String, onBack: () -> Unit, onFont: (String) -> Unit) {
        if (installed.isEmpty()) return
        val recent = recent()
        val fonts = (installed + documentFonts + recent + listOf(current).filter { it.isNotEmpty() })
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val index = fonts.withIndex().associate { it.value to it.index }
        fun indices(names: List<String>) = names.mapNotNull { index[it] }
        panels.choose(
            context.getString(Resources.string.editors_format_font), fonts, fonts.indexOf(current), onBack,
            searchHint = context.getString(Resources.string.editors_format_font_find),
            sections = listOf(
                PanelHost.Section(context.getString(Resources.string.editors_format_fonts_document), indices(documentFonts)),
                PanelHost.Section(context.getString(Resources.string.editors_format_fonts_recent), indices(recent))
            ),
            allTitle = context.getString(Resources.string.editors_format_fonts_all)
        ) { chosen ->
            val name = fonts[chosen]
            preferences.edit().putString(RECENT, (listOf(name) + recent.filter { it != name }).take(MAX_RECENT).joinToString("\n")).apply()
            onFont(name)
        }
    }

    private fun recent(): List<String> = preferences.getString(RECENT, null)?.split("\n")?.filter { it.isNotEmpty() }.orEmpty()

    private companion object {
        const val PREFERENCES = "editors_fonts"
        const val RECENT = "recent"
        const val MAX_RECENT = 5
    }
}
