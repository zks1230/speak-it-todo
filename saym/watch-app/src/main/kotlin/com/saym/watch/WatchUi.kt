package com.saym.watch

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 手表 UI 规范。
 *
 * 设计依据：屏幕按 Apple Watch Ultra 量级（约 49mm / 410×502 像素）估算 —— 方形圆角、字大、点击区大。
 *
 * ⚠️ 为什么要有「界面缩放」：
 * 这台开发机拿不到 DW99 的真实 `wm density`（无 adb）。万一实际显示偏小/偏大，
 * 用户可以在手表上直接切 小/中/大，**不需要我改代码重新装包**。
 * 这是"看不到真机"情况下唯一实用的兜底手段。
 */
object WatchUi {

    // 字号（sp）与点击区高度（dp）随缩放档位变化
    private val FONT_TITLE = intArrayOf(15, 17, 20)
    private val FONT_ITEM = intArrayOf(13, 15, 18)
    private val FONT_SMALL = intArrayOf(10, 11, 13)
    private val BTN_HEIGHT = intArrayOf(38, 46, 58)
    private val PAD = intArrayOf(6, 8, 12)

    /** 当前缩放档位 0/1/2 —— 由 Activity 在 onCreate 时从设置读取 */
    var scale: Int = 1
        private set

    fun applyScale(c: Context) {
        scale = WatchStore.uiScale(c).coerceIn(0, 2)
    }

    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun fontTitle() = FONT_TITLE[scale].toFloat()
    fun fontItem() = FONT_ITEM[scale].toFloat()
    fun fontSmall() = FONT_SMALL[scale].toFloat()
    fun btnHeight(ctx: Context) = dp(ctx, BTN_HEIGHT[scale])
    fun pad(ctx: Context) = dp(ctx, PAD[scale])

    // ---- 颜色（手表上对比度要高，别用浅灰） ----
    val BG = Color.parseColor("#F2F3F5")
    val CARD = Color.WHITE
    val TEXT = Color.parseColor("#1A1A1A")
    val MUTED = Color.parseColor("#6B7280")
    val PRIMARY = Color.parseColor("#1A73E8")
    val DANGER = Color.parseColor("#E5484D")
    val OK = Color.parseColor("#12A150")
    val STAR = Color.parseColor("#E8A33D")
    val DIVIDER = Color.parseColor("#DDDDDD")

    // ============ 组件 ============

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(ctx), pad(ctx), pad(ctx), pad(ctx))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 12).toFloat()
            setColor(CARD)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 8) }
    }

    fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = fontTitle()
        setTextColor(TEXT)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    fun body(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = fontItem()
        setTextColor(TEXT)
    }

    fun muted(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = fontSmall()
        setTextColor(MUTED)
        setLineSpacing(dp(ctx, 2).toFloat(), 1f)
    }

    /**
     * 大按钮。
     * 用系统 Button 而不是自定义 View：DW99 是原生 Android，系统 Button 的触摸反馈最稳。
     */
    fun button(ctx: Context, text: String, primary: Boolean = false, onClick: () -> Unit): android.widget.Button =
        android.widget.Button(ctx).apply {
            this.text = text
            textSize = fontItem()
            isAllCaps = false
            minHeight = btnHeight(ctx)
            minimumHeight = btnHeight(ctx)
            setPadding(pad(ctx), 0, pad(ctx), 0)
            if (primary) {
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 10).toFloat()
                    setColor(PRIMARY)
                }
            }
            setOnClickListener { onClick() }
        }

    fun row(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.marginStart = dp(ctx, 6)
            v.layoutParams = lp
            addView(v)
        }
    }

    fun spacer(ctx: Context, h: Int): View = View(ctx).apply {
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, h))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
            topMargin = dp(ctx, 6); bottomMargin = dp(ctx, 6)
        }
        setBackgroundColor(DIVIDER)
    }

    fun empty(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = fontItem()
        setTextColor(MUTED)
        gravity = Gravity.CENTER
        setPadding(0, dp(ctx, 24), 0, dp(ctx, 24))
    }

    fun vertical(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
}
