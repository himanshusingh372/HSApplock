package com.local.offlinelocker

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class HandGestureView(context: Context) : View(context) {

    var onGestureDrawn: ((String) -> Unit)? = null

    private val path = Path()
    private val points = mutableListOf<PointF>()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#38BDF8")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#1E293B")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(560).coerceAtLeast(380)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#0B1120"))
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), gridPaint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, gridPaint)
        canvas.drawPath(path, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                path.reset()
                points.clear()
                path.moveTo(event.x, event.y)
                points.add(PointF(event.x, event.y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val last = points.lastOrNull()
                if (last == null || hypot(event.x - last.x, event.y - last.y) > 18f) {
                    path.lineTo(event.x, event.y)
                    points.add(PointF(event.x, event.y))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (points.size >= 4) {
                    onGestureDrawn?.invoke(encodeDirectionSignature(points))
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun clearCanvas() {
        path.reset()
        points.clear()
        invalidate()
    }

    // Encodes hand sign into an 8-direction quantized signature (U, D, L, R, UR, UL, DR, DL)
    private fun encodeDirectionSignature(pts: List<PointF>): String {
        val dirs = mutableListOf<String>()
        for (i in 1 until pts.size) {
            val dx = pts[i].x - pts[i - 1].x
            val dy = pts[i].y - pts[i - 1].y
            if (abs(dx) < 12f && abs(dy) < 12f) continue
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            val code = when {
                angle in -22.5..22.5 -> "R"
                angle in 22.5..67.5 -> "DR"
                angle in 67.5..112.5 -> "D"
                angle in 112.5..157.5 -> "DL"
                angle > 157.5 || angle < -157.5 -> "L"
                angle in -157.5..-112.5 -> "UL"
                angle in -112.5..-67.5 -> "U"
                else -> "UR"
            }
            if (dirs.lastOrNull() != code) {
                dirs.add(code)
            }
        }
        return dirs.take(10).joinToString("-")
    }
}
