package lib.editors.gbase.ui.activities

import android.content.Context
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.ColorSchemes
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.PageSize
import lib.editors.base.engine.SheetSetup
import lib.editors.gbase.ui.commands.composePanel
import kotlin.math.abs
import lib.editors.resources.R as Resources

/**
 * Page setup of presentations (slide size) and spreadsheets (gridlines, the pages' orientation
 * and size), in panels as web-apps' mobile settings, and the color schemes of all editors.
 * Choices are applied as they are made.
 */
internal class PageSettingsUi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val panels: PanelHost,
    private val edit: (EditorDocument.() -> EditorState) -> Unit
) {

    private val density = context.resources.displayMetrics.density

    /** The color schemes, each with its name and accent colors, the current one marked; then [onBack]. */
    fun chooseColorScheme(onBack: () -> Unit) = read({ colorSchemes() }) { found -> showColorSchemes(found, onBack) }

    private fun showColorSchemes(found: ColorSchemes, onBack: () -> Unit) {
        val content = composePanel(context) {
            Column(Modifier.padding(bottom = 16.dp)) {
                found.schemes.forEachIndexed { index, scheme ->
                    val current = index == found.current
                    ListItem(
                        headlineContent = { Text(scheme.name) },
                        leadingContent = { RadioButton(selected = current, onClick = null) },
                        // The accents, as web-apps shows a scheme
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                for (color in scheme.colors.drop(ACCENTS_FROM).take(ACCENTS)) {
                                    Box(Modifier.size(width = 12.dp, height = 22.dp).clip(RoundedCornerShape(3.dp)).background(Color(android.graphics.Color.parseColor("#$color"))))
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(selected = current, role = Role.RadioButton) {
                            if (!current) edit { setColorScheme(index) }
                            onBack()
                        }
                    )
                }
            }
        }
        panels.show(context.getString(Resources.string.editors_color_scheme), content, onBack) {}
    }

    /** Presentations: the slides' size and the color scheme. */
    fun showSlides() = read({ slideSize() }) { size -> SlidesPanel(size).show() }

    /** Spreadsheets: the active sheet's gridlines, its pages' orientation and size, and the color scheme. */
    fun showSheet() = read({ sheetSetup() }) { setup -> SheetPanel(setup).show() }

    private inner class SlidesPanel(private val size: PageSize) : PanelBuilder<PageSize>(context) {
        fun show() {
            val content = column {
                caption(Resources.string.editors_slide_size)
                scrollRow {
                    choice("16:9", { abs(it.widthMm / it.heightMm - 16.0 / 9) < 0.01 }) { change { setSlideSize(wide = true) } }
                    choice("4:3", { abs(it.widthMm / it.heightMm - 4.0 / 3) < 0.01 }) { change { setSlideSize(wide = false) } }
                }
                action(Resources.string.editors_color_scheme) { chooseColorScheme { showSlides() } }
            }
            panels.show(context.getString(Resources.string.editors_doc_page_setup), content) {}
            updaters.forEach { it(size) }
        }

        private fun change(command: EditorDocument.() -> EditorState) {
            edit(command)
            read({ slideSize() }) { changed -> updaters.forEach { it(changed) } }
        }
    }

    private inner class SheetPanel(private val setup: SheetSetup) : PanelBuilder<SheetSetup>(context) {
        fun show() {
            val content = column {
                caption(Resources.string.editors_cells_view)
                scrollRow {
                    choice(context.getString(Resources.string.editors_cells_gridlines), { it.gridlines }, Resources.drawable.ic_editor_border_all) {
                        change { setGridlines(!sheetSetup().gridlines) }
                    }
                }
                caption(Resources.string.editors_doc_orientation)
                scrollRow {
                    choice(context.getString(Resources.string.editors_doc_portrait), { !it.landscape }, Resources.drawable.ic_editor_page_portrait) {
                        change { setSheetPage(landscape = false) }
                    }
                    choice(context.getString(Resources.string.editors_doc_landscape), { it.landscape }, Resources.drawable.ic_editor_page_landscape) {
                        change { setSheetPage(landscape = true) }
                    }
                }
                caption(Resources.string.editors_doc_size)
                scrollRow {
                    for ((name, width, height) in PAPER_SIZES) {
                        choice(name, { abs(minOf(it.width, it.height) - width) < 1 && abs(maxOf(it.width, it.height) - height) < 1 }) {
                            change { setSheetPage(width = width, height = height) }
                        }
                    }
                }
                action(Resources.string.editors_color_scheme) { chooseColorScheme { showSheet() } }
            }
            panels.show(context.getString(Resources.string.editors_doc_page_setup), content) {}
            updaters.forEach { it(setup) }
        }

        private fun change(command: EditorDocument.() -> EditorState) {
            edit(command)
            read({ sheetSetup() }) { changed -> updaters.forEach { it(changed) } }
        }
    }

    private fun column(build: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(16), dp(8), dp(16), dp(24))
        build()
    }

    // Reads from the document on the engine's thread, then shows the result
    private fun <T> read(query: EditorDocument.() -> T, onResult: (T) -> Unit) {
        val document = document() ?: return
        scope.launch {
            val result = withContext(EditorEngine.dispatcher) { runCatching { document.query() }.getOrNull() } ?: return@launch
            onResult(result)
        }
    }

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        // Name, width and height (portrait) in mm
        val PAPER_SIZES = listOf(
            Triple("A4", 210.0, 297.0),
            Triple("A5", 148.0, 210.0),
            Triple("Letter", 215.9, 279.4),
            Triple("Legal", 215.9, 355.6)
        )
        // A scheme's accents 1 to 6 (after dark 1, light 1, dark 2, light 2)
        const val ACCENTS_FROM = 4
        const val ACCENTS = 6
    }
}
