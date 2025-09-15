package com.example.android_app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * OverlayView can draw detection boxes in two modes:
 *  1) Image-space (legacy): call setOverlay(bitmap, rects) with Rect in bitmap coords.
 *  2) View-space (recommended for overlays on top of another ImageView):
 *     call showBoxes(rectsInView, color, strokeDp) with RectF already mapped to this view.
 */
class OverlayView(context: Context, attrs: AttributeSet) : AppCompatImageView(context, attrs) {

    // --- Image-space path (uses this ImageView's matrix)
    private var imageRects: List<Rect>? = null

    // --- View-space path (draw exactly at given coords)
    private var viewRects: List<RectF>? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    /**
     * LEGACY path: sets a bitmap to display and Rect boxes in *image* coordinates.
     * Boxes are transformed by this ImageView's matrix (scale + translate).
     */
    fun setOverlay(bitmap: Bitmap, rects: List<Rect>?) {
        setImageBitmap(bitmap)
        imageRects = rects
        viewRects = null           // switch to image-space mode
        invalidate()
    }

    /**
     * NEW path: draw RectF boxes that are already in this view's coordinate space.
     * Useful when this view sits above another ImageView that shows the RGB.
     */
    fun showBoxes(rectsInView: List<RectF>, color: Int = Color.RED, strokeDp: Float = 3f) {
        viewRects = rectsInView
        imageRects = null          // switch to view-space mode
        paint.color = color
        paint.strokeWidth = dpToPx(strokeDp)
        invalidate()
    }

    /** Clears any drawn boxes (both modes). */
    fun clear() {
        imageRects = null
        viewRects = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1) Preferred: draw view-space rects directly
        viewRects?.let { rects ->
            for (r in rects) canvas.drawRect(r, paint)
            return
        }

        // 2) Fallback: draw image-space rects using this ImageView's matrix
        imageRects?.let { rects ->
            val d = drawable ?: return
            val m = FloatArray(9)
            imageMatrix.getValues(m)
            val sx = m[Matrix.MSCALE_X]
            val sy = m[Matrix.MSCALE_Y]
            val tx = m[Matrix.MTRANS_X]
            val ty = m[Matrix.MTRANS_Y]

            for (r in rects) {
                val left = r.left * sx + tx
                val top = r.top * sy + ty
                val right = r.right * sx + tx
                val bottom = r.bottom * sy + ty
                canvas.drawRect(left, top, right, bottom, paint)
            }
        }
    }
}
