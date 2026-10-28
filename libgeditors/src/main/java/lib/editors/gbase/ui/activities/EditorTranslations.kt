package lib.editors.gbase.ui.activities

import android.content.Context
import lib.editors.resources.R as Resources

/**
 * Translations (Weblate, in libresources) of what sdkjs writes in English: texts it puts into
 * documents and the names of the built-in styles.
 */
internal object EditorTranslations {

    /** sdkjs' texts by their English text (its translateManager keys). */
    fun sdkjs(context: Context): Map<String, String> = mapOf(
        "Table of Contents" to Resources.string.editors_sdk_table_of_contents,
        "No table of contents entries found." to Resources.string.editors_sdk_no_toc_entries,
        "Header" to Resources.string.editors_sdk_header,
        "Footer" to Resources.string.editors_sdk_footer,
        "First Page " to Resources.string.editors_sdk_first_page,
        "Even Page " to Resources.string.editors_sdk_even_page,
        "Odd Page " to Resources.string.editors_sdk_odd_page,
        "Same as Previous" to Resources.string.editors_sdk_same_as_previous,
        " -Section " to Resources.string.editors_sdk_section,
        "Click to add first slide" to Resources.string.editors_sdk_add_first_slide,
        "Click to add notes" to Resources.string.editors_sdk_add_notes,
        "Your text here" to Resources.string.editors_sdk_your_text_here,
        "Type equation here" to Resources.string.editors_sdk_equation,
        "Choose an item" to Resources.string.editors_sdk_choose_item,
        "Enter a date" to Resources.string.editors_sdk_enter_date,
        "Click to load image" to Resources.string.editors_sdk_load_image,
        "Chart" to Resources.string.editors_sdk_chart,
        "Series" to Resources.string.editors_sdk_series,
        "Caption" to Resources.string.editors_sdk_caption,
        "Picture" to Resources.string.editors_sdk_picture,
        "None" to Resources.string.editors_sdk_none,
        "Error! Reference source not found." to Resources.string.editors_sdk_reference_not_found,
        "Error! Bookmark not defined." to Resources.string.editors_sdk_bookmark_not_defined
    ).mapValues { context.getString(it.value) }

    /** The name to show for a style: translated for the built-in ones, as it is for others. */
    fun styleName(context: Context, name: String): String {
        Regex("Heading ([1-9])").matchEntire(name)?.let { return context.getString(Resources.string.editors_style_heading, it.groupValues[1].toInt()) }
        val resource = when (name) {
            "Normal" -> Resources.string.editors_style_normal
            "No Spacing" -> Resources.string.editors_style_no_spacing
            "Title" -> Resources.string.editors_style_title
            "Subtitle" -> Resources.string.editors_style_subtitle
            "Quote" -> Resources.string.editors_style_quote
            "Intense Quote" -> Resources.string.editors_style_intense_quote
            "List Paragraph" -> Resources.string.editors_style_list_paragraph
            "Caption" -> Resources.string.editors_style_caption
            "footnote text", "Footnote Text" -> Resources.string.editors_style_footnote_text
            else -> return name
        }
        return context.getString(resource)
    }

    /** The name to show for a cell style: translated for the built-in ones, as it is for others. */
    fun cellStyleName(context: Context, name: String): String {
        Regex("([0-9]+)% - Accent([1-6])").matchEntire(name)?.let {
            return context.getString(Resources.string.editors_cell_style_accent_tint, it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        Regex("Accent([1-6])").matchEntire(name)?.let { return context.getString(Resources.string.editors_cell_style_accent, it.groupValues[1].toInt()) }
        if (name.endsWith(" [0]")) {
            val base = name.removeSuffix(" [0]")
            val translated = cellStyleName(context, base)
            return if (translated == base) name else context.getString(Resources.string.editors_cell_style_no_decimals, translated)
        }
        val resource = when (name) {
            "Good" -> Resources.string.editors_cell_style_good
            "Bad" -> Resources.string.editors_cell_style_bad
            "Neutral" -> Resources.string.editors_cell_style_neutral
            "Input" -> Resources.string.editors_cell_style_input
            "Output" -> Resources.string.editors_cell_style_output
            "Calculation" -> Resources.string.editors_cell_style_calculation
            "Check Cell" -> Resources.string.editors_cell_style_check_cell
            "Explanatory Text" -> Resources.string.editors_cell_style_explanatory_text
            "Note" -> Resources.string.editors_cell_style_note
            "Linked Cell" -> Resources.string.editors_cell_style_linked_cell
            "Warning Text" -> Resources.string.editors_cell_style_warning_text
            "Total" -> Resources.string.editors_cell_style_total
            "Comma" -> Resources.string.editors_cell_style_comma
            "Currency" -> Resources.string.editors_cells_format_currency
            "Percent" -> Resources.string.editors_cells_format_percent
            // As paragraph styles: Normal, Title, Heading 1-4
            else -> return styleName(context, name)
        }
        return context.getString(resource)
    }
}
