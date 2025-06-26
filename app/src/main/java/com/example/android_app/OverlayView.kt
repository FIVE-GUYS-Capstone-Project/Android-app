package com.example.android_app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * A custom ImageView that allows drawing one or more rectangular overlays on top of a displayed image.
 * This is typically used to highlight detected objects (e.g., bounding boxes from ML inference).
 */
class OverlayView(context: Context, attrs: AttributeSet) : AppCompatImageView(context, attrs) {

    // Stores the list of rectangles to be drawn
    private var drawRects: List<Rect>? = null

    // Paint object used to draw bounding boxes
    private val paint = Paint().apply {
        color = Color.RED           // Red color for the overlay box
        strokeWidth = 6f            // Thickness of the box
        style = Paint.Style.STROKE // Draw only the outline
    }

    /**
     * Sets a new bitmap and list of rectangles to be drawn as overlays.
     * @param bitmap The image to display in the ImageView.
     * @param rects A list of Rect objects representing detection boxes.
     */
    fun setOverlay(bitmap: Bitmap, rects: List<Rect>?) {
        setImageBitmap(bitmap)
        drawRects = rects
        invalidate() // Triggers a redraw with the new overlay
    }

    /**
     * Called automatically during the View's draw cycle. Responsible for drawing bounding boxes
     * over the image using the correct transformation matrix (scale and translation).
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawRects?.let { rects ->
            val drawable = drawable ?: return // Ensure image is loaded
            val matrixValues = FloatArray(9)
            imageMatrix.getValues(matrixValues) // Get transformation matrix

            // Extract scale and translation values from the image matrix
            val scaleX = matrixValues[Matrix.MSCALE_X]
            val scaleY = matrixValues[Matrix.MSCALE_Y]
            val transX = matrixValues[Matrix.MTRANS_X]
            val transY = matrixValues[Matrix.MTRANS_Y]

            // Apply transformation to each rectangle and draw it
            for (rect in rects) {
                val left = rect.left * scaleX + transX
                val top = rect.top * scaleY + transY
                val right = rect.right * scaleX + transX
                val bottom = rect.bottom * scaleY + transY
                canvas.drawRect(left, top, right, bottom, paint)
            }
        }
    }
}
