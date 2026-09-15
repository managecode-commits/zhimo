// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

class HandwritingCanvas(
    context: Context,
    private val inkColor: Int,
    private val beforeStroke: () -> Boolean = { true },
    private val changed: (List<InkPoint>?) -> Unit,
) : View(context) {
    private val strokes = mutableListOf<List<InkPoint>>()
    private val pending = mutableListOf<InkPoint>()
    private var pointer = -1
    val isWriting: Boolean get() = pointer >= 0
    var lineMode = false
        set(value) { field = value; invalidate() }
    var boundaries: List<Float> = emptyList()
        set(value) { field = value.toList(); invalidate() }
    var editingBoundaries = false
        set(value) { field = value; boundaryPointer = -1; invalidate() }
    var onBoundariesChanged: ((List<Float>) -> Unit)? = null
    private var boundaryPointer = -1
    private var boundaryIndex = -1
    private var boundaryDown = 0f
    private var boundaryMoved = false
    private var boundaryOriginal: List<Float> = emptyList()
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkColor; strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    init { contentDescription = "手写区域，请一次书写一个汉字"; isClickable = true }
    fun clear() { strokes.clear(); pending.clear(); pointer = -1; editingBoundaries = false; boundaries = emptyList(); invalidate() }
    fun restore(values: List<List<InkPoint>>) { clear(); strokes.addAll(values.map { it.toList() }); invalidate() }
    fun undo() { pending.clear(); pointer = -1; if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pen.alpha = 14
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, pen)
        if (lineMode && boundaries.isNotEmpty()) {
            pen.alpha = 65
            for (cut in boundaries) canvas.drawLine(width * cut, 0f, width * cut, height.toFloat(), pen)
        } else if (!lineMode) canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), pen)
        pen.alpha = 255
        for (stroke in strokes + listOf(pending)) {
            if (stroke.size == 1) canvas.drawPoint(stroke[0].x, stroke[0].y, pen)
            for (i in 1 until stroke.size) canvas.drawLine(stroke[i-1].x, stroke[i-1].y, stroke[i].x, stroke[i].y, pen)
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editingBoundaries) return editBoundary(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!beforeStroke()) return false
                if (strokes.size >= 64 || strokes.sumOf { it.size } >= 32768) return false
                pointer = event.getPointerId(0); pending.clear(); changed(null)
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_CANCEL -> {
                pointer = -1; pending.clear(); changed(null); invalidate(); return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return true
        }
        val index = event.findPointerIndex(pointer)
        if (index < 0) return true
        fun add(x: Float, y: Float, time: Long) {
            if (pending.size < 4096 && strokes.sumOf { it.size } + pending.size < 32768)
                pending.add(InkPoint(x.coerceIn(0f, width.toFloat()), y.coerceIn(0f, height.toFloat()), time))
        }
        for (i in 0 until event.historySize) add(event.getHistoricalX(index, i), event.getHistoricalY(index, i), event.getHistoricalEventTime(i))
        add(event.getX(index), event.getY(index), event.eventTime)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == pointer)) {
            val stroke = pending.toList(); strokes.add(stroke); pending.clear(); pointer = -1
            changed(stroke); performClick()
        }
        invalidate(); return true
    }
    private fun editBoundary(event: MotionEvent): Boolean {
        if (!beforeStroke() || width <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                boundaryPointer = event.getPointerId(0); boundaryDown = event.x; boundaryMoved = false
                boundaryOriginal = boundaries
                boundaryIndex = boundaries.indices.minByOrNull { kotlin.math.abs(boundaries[it] * width - event.x) }
                    ?.takeIf { kotlin.math.abs(boundaries[it] * width - event.x) <= 24 * resources.displayMetrics.density } ?: -1
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_CANCEL -> { boundaries = boundaryOriginal; boundaryPointer = -1; return true }
        }
        val index = event.findPointerIndex(boundaryPointer)
        if (index < 0) return true
        val x = event.getX(index)
        if (kotlin.math.abs(x - boundaryDown) > 8 * resources.displayMetrics.density) boundaryMoved = true
        if (event.actionMasked == MotionEvent.ACTION_MOVE && boundaryIndex >= 0) {
            val updated = boundaries.toMutableList()
            updated[boundaryIndex] = (x / width).coerceIn(.01f, .99f)
            boundaries = updated // sort only on release, keeping the dragged boundary's identity
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == boundaryPointer)) {
            val updated = boundaries.toMutableList()
            if (boundaryIndex >= 0 && !boundaryMoved) updated.removeAt(boundaryIndex)
            else if (boundaryIndex < 0 && updated.size < 3) updated.add((x / width).coerceIn(.01f, .99f))
            boundaries = updated.sorted().distinct()
            boundaryPointer = -1; onBoundariesChanged?.invoke(boundaries); performClick()
        }
        return true
    }
}
