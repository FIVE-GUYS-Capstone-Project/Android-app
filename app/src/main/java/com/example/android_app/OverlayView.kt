package com.example.android_app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

class OverlayView(context: Context, attrs: AttributeSet) : AppCompatImageView(context, attrs) {
    private var drawRect: Rect? = null

    private val paint = Paint().apply {
        color = Color.RED
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    fun setOverlay(bitmap: Bitmap, rect: Rect?) {
        setImageBitmap(bitmap)
        drawRect = rect
        invalidate()
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        drawRect?.let {
            canvas?.drawRect(it, paint)
        }
    }
}
