package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.ColorSchemes
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.EditorState
import lib.editors.base.engine.PageSize
import lib.editors.base.engine.SheetSetup
import lib.editors.gbase.R
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
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(0, 0, 0, dp(16))
        }
        found.schemes.forEachIndexed { index, scheme ->
            val current = index == found.current
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPaddingRelative(dp(16), 0, dp(12), 0)
                setBackgroundResource(R.drawable.editor_tool_background)
                isSelected = current
                contentDescription = scheme.name
                // The accents, as web-apps shows a scheme
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    for (color in scheme.colors.drop(ACCENTS_FROM).take(ACCENTS)) {
                        addView(View(context).apply {
                            background = GradientDrawable().apply {
                                cornerRadius = dp(3).toFloat()
                                setColor(android.graphics.Color.parseColor("#$color"))
                            }
                        }, LinearLayout.LayoutParams(dp(14), dp(22)).apply { marginEnd = dp(2) })
                    }
                })
                addView(TextView(context).apply {
                    text = scheme.name
                    textSize = 16f
                    setPaddingRelative(dp(12), 0, 0, 0)
                    if (current) {
                        setTextColor(ContextCompat.getColor(context, Resources.color.editors_accent))
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (current) addView(ImageView(context).apply { setImageResource(Resources.drawable.ic_editor_check) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                setOnClickListener {
                    if (!current) edit { setColorScheme(index) }
                    onBack()
                }
            })
        }
        panels.show(context.getString(Resources.string.editors_color_scheme), list, onBack) {}
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
