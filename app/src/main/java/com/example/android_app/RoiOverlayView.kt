package com.example.android_app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class RoiOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var drawing = false
    private var x0 = 0f; private var y0 = 0f
    private var x1 = 0f; private var y1 = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(14f, 12f), 0f)
    }

    /** Called on ACTION_UP with the final box (overlay view coordinates). */
    var onBoxFinished: ((RectF) -> Unit)? = null

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Don't let the ScrollView intercept while drawing
        parent?.requestDisallowInterceptTouchEvent(true)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawing = true
                x0 = ev.x.coerceIn(0f, width.toFloat())
                y0 = ev.y.coerceIn(0f, height.toFloat())
                x1 = x0; y1 = y0
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawing) {
                    x1 = ev.x.coerceIn(0f, width.toFloat())
                    y1 = ev.y.coerceIn(0f, height.toFloat())
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (drawing) {
                    drawing = false
                    x1 = ev.x.coerceIn(0f, width.toFloat())
                    y1 = ev.y.coerceIn(0f, height.toFloat())
                    invalidate()
                    val r = RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
                    if (r.width() > 12 && r.height() > 12) onBoxFinished?.invoke(r)
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawing || (x0 != x1 && y0 != y1)) {
            val r = RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
            canvas.drawRect(r, paint)
        }
    }
}
