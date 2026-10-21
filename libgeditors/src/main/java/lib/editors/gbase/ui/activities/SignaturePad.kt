package lib.editors.gbase.ui.activities

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import lib.editors.resources.R as Resources
import java.io.File

/**
 * Drawing a signature with the finger. [onSigned] gets a PNG (transparent around the strokes,
 * cut to them) and its width / height.
 */
internal class SignaturePad(private val context: Context, private val onSigned: (File, Float) -> Unit) {

    fun show() {
        val pad = PadView(context)
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val dialog = AlertDialog.Builder(context)
            .setTitle(Resources.string.editors_sign_draw)
            .setView(FrameLayout(context).apply {
                setPadding(padding, 0, padding, 0)
                addView(pad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (200 * context.resources.displayMetrics.density).toInt()))
            })
            .setPositiveButton(Resources.string.editors_sign_place, null)
            .setNeutralButton(Resources.string.editors_sign_clear, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val signature = pad.export() ?: return@setOnClickListener
                dialog.dismiss()
                onSigned(signature.first, signature.second)
            }
        }
        dialog.show()
    }

    private class PadView(context: Context) : View(context) {
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(16, 40, 120)
            style = Paint.Style.STROKE
            strokeWidth = 3 * context.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init {
            setBackgroundColor(Color.rgb(245, 245, 245))
        }

        fun clear() {
            path.reset()
            invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    path.moveTo(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.historySize) path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                    path.lineTo(event.x, event.y)
                }
            }
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(path, paint)
        }

        // The strokes on a transparent picture of their size (at twice the resolution)
        fun export(): Pair<File, Float>? {
            val bounds = RectF()
            path.computeBounds(bounds, true)
            if (bounds.width() < 4 && bounds.height() < 4) return null
            val margin = paint.strokeWidth * 2
            bounds.inset(-margin, -margin)
            val scale = 2f
            val bitmap = Bitmap.createBitmap((bounds.width() * scale).toInt(), (bounds.height() * scale).toInt(), Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                scale(scale, scale)
                translate(-bounds.left, -bounds.top)
                drawPath(path, paint)
            }
            val dir = File(context.cacheDir, "pdf-edit").apply { mkdirs() }
            val file = File(dir, "signature-${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return file to bounds.width() / bounds.height()
        }
    }
}
