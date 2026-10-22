package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import lib.editors.base.engine.ChartState
import lib.editors.base.engine.DrawingOrder
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.resources.R as Resources
import java.io.File
import java.util.UUID

/**
 * Inserting pictures and shapes and formatting the selected ones: the toolbar's "Insert" and
 * "Shape" menus. Picking and taking pictures needs the activity; it calls back through
 * [pickPicture] and [takePhoto].
 */
internal class DrawingUi(
    private val context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val pickPicture: () -> Unit,
    private val takePhoto: () -> Unit,
    private val chartUi: ChartUi,
    private val picker: IconPicker,
    private val insertTextBox: (() -> Unit)? = null
) {

    /** The selected chart, for its own entries in the shape menu. */
    var chart: ChartState? = null

    private class Choice(val label: Int, val value: String)

    fun showInsertMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 0, 0, Resources.string.editors_insert_picture).setIcon(Resources.drawable.ic_editor_image)
            menu.add(0, 1, 1, Resources.string.editors_insert_photo).setIcon(Resources.drawable.ic_editor_camera)
            menu.add(0, 2, 2, Resources.string.editors_insert_shape).setIcon(Resources.drawable.ic_editor_shapes)
            menu.add(0, 3, 3, Resources.string.editors_chart_insert).setIcon(Resources.drawable.ic_editor_chart)
            menu.add(0, 4, 4, Resources.string.editors_smartart_insert).setIcon(Resources.drawable.ic_editor_smartart)
            if (insertTextBox != null) menu.add(0, 5, 2, Resources.string.editors_text_box).setIcon(Resources.drawable.ic_editor_text_box)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setForceShowIcon(true)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    5 -> insertTextBox?.invoke()
                    0 -> pickPicture()
                    1 -> takePhoto()
                    2 -> chooseShape()
                    3 -> chartUi.chooseType(Resources.string.editors_chart_insert) { type -> edit { insertChart(type) } }
                    else -> chartUi.chooseSmartArt()
                }
                true
            }
        }.show()
    }

    fun showShapeMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 0, 0, Resources.string.editors_shape_fill)
            menu.add(0, 1, 1, Resources.string.editors_shape_outline)
            menu.add(0, 2, 2, Resources.string.editors_shape_front)
            menu.add(0, 3, 3, Resources.string.editors_shape_back)
            menu.add(0, 4, 4, Resources.string.editors_shape_group)
            menu.add(0, 5, 5, Resources.string.editors_shape_ungroup)
            val selectedChart = chart
            if (selectedChart != null) {
                menu.add(0, 10, 0, Resources.string.editors_chart_type)
                menu.add(0, 11, 0, Resources.string.editors_chart_elements)
                if (!selectedChart.dataInCells) menu.add(0, 12, 0, Resources.string.editors_chart_data)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    10 -> chartUi.chooseType(Resources.string.editors_chart_type, selectedChart?.type) { type -> edit { setChartType(type) } }
                    11 -> selectedChart?.let { chartUi.showElements(it) }
                    12 -> chartUi.editData()
                    0 -> chooseColor(Resources.string.editors_shape_fill) { color -> edit { setShapeFill(color) } }
                    1 -> chooseColor(Resources.string.editors_shape_outline) { color -> edit { setShapeOutline(color) } }
                    2 -> edit { arrange(DrawingOrder.TO_FRONT) }
                    3 -> edit { arrange(DrawingOrder.TO_BACK) }
                    4 -> edit { group(true) }
                    else -> edit { group(false) }
                }
                true
            }
        }.show()
    }

    private fun chooseShape() =
        picker.show(Resources.string.editors_insert_shape, InsertCatalog.SHAPES) { preset -> edit { insertShape(preset) } }

    private fun chooseColor(title: Int, onColor: (String?) -> Unit) {
        val labels = COLORS.map { context.getString(it.label) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(labels) { _, index -> onColor(COLORS[index].value.ifEmpty { null }) }
            .show()
    }

    companion object {
        private val COLORS = listOf(
            Choice(Resources.string.editors_color_none, ""),
            Choice(Resources.string.editors_color_black, "000000"),
            Choice(Resources.string.editors_color_white, "FFFFFF"),
            Choice(Resources.string.editors_color_gray, "808080"),
            Choice(Resources.string.editors_color_red, "E53935"),
            Choice(Resources.string.editors_color_orange, "FB8C00"),
            Choice(Resources.string.editors_color_yellow, "FDD835"),
            Choice(Resources.string.editors_color_green, "43A047"),
            Choice(Resources.string.editors_color_blue, "1E88E5"),
            Choice(Resources.string.editors_color_purple, "8E24AA")
        )

        private const val MAX_PICTURE_SIZE = 2048

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
