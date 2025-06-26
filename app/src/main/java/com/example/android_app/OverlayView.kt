package com.example.android_app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

class OverlayView(context: Context, attrs: AttributeSet) : AppCompatImageView(context, attrs) {
    private var drawRects: List<Rect>? = null

    private val paint = Paint().apply {
        color = Color.RED
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    fun setOverlay(bitmap: Bitmap, rects: List<Rect>?) {
        setImageBitmap(bitmap)
        drawRects = rects
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawRects?.let { rects ->
            val drawable = drawable ?: return
            val matrixValues = FloatArray(9)
            imageMatrix.getValues(matrixValues)

            val scaleX = matrixValues[Matrix.MSCALE_X]
            val scaleY = matrixValues[Matrix.MSCALE_Y]
            val transX = matrixValues[Matrix.MTRANS_X]
            val transY = matrixValues[Matrix.MTRANS_Y]

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

