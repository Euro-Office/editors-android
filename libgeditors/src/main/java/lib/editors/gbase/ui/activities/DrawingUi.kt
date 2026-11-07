package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import lib.editors.base.engine.ChartState
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.gbase.ui.commands.StepperControls
import java.io.File
import java.util.UUID
import lib.editors.resources.R as Resources

/**
 * Inserting pictures, shapes, charts and tables: the toolbar's "Insert" menu (the selected
 * ones are formatted in the format panels, see [ShapePanel]). Picking and taking pictures needs
 * the activity; it calls back through [pickPicture] and [takePhoto].
 */
internal class DrawingUi(
    private val context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val pickPicture: () -> Unit,
    private val takePhoto: () -> Unit,
    private val chartUi: ChartUi,
    private val picker: IconPicker,
    private val tablePicker: PreviewPicker? = null,
    private val insertTextBox: (() -> Unit)? = null,
    /** More items at the end of the menu, which do their actions themselves (documents' breaks, notes, ...). */
    private val moreInsertItems: (Menu) -> Unit = {}
) {

    /** The selected chart, for its own settings in the format panel ([ShapePanel]). */
    var chart: ChartState? = null

    /** Pictures, photos, tables, shapes, text boxes, charts and SmartArt: inserted. */
    fun insertActions(): List<MenuAction> = buildList {
        add(MenuAction(Resources.string.editors_insert_picture, Resources.drawable.ic_editor_image) { pickPicture() })
        add(MenuAction(Resources.string.editors_insert_photo, Resources.drawable.ic_editor_camera) { takePhoto() })
        add(MenuAction(Resources.string.editors_insert_shape, Resources.drawable.ic_editor_shapes) { chooseShape() })
        insertTextBox?.let { insert -> add(MenuAction(Resources.string.editors_text_box, Resources.drawable.ic_editor_text_box) { insert() }) }
        add(MenuAction(Resources.string.editors_chart_insert, Resources.drawable.ic_editor_chart) {
            chartUi.chooseType(Resources.string.editors_chart_insert) { type -> edit { insertChart(type) } }
        })
        if (tablePicker != null) add(MenuAction(Resources.string.editors_table_insert, Resources.drawable.ic_editor_table) { chooseTable() })
        add(MenuAction(Resources.string.editors_smartart_insert, Resources.drawable.ic_editor_smartart) { chartUi.chooseSmartArt() })
    }

    fun showInsertMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.addActions(insertActions())
            moreInsertItems(menu)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setForceShowIcon(true)
        }.show()
    }

    // The table's size above pictures of the styles; a style inserts it
    private fun chooseTable() {
        val picker = tablePicker ?: return
        var cols by mutableIntStateOf(3)
        var rows by mutableIntStateOf(3)
        val header: @Composable () -> Unit = {
            @Composable
            fun Size(label: Int, value: Int, set: (Int) -> Unit) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    StepperControls(stringResource(label), value.toString(), true,
                        { set((value - 1).coerceIn(1, MAX_TABLE_SIZE)) }, { set((value + 1).coerceIn(1, MAX_TABLE_SIZE)) })
                }
            }
            Size(Resources.string.editors_doc_columns, cols) { cols = it }
            Size(Resources.string.editors_doc_rows, rows) { rows = it }
        }
        picker.show(context.getString(Resources.string.editors_table_insert), "tableStyles", header = header) { style ->
            edit { insertTable(cols, rows, style.id) }
        }
    }

    private fun chooseShape() =
        picker.show(Resources.string.editors_insert_shape, InsertCatalog.SHAPES) { preset -> edit { insertShape(preset) } }

    companion object {
        private const val MAX_PICTURE_SIZE = 2048
        private const val MAX_TABLE_SIZE = 20

        /**
         * Copies a picture into [directory] (the document's images), at most 2048 pixels on its
         * longer side and upright, and returns its name and size; null if it can't be read.
         */
        fun importPicture(context: Context, uri: Uri, directory: File): Triple<String, Int, Int>? {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // Only reads the size (decodeStream returns null then)
            (resolver.openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PICTURE_SIZE) sample *= 2
            var bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null

            // Photos are stored as taken, with their orientation in EXIF
            val rotation = resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val scale = minOf(1f, MAX_PICTURE_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height))
            if (rotation != 0f || scale < 1f) {
                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postRotate(rotation)
                }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            // PNG keeps transparency; photos are smaller as JPEG
            val isPng = bounds.outMimeType == "image/png"
            val name = "eo_${UUID.randomUUID()}.${if (isPng) "png" else "jpg"}"
            directory.mkdirs()
            File(directory, name).outputStream().use {
                bitmap.compress(if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)
            }
            return Triple(name, bitmap.width, bitmap.height)
        }
    }
}
