package com.local.offlinelocker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class PatternLockView(context: Context) : View(context) {

    var compactMode: Boolean = false
    var onPatternComplete: ((String) -> Unit)? = null

    private val storage = LockStorage(context)
    private val selectedNodes = mutableListOf<Int>()
    private var currentX = -1f
    private var currentY = -1f
    private var isDrawing = false

    // Matching the warm Pixel light keypad palette
    private val dotCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FAF8F5")
        style = Paint.Style.FILL
    }

    private val dotCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5C6470")
        style = Paint.Style.FILL
    }

    private val activeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2B885")
        style = Paint.Style.FILL
    }

    private val activeCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F242D")
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F242D")
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultPx = if (compactMode) {
            (resources.displayMetrics.density * 220).toInt()
        } else {
            (resources.displayMetrics.density * 290).toInt()
        }
        val w = resolveSize(defaultPx, widthMeasureSpec)
        val h = resolveSize(defaultPx, heightMeasureSpec)
        val size = minOf(w, h)
        setMeasuredDimension(size, size)
    }

    private fun getNodeCenter(index: Int): Pair<Float, Float> {
        val row = index / 3
        val col = index % 3
        val cellSize = width / 3f
        return Pair(cellSize * col + cellSize / 2f, cellSize * row + cellSize / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val hidePattern = storage.isPatternHidden
        val cellSize = width / 3f
        val outerRadius = cellSize * 0.28f
        val innerRadius = cellSize * 0.085f

        // Draw connecting path ONLY when Hide Pattern is OFF
        if (!hidePattern && selectedNodes.isNotEmpty()) {
            val path = Path()
            val (startX, startY) = getNodeCenter(selectedNodes[0])
            path.moveTo(startX, startY)
            for (i in 1 until selectedNodes.size) {
                val (nx, ny) = getNodeCenter(selectedNodes[i])
                path.lineTo(nx, ny)
            }
            if (isDrawing && currentX >= 0f && currentY >= 0f) {
                path.lineTo(currentX, currentY)
            }
            canvas.drawPath(path, linePaint)
        }

        // Draw 3x3 nodes styled like the circular keypad keys
        for (i in 0 until 9) {
            val (cx, cy) = getNodeCenter(i)
            val isSelected = !hidePattern && selectedNodes.contains(i)
            canvas.drawCircle(cx, cy, outerRadius, if (isSelected) activeRingPaint else dotCirclePaint)
            canvas.drawCircle(cx, cy, innerRadius, if (isSelected) activeCenterPaint else dotCenterPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                selectedNodes.clear()
                isDrawing = true
                currentX = event.x
                currentY = event.y
                checkHit(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                checkHit(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isDrawing = false
                if (selectedNodes.isNotEmpty()) {
                    val result = selectedNodes.joinToString("") { (it + 1).toString() }
                    onPatternComplete?.invoke(result)
                }
                selectedNodes.clear()
                currentX = -1f
                currentY = -1f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun checkHit(x: Float, y: Float) {
        val hitRadius = (width / 3f) * 0.34f
        for (i in 0 until 9) {
            if (selectedNodes.contains(i)) continue
            val (cx, cy) = getNodeCenter(i)
            if (hypot(x - cx, y - cy) <= hitRadius) {
                selectedNodes.add(i)
            }
        }
    }
}
