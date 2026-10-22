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
     * names under them if [withNames]; [header] goes above them (settings for the choice).
     * [onPick] gets the chosen one; the panel stays open if [keepOpen].
     */
    fun show(
        title: String, kind: String, selected: String? = null, columnWidthDp: Int = 104, withNames: Boolean = false,
        header: View? = null, keepOpen: Boolean = false, onPick: (Preview) -> Unit
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
            val grid = GridLayout(context).apply { columnCount = columns }
            for (preview in previews) {
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
                    contentDescription = preview.name
                    TooltipCompat.setTooltipText(this, preview.name)
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
                        if (!keepOpen) panel.dismiss()
                        else for (i in 0 until grid.childCount) grid.getChildAt(i).isSelected = grid.getChildAt(i) === this
                        onPick(preview)
                    }
                    layoutParams = GridLayout.LayoutParams().apply { width = cell }
                })
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(dp(8), 0, dp(8), dp(16))
                if (header != null) addView(header)
                addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            panel = panels.show(title, content) { drawing?.cancel() }
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
