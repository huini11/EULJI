package com.hanscreen.translator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

data class OverlayItem(
    val translated: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * 原位覆盖层：在原文位置盖一块半透明底板，把译文写在上面。
 * 字号会按框大小自动收缩，避免溢出到相邻控件。
 */
class OverlayPaintView(context: Context) : View(context) {

    var items: List<OverlayItem> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    var fontPx: Float = 42f
        set(v) {
            field = v
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val padH = 6f * density
    private val padV = 3f * density
    private val radius = 7f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 必须完全不透明：94% 会让原文透上来形成重影，看起来像没盖住
        color = Color.parseColor("#FF161B2E")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#552F6BFF")
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val screenW = width.toFloat()
        val screenH = height.toFloat()

        for (item in items.sortedBy { it.top }) {
            if (item.translated.isBlank()) continue

            var l = item.left - padH
            var t = item.top - padV
            var r = item.right + padH
            var b = item.bottom + padV

            // 保证最小可读高度
            val minH = fontPx * 1.55f
            if (b - t < minH) {
                val cy = (t + b) / 2f
                t = cy - minH / 2f
                b = cy + minH / 2f
            }
            // 保证最小可读宽度
            val minW = fontPx * 2.4f
            if (r - l < minW) {
                val cx = (l + r) / 2f
                l = cx - minW / 2f
                r = cx + minW / 2f
            }

            // 收敛到屏幕内
            if (l < 2f) { r += (2f - l); l = 2f }
            if (r > screenW - 2f) { l -= (r - screenW + 2f); r = screenW - 2f }
            if (t < 2f) { b += (2f - t); t = 2f }
            if (b > screenH - 2f) { t -= (b - screenH + 2f); b = screenH - 2f }
            l = max(l, 2f); t = max(t, 2f)

            val box = RectF(l, t, r, b)
            canvas.drawRoundRect(box, radius, radius, bgPaint)
            canvas.drawRoundRect(box, radius, radius, borderPaint)
            drawFittedText(canvas, item.translated, box)
        }
    }

    private fun drawFittedText(canvas: Canvas, text: String, box: RectF) {
        val innerW = (box.width() - padH * 2).toInt()
        val innerH = box.height() - padV * 2
        if (innerW < 24 || innerH < 10) return

        var size = fontPx
        var layout: StaticLayout? = null
        var guard = 0

        while (guard < 6) {
            textPaint.textSize = size
            val l = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.02f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .build()
            layout = l
            if (l.height <= innerH || size <= fontPx * 0.5f) break
            size *= 0.86f
            guard++
        }

        val l = layout ?: return
        val offsetY = padV + ((innerH - l.height) / 2f).coerceAtLeast(0f)

        canvas.save()
        canvas.translate(box.left + padH, box.top + offsetY)
        canvas.clipRect(0f, 0f, innerW.toFloat(), innerH)
        l.draw(canvas)
        canvas.restore()
    }
}
