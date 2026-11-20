package com.hanscreen.translator.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮球：拖动、点击翻译、长按开关连续模式。
 * 位置由外部通过 onDrag 更新 WindowManager 参数。
 */
@SuppressLint("ViewConstructor")
class FloatingBallView(context: Context) : View(context) {

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    var busy: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    var continuous: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#B3FFFFFF")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var moved = false
    private var longFired = false

    private val longPress = Runnable {
        longFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = min(w, h) / 2f
        bodyPaint.shader = RadialGradient(
            w * 0.35f, h * 0.3f, r * 1.35f,
            intArrayOf(Color.parseColor("#6E9BFF"), Color.parseColor("#2F6BFF")),
            null,
            Shader.TileMode.CLAMP
        )
        textPaint.textSize = r * 0.86f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 6f

        bodyPaint.alpha = if (busy) 170 else 255
        canvas.drawCircle(cx, cy, r, bodyPaint)
        canvas.drawCircle(cx, cy, r - 1.5f, ringPaint)

        val label = if (busy) "…" else "译"
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, cx, baseline, textPaint)

        // 状态点：连续模式绿点，普通蓝点
        if (continuous) {
            dotPaint.color = Color.parseColor("#22E06B")
            canvas.drawCircle(width - 12f, 12f, 7f, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                moved = false
                longFired = false
                handler.postDelayed(longPress, 460)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                if (!moved && (abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop)) {
                    moved = true
                    handler.removeCallbacks(longPress)
                }
                if (moved) {
                    onDrag?.invoke(dx, dy)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (!moved && !longFired) {
                    performClick()
                    onTap?.invoke()
                } else if (moved) {
                    onDragEnd?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (moved) onDragEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}
