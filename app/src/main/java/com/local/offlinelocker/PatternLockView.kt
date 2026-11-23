package com.local.offlinelocker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class PatternLockView(context: Context) : View(context) {

    var onPatternComplete: ((String) -> Unit)? = null
    var hidePattern: Boolean = false

    private val selectedNodes = mutableListOf<Int>()
    private var currentTouchX = -1f
    private var currentTouchY = -1f
    private var isDrawing = false

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#334155")
    }

    private val activeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.parseColor("#10B981")
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#94A3B8")
    }

    private val activeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#10B981")
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#10B981")
    }

    init {
        isClickable = true
        isFocusable = true
    }

    private fun getNodeCenter(index: Int): Pair<Float, Float> {
        val col = index % 3
        val row = index / 3
        val cellW = width / 3f
        val cellH = height / 3f
        return Pair(cellW * col + cellW / 2f, cellH * row + cellH / 2f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenWidth = resources.displayMetrics.widthPixels
        val targetSize = (screenWidth * 0.74f).toInt()
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val finalSize = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> targetSize.coerceAtMost(widthSize)
            else -> targetSize
        }
        setMeasuredDimension(finalSize, finalSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        if (!hidePattern) {
            for (i in 0 until selectedNodes.size - 1) {
                val (x1, y1) = getNodeCenter(selectedNodes[i])
                val (x2, y2) = getNodeCenter(selectedNodes[i + 1])
                canvas.drawLine(x1, y1, x2, y2, linePaint)
            }

            if (isDrawing && selectedNodes.isNotEmpty() && currentTouchX >= 0f) {
                val (lastX, lastY) = getNodeCenter(selectedNodes.last())
                canvas.drawLine(lastX, lastY, currentTouchX, currentTouchY, linePaint)
            }
        }

        for (i in 0 until 9) {
            val (cx, cy) = getNodeCenter(i)
            val showActive = !hidePattern && selectedNodes.contains(i)
            canvas.drawCircle(cx, cy, 48f, if (showActive) activeRingPaint else outerRingPaint)
            canvas.drawCircle(cx, cy, if (showActive) 20f else 14f, if (showActive) activeDotPaint else dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                selectedNodes.clear()
                isDrawing = true
                currentTouchX = event.x
                currentTouchY = event.y
                checkHit(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                currentTouchX = event.x
                currentTouchY = event.y
                checkHit(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isDrawing = false
                if (selectedNodes.isNotEmpty()) {
                    val code = selectedNodes.joinToString("") { (it + 1).toString() }
                    onPatternComplete?.invoke(code)
                }
                selectedNodes.clear()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isDrawing = false
                selectedNodes.clear()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun checkHit(x: Float, y: Float) {
        val hitRadius = (width / 3f) * 0.40f
        for (i in 0 until 9) {
            if (selectedNodes.contains(i)) continue
            val (cx, cy) = getNodeCenter(i)
            if (hypot(x - cx, y - cy) <= hitRadius) {
                if (selectedNodes.isNotEmpty()) {
                    val prev = selectedNodes.last()
                    val mid = getMiddleNode(prev, i)
                    if (mid != -1 && !selectedNodes.contains(mid)) {
                        selectedNodes.add(mid)
                    }
                }
                selectedNodes.add(i)
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
    }

    private fun getMiddleNode(a: Int, b: Int): Int {
        val r1 = a / 3; val c1 = a % 3
        val r2 = b / 3; val c2 = b % 3
        return if ((r1 + r2) % 2 == 0 && (c1 + c2) % 2 == 0) {
            ((r1 + r2) / 2) * 3 + ((c1 + c2) / 2)
        } else -1
    }
}
