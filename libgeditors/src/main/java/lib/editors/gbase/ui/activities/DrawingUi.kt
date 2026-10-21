package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
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
    private val takePhoto: () -> Unit
) {

    private class Choice(val label: Int, val value: String)

    fun showInsertMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 0, 0, Resources.string.editors_insert_picture)
            menu.add(0, 1, 1, Resources.string.editors_insert_photo)
            menu.add(0, 2, 2, Resources.string.editors_insert_shape)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> pickPicture()
                    1 -> takePhoto()
                    else -> chooseShape()
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
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
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

    private fun chooseShape() {
        val labels = SHAPES.map { context.getString(it.label) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_insert_shape)
            .setItems(labels) { _, index -> edit { insertShape(SHAPES[index].value) } }
            .show()
    }

    private fun chooseColor(title: Int, onColor: (String?) -> Unit) {
        val labels = COLORS.map { context.getString(it.label) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(labels) { _, index -> onColor(COLORS[index].value.ifEmpty { null }) }
            .show()
    }

    companion object {
        private val SHAPES = listOf(
            Choice(Resources.string.editors_shape_rect, "rect"),
            Choice(Resources.string.editors_shape_round_rect, "roundRect"),
            Choice(Resources.string.editors_shape_ellipse, "ellipse"),
            Choice(Resources.string.editors_shape_triangle, "triangle"),
            Choice(Resources.string.editors_shape_diamond, "diamond"),
            Choice(Resources.string.editors_shape_star, "star5"),
            Choice(Resources.string.editors_shape_right_arrow, "rightArrow"),
            Choice(Resources.string.editors_shape_line, "line"),
            Choice(Resources.string.editors_shape_arrow, "straightConnector1"),
            Choice(Resources.string.editors_shape_callout, "wedgeRectCallout"),
            Choice(Resources.string.editors_shape_process, "flowChartProcess"),
            Choice(Resources.string.editors_shape_decision, "flowChartDecision")
        )

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
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
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
