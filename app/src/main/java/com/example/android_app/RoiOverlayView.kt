package com.example.android_app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import android.view.ViewParent

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

    /** Area (in this view's coords) where the image is actually drawn (fitCenter). */
    private var content: RectF? = null
    fun setContentBounds(r: RectF) {
        content = RectF(r); invalidate()
    }

    /** Called on ACTION_UP with the final box (overlay view coordinates). */
    var onBoxFinished: ((RectF) -> Unit)? = null

    fun showBox(rect: RectF) {
        x0 = rect.left; y0 = rect.top
        x1 = rect.right; y1 = rect.bottom
        drawing = false
        invalidate()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Don't let the ScrollView intercept while drawing
        parent?.requestDisallowInterceptTouchEvent(true)
        val parentView: ViewParent? = parent

        fun clampX(x: Float) = content?.let { x.coerceIn(it.left, it.right) } ?: x
        fun clampY(y: Float) = content?.let { y.coerceIn(it.top,  it.bottom) } ?: y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parentView?.requestDisallowInterceptTouchEvent(true)
                drawing = true
                x0 = clampX(ev.x); y0 = clampY(ev.y)
                x1 = x0; y1 = y0
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawing) {
                    x1 = clampX(ev.x); y1 = clampY(ev.y)
                    invalidate()
                }
                parentView?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (drawing) {
                    drawing = false; invalidate()
                    val r = RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
                    if (r.width() > 12 && r.height() > 12) onBoxFinished?.invoke(r)
                }
                parentView?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var didSave = false
        content?.let { didSave = true; canvas.save(); canvas.clipRect(it) }
        if (drawing || (x0 != x1 && y0 != y1)) {
            val r = RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
            canvas.drawRect(r, paint)
        }
        if (didSave) canvas.restore()
    }
}
