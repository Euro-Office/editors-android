package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.Preview
import lib.editors.gbase.R

/**
 * Pictures the engine draws to choose one from (chart styles, table styles, slide layouts; see
 * [EditorDocument.previews]), in a panel (see [PanelHost]). They are drawn one after the other
 * while the panel is open.
 */
internal class PreviewPicker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val document: () -> EditorDocument?,
    private val panels: PanelHost
) {

    private val density = context.resources.displayMetrics.density

    /**
     * Shows the previews of [kind] under [title], the one with [selected] id highlighted, their
     * names under them if [withNames] ([describe] says each one to TalkBack); [header] goes above
     * them (settings for the choice). Previews of a [section] (null: none) come under its heading.
     * [onPick] gets the chosen one; the panel stays open if [keepOpen], else goes [onBack] to
     * the panel it was opened from, if any.
     */
    fun show(
        title: String, kind: String, selected: String? = null, columnWidthDp: Int = 104, withNames: Boolean = false,
        header: View? = null, keepOpen: Boolean = false, onBack: (() -> Unit)? = null,
        describe: (Preview) -> String = { it.name }, section: (Preview) -> String? = { null }, onPick: (Preview) -> Unit
    ) {
        val document = document() ?: return
        scope.launch {
            val previews = withContext(EditorEngine.dispatcher) { runCatching { document.previews(kind) }.getOrNull() }.orEmpty()
            if (previews.isEmpty()) return@launch
            var drawing: Job? = null
            lateinit var panel: PanelHost.Panel
            val cell = dp(columnWidthDp)
            val columns = ((panels.width - dp(16)) / cell).coerceAtLeast(2)
            val images = mutableListOf<ImageView>()
            val cells = mutableListOf<View>()
            val grids = mutableListOf<View>()
            var grid = GridLayout(context)
            previews.forEachIndexed { index, preview ->
                // A section's heading and grid
                val name = section(preview)
                if (index == 0 || name != section(previews[index - 1])) {
                    if (name != null) {
                        grids += TextView(context).apply {
                            text = name
                            textSize = 14f
                            setPaddingRelative(dp(6), dp(12), 0, dp(4))
                        }
                    }
                    grid = GridLayout(context).apply { columnCount = columns }
                    grids += grid
                }
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                }
                images += image
                grid.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setBackgroundResource(R.drawable.editor_tool_background)
                    isSelected = preview.id == selected
                    contentDescription = describe(preview)
                    TooltipCompat.setTooltipText(this, contentDescription)
                    addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imageHeight(preview, cell - dp(12))))
                    if (withNames) {
                        addView(TextView(context).apply {
                            text = preview.name
                            textSize = 12f
                            maxLines = 2
                            gravity = Gravity.CENTER
                            setPaddingRelative(0, dp(4), 0, 0)
                        })
                    }
                    setOnClickListener {
                        when {
                            keepOpen -> cells.forEach { it.isSelected = it === this }
                            onBack == null -> panel.dismiss()
                        }
                        onPick(preview)
                        if (!keepOpen) onBack?.invoke()
                    }
                    layoutParams = GridLayout.LayoutParams().apply { width = cell }
                }.also { cells += it })
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(dp(8), 0, dp(8), dp(16))
                if (header != null) addView(header)
                grids.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            }
            panel = panels.show(title, content, onBack) { drawing?.cancel() }
            drawing = scope.launch {
                previews.forEachIndexed { index, preview ->
                    val width = cell - dp(12)
                    val bitmap = withContext(EditorEngine.dispatcher) {
                        val picture = Bitmap.createBitmap(width, imageHeight(preview, width).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        // The engine lists the previews of its last call: another picker's may have come since
                        picture.takeIf { runCatching { document.renderPreview(index, it) }.getOrDefault(false) }
                    }
                    if (bitmap != null) images[index].setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun imageHeight(preview: Preview, width: Int) =
        if (preview.widthMm > 0) (width * preview.heightMm / preview.widthMm).toInt() else width

    private fun dp(value: Int) = (value * density).toInt()
}
