package com.hanscreen.translator.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hanscreen.translator.core.Screen

/**
 * 译文覆盖窗口。两种形态：
 *  - 原位覆盖：贴在原文位置，适合按钮/表单/短句
 *  - 底部卡片：整屏译文列表，适合长文/对话
 */
class OverlayController(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var root: FrameLayout? = null
    private var paintView: OverlayPaintView? = null
    private var cardTitle: TextView? = null
    private var cardList: LinearLayout? = null
    private var currentMode = -1

    val isShowing: Boolean get() = root != null

    fun show(items: List<OverlayItem>, cardMode: Boolean, fontPx: Float) {
        if (items.isEmpty()) {
            hide()
            return
        }
        val mode = if (cardMode) 1 else 0

        if (root == null || currentMode != mode) {
            rebuild(mode, fontPx)
            currentMode = mode
        }

        if (mode == 0) {
            paintView?.fontPx = fontPx
            paintView?.items = items
        } else {
            cardTitle?.text = "译文 · ${items.size} 段"
            cardList?.removeAllViews()
            val sp = (fontPx / context.resources.displayMetrics.scaledDensity)
                .coerceIn(11f, 24f)
            items.sortedBy { it.top }.forEach { item ->
                val tv = TextView(context).apply {
                    text = item.translated
                    setTextColor(Color.WHITE)
                    textSize = sp
                    setLineSpacing(3f * density, 1f)
                    setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt())
                }
                cardList?.addView(
                    tv,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            (cardList?.parent as? ScrollView)?.scrollTo(0, 0)
        }
    }

    fun hide() {
        val r = root ?: return
        root = null
        paintView = null
        cardList = null
        cardTitle = null
        currentMode = -1
        runCatching { wm.removeViewImmediate(r) }
    }

    /** 临时隐藏但不销毁，用于连续翻译时抓一张干净的原始画面 */
    fun setVisible(visible: Boolean) {
        root?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun rebuild(mode: Int, fontPx: Float) {
        hide()
        val screen: Point = Screen.size(context)

        val container = FrameLayout(context).apply {
            isClickable = true
            setOnClickListener { hide() }
        }
        container.addView(
            View(context),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        if (mode == 0) {
            val pv = OverlayPaintView(context).apply {
                this.fontPx = fontPx
                isClickable = false
            }
            container.addView(
                pv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            paintView = pv
        } else {
            container.addView(buildCard(screen), cardParams(screen))
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        runCatching { wm.addView(container, lp) }
        root = container
    }

    private fun cardParams(screen: Point): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (screen.y * 0.52f).toInt(),
            Gravity.BOTTOM
        ).apply {
            leftMargin = (10 * density).toInt()
            rightMargin = (10 * density).toInt()
            bottomMargin = (14 * density).toInt()
        }

    private fun buildCard(screen: Point): View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(Color.parseColor("#F5161B2E"))
                setStroke((1 * density).toInt(), Color.parseColor("#3D2F6BFF"))
            }
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (10 * density).toInt()
            )
            isClickable = true
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        cardTitle = TextView(context).apply {
            text = "译文"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(
            cardTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        header.addView(TextView(context).apply {
            text = "关闭"
            setTextColor(Color.parseColor("#9FB3FF"))
            textSize = 13f
            setPadding((14 * density).toInt(), (6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt())
            setOnClickListener { hide() }
        })

        panel.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        cardList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        scroll.addView(
            cardList,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        return panel
    }
}
