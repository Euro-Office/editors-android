package lib.editors.gbase.ui.activities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import lib.editors.base.engine.ChartState
import lib.editors.base.engine.EditorDocument
import lib.editors.base.engine.EditorState
import lib.editors.gbase.R
import lib.editors.resources.R as Resources
import java.io.File
import java.util.UUID

/**
 * Inserting pictures and shapes and formatting the selected ones: the toolbar's "Insert" menu
 * and "Shape" panel ([ShapePanel]). Picking and taking pictures needs the activity; it calls back
 * through [pickPicture] and [takePhoto].
 */
internal class DrawingUi(
    private val context: Context,
    private val edit: (EditorDocument.() -> EditorState) -> Unit,
    private val pickPicture: () -> Unit,
    private val takePhoto: () -> Unit,
    private val chartUi: ChartUi,
    private val shapePanel: ShapePanel,
    private val picker: IconPicker,
    private val tablePicker: PreviewPicker? = null,
    private val insertTextBox: (() -> Unit)? = null,
    /** More items at the end of the menu, which do their actions themselves (documents' breaks, notes, ...). */
    private val moreInsertItems: (Menu) -> Unit = {}
) {

    /** The selected chart, for its own settings in the shape panel. */
    var chart: ChartState? = null

    fun showInsertMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 0, 0, Resources.string.editors_insert_picture).setIcon(Resources.drawable.ic_editor_image)
            menu.add(0, 1, 1, Resources.string.editors_insert_photo).setIcon(Resources.drawable.ic_editor_camera)
            menu.add(0, 2, 2, Resources.string.editors_insert_shape).setIcon(Resources.drawable.ic_editor_shapes)
            menu.add(0, 3, 3, Resources.string.editors_chart_insert).setIcon(Resources.drawable.ic_editor_chart)
            menu.add(0, 4, 4, Resources.string.editors_smartart_insert).setIcon(Resources.drawable.ic_editor_smartart)
            if (insertTextBox != null) menu.add(0, 5, 2, Resources.string.editors_text_box).setIcon(Resources.drawable.ic_editor_text_box)
            if (tablePicker != null) menu.add(0, 6, 3, Resources.string.editors_table_insert).setIcon(Resources.drawable.ic_editor_table)
            moreInsertItems(menu)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setForceShowIcon(true)
            setOnMenuItemClickListener { item ->
                // Opening a submenu of moreInsertItems (their ids aren't these)
                if (item.hasSubMenu()) return@setOnMenuItemClickListener false
                when (item.itemId) {
                    5 -> insertTextBox?.invoke()
                    6 -> chooseTable()
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

    fun showShapePanel() = shapePanel.show(chart)

    // The table's size above pictures of the styles; a style inserts it
    private fun chooseTable() {
        val picker = tablePicker ?: return
        var cols = 3
        var rows = 3
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(8), 0, dp(8), dp(8))
            fun stepper(label: Int, get: () -> Int, set: (Int) -> Unit) {
                addView(TextView(context).apply {
                    setText(label)
                    setPaddingRelative(0, 0, dp(4), 0)
                })
                val value = TextView(context).apply {
                    text = get().toString()
                    gravity = Gravity.CENTER
                    minWidth = dp(32)
                }
                fun button(text: String, step: Int, description: String) = TextView(context).apply {
                    this.text = text
                    textSize = 18f
                    gravity = Gravity.CENTER
                    minWidth = dp(44)
                    minHeight = dp(44)
                    contentDescription = description
                    setBackgroundResource(R.drawable.editor_tool_background)
                    setOnClickListener {
                        set((get() + step).coerceIn(1, MAX_TABLE_SIZE))
                        value.text = get().toString()
                    }
                }
                val name = context.getString(label)
                addView(button("−", -1, "$name −"))
                addView(value)
                addView(button("+", 1, "$name +"))
                addView(View(context), LinearLayout.LayoutParams(dp(16), 1))
            }
            stepper(Resources.string.editors_doc_columns, { cols }, { cols = it })
            stepper(Resources.string.editors_doc_rows, { rows }, { rows = it })
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
