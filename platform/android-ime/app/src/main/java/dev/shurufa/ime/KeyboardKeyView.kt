package dev.shurufa.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.TypedValue
import android.widget.Button

/** Native, accessible button with a secondary legend and code-drawn vector icons. */
// This IME uses a framework theme and explicit colors, not an AppCompat activity/theme.
@android.annotation.SuppressLint("AppCompatCustomView")
class KeyboardKeyView(context: Context) : Button(context) {
    var secondaryLabel: String? = null
    var hintSizeSp = KeyboardTypography.HINT
    var hintColor: Int? = null
    var drawIcon = false
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        if (!drawIcon || !drawFunctionIcon(canvas)) {
            val label = text.toString()
            if (secondaryLabel == null && label.length == 1 && !label[0].isLetterOrDigit()) {
                // Full-width punctuation reserves an em but its ink may sit at
                // one edge. Center the visible glyph, not its advance width.
                paint.color = currentTextColor
                paint.getTextBounds(label, 0, label.length, glyphBounds)
                canvas.drawText(label, width / 2f - glyphBounds.exactCenterX(),
                    height / 2f - glyphBounds.exactCenterY(), paint)
            } else super.onDraw(canvas)
        }
        secondaryLabel?.let { label ->
            legendPaint.style = Paint.Style.FILL
            legendPaint.color = hintColor ?: currentTextColor
            legendPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, hintSizeSp, resources.displayMetrics)
            legendPaint.textAlign = Paint.Align.CENTER
            val inset = 4 * resources.displayMetrics.density
            canvas.drawText(label, width / 2f, inset - legendPaint.fontMetrics.ascent, legendPaint)
        }
    }

    private fun drawFunctionIcon(canvas: Canvas): Boolean {
        val size = minOf(24 * resources.displayMetrics.density, width * 0.62f, height * 0.62f)
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 24, size / 24)
        legendPaint.apply {
            color = currentTextColor
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, legendPaint)
        when (text.toString()) {
            "⌫", "删除" -> {
                val outline = Path().apply { moveTo(9f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(9f, 19f); lineTo(2f, 12f); close() }
                canvas.drawPath(outline, legendPaint)
                line(12f, 9f, 17f, 15f); line(17f, 9f, 12f, 15f)
            }
            "🎙", "重试", "识别" -> {
                canvas.drawRoundRect(9f, 2f, 15f, 15f, 3f, 3f, legendPaint)
                canvas.drawArc(5f, 7f, 19f, 19f, 0f, 180f, false, legendPaint)
                line(12f, 19f, 12f, 22f); line(8f, 22f, 16f, 22f)
            }
            "停止" -> canvas.drawRoundRect(5f, 5f, 19f, 19f, 2f, 2f, legendPaint)
            "😊" -> {
                canvas.drawCircle(12f, 12f, 9f, legendPaint)
                canvas.drawPoint(9f, 9f, legendPaint); canvas.drawPoint(15f, 9f, legendPaint)
                canvas.drawArc(7f, 9f, 17f, 17f, 15f, 150f, false, legendPaint)
            }
            "↔", "左手", "右手" -> {
                canvas.drawRoundRect(2f, 4f, 22f, 20f, 2f, 2f, legendPaint)
                val x = when (text.toString()) { "左手" -> 5f; "右手" -> 13f; else -> 9f }
                canvas.drawRect(x, 8f, x + 6f, 16f, legendPaint)
            }
            "⇧", "⇧ 大写" -> {
                val arrow = Path().apply { moveTo(4f, 11f); lineTo(12f, 3f); lineTo(20f, 11f); lineTo(16f, 11f); lineTo(16f, 20f); lineTo(8f, 20f); lineTo(8f, 11f); close() }
                canvas.drawPath(arrow, legendPaint)
                if (text.toString().contains("大写")) line(8f, 23f, 16f, 23f)
            }
            "⌃", "⌄", "‹", "›" -> {
                val path = Path()
                when (text.toString()) {
                    "⌃" -> { path.moveTo(5f, 15f); path.lineTo(12f, 8f); path.lineTo(19f, 15f) }
                    "⌄" -> { path.moveTo(5f, 8f); path.lineTo(12f, 15f); path.lineTo(19f, 8f) }
                    "‹" -> { path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f) }
                    else -> { path.moveTo(8f, 5f); path.lineTo(15f, 12f); path.lineTo(8f, 19f) }
                }
                canvas.drawPath(path, legendPaint)
            }
            else -> { canvas.restore(); return false }
        }
        canvas.restore()
        return true
    }
}
