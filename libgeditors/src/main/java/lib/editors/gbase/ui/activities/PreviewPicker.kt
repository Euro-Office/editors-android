package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorEngine
import lib.editors.base.engine.Preview
import lib.editors.gbase.ui.commands.PictureCell
import lib.editors.gbase.ui.commands.composePanel

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
        header: (@Composable () -> Unit)? = null, keepOpen: Boolean = false, onBack: (() -> Unit)? = null,
        describe: (Preview) -> String = { it.name }, section: (Preview) -> String? = { null }, onPick: (Preview) -> Unit
    ) {
        val document = document() ?: return
        scope.launch {
            val previews = withContext(EditorEngine.dispatcher) { runCatching { document.previews(kind) }.getOrNull() }.orEmpty()
            if (previews.isEmpty()) return@launch
            var drawing: Job? = null
            lateinit var panel: PanelHost.Panel
            // Drawn as they come
            val images = mutableStateListOf<ImageBitmap?>().apply { repeat(previews.size) { add(null) } }
            var chosen by mutableStateOf(selected)
            val width = dp(columnWidthDp) - dp(12)
            val content = composePanel(context) {
                Column(Modifier.padding(bottom = 16.dp)) {
                    header?.invoke()
                    // A section's heading, then its pictures
                    val sections = previews.indices.groupBy { section(previews[it]) }
                    for ((name, indices) in sections) {
                        if (name != null) {
                            Text(name, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (index in indices) {
                                val preview = previews[index]
                                PictureCell(preview.id == chosen, describe(preview), onClick = {
                                    when {
                                        keepOpen -> chosen = preview.id
                                        onBack == null -> panel.dismiss()
                                    }
                                    onPick(preview)
                                    if (!keepOpen) onBack?.invoke()
                                }, modifier = Modifier.width(columnWidthDp.dp)) {
                                    Column {
                                        val height = with(LocalDensity.current) { imageHeight(preview, width).toDp() }
                                        Box(Modifier.fillMaxWidth().height(height)) {
                                            images[index]?.let { Image(it, null, Modifier.fillMaxWidth().height(height)) }
                                        }
                                        if (withNames) {
                                            Text(preview.name, Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            panel = panels.show(title, content, onBack) { drawing?.cancel() }
            drawing = scope.launch {
                previews.forEachIndexed { index, preview ->
                    val bitmap = withContext(EditorEngine.dispatcher) {
                        val picture = Bitmap.createBitmap(width, imageHeight(preview, width).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        // The engine lists the previews of its last call: another picker's may have come since
                        picture.takeIf { runCatching { document.renderPreview(index, it) }.getOrDefault(false) }
                    }
                    if (bitmap != null) images[index] = bitmap.asImageBitmap()
                }
            }
        }
    }

    private fun imageHeight(preview: Preview, width: Int) =
        if (preview.widthMm > 0) (width * preview.heightMm / preview.widthMm).toInt() else width

    private fun dp(value: Int) = (value * density).toInt()
}
