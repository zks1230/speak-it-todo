package com.saym.phone.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.saym.phone.R

/**
 * 统一视觉规范。
 *
 * 之前每一页都是直接用默认 Button/TextView 拼的，所以看起来是"一堆灰方块"。
 * 这里把卡片、按钮、标题、行、标签的样式集中一处，保证所有页面观感一致，
 * 也避免每个 Activity 各写一遍 dp 换算。
 */
object UiKit {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    // ============ 卡片 ============

    fun card(ctx: Context, paddingDp: Int = 16): MaterialCardView =
        MaterialCardView(ctx).apply {
            radius = dp(ctx, 14).toFloat()
            cardElevation = dp(ctx, 1).toFloat()
            setCardBackgroundColor(c(ctx, R.color.saym_surface))
            val p = dp(ctx, paddingDp)
            setContentPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12) }
        }

    // ============ 文字 ============

    fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(c(ctx, R.color.saym_text))
    }

    fun body(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTextColor(c(ctx, R.color.saym_text))
    }

    fun muted(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(c(ctx, R.color.saym_text_muted))
        setLineSpacing(dp(ctx, 3).toFloat(), 1f)
    }

    fun sectionLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(c(ctx, R.color.saym_text_muted))
        setPadding(0, dp(ctx, 12), 0, dp(ctx, 6))
    }

    // ============ 按钮 ============

    /** 主按钮（实心蓝） */
    fun primaryButton(ctx: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            this.text = text
            textSize = 15f
            isAllCaps = false
            cornerRadius = dp(ctx, 12)
            minHeight = dp(ctx, 46)
            setOnClickListener { onClick() }
        }

    /** 次按钮（描边） */
    fun outlinedButton(ctx: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            cornerRadius = dp(ctx, 12)
            minHeight = dp(ctx, 42)
            strokeWidth = dp(ctx, 1)
            strokeColor = android.content.res.ColorStateList.valueOf(c(ctx, R.color.saym_divider))
            setTextColor(c(ctx, R.color.saym_text))
            setOnClickListener { onClick() }
        }

    /** 文字按钮 */
    fun textButton(ctx: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setTextColor(c(ctx, R.color.saym_primary))
            setOnClickListener { onClick() }
        }

    /** 一排等宽按钮 */
    fun buttonRow(ctx: Context, vararg buttons: View): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { i, b ->
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (i > 0) lp.marginStart = dp(ctx, 8)
                b.layoutParams = lp
                addView(b)
            }
        }

    // ============ 标签 ============

    /** 小圆角标签：学科、来源、关键任务等 */
    fun chip(ctx: Context, text: String, bgColorRes: Int, textColorRes: Int): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 11f
            setTextColor(c(ctx, textColorRes))
            setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 8).toFloat()
                setColor(c(ctx, bgColorRes))
            }
            // FrameLayout.LayoutParams 继承自 MarginLayoutParams，所以 margin/bottomMargin 都可用
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(ctx, 6)
                topMargin = dp(ctx, 4)
                bottomMargin = dp(ctx, 2)
            }
        }

    fun chipPrimary(ctx: Context, text: String): TextView =
        chip(ctx, text, R.color.saym_primary_light, R.color.saym_primary_dark)

    fun chipStar(ctx: Context, text: String): TextView =
        chip(ctx, text, R.color.saym_primary_light, R.color.saym_star)

    fun chipOk(ctx: Context, text: String): TextView =
        chip(ctx, text, R.color.saym_primary_light, R.color.saym_ok)

    /** 一条标签行容器（自动换行需要 Flexbox，这里简单横排即可） */
    fun chipRow(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    // ============ 分隔线 ============

    fun divider(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)
        ).apply { topMargin = dp(ctx, 10); bottomMargin = dp(ctx, 10) }
        setBackgroundColor(c(ctx, R.color.saym_divider))
    }

    /** 极细分隔（列表行之间） */
    fun hairline(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)
        )
        setBackgroundColor(c(ctx, R.color.saym_divider))
    }

    // ============ 可滚动容器 ============

    fun vertical(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun spacer(ctx: Context, heightDp: Int): View = View(ctx).apply {
        // 用 MarginLayoutParams：不管父容器是 LinearLayout 还是别的，
        // 都支持 margin/height，避免把 LinearLayout.LayoutParams 硬塞给非 LinearLayout 父容器
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)
        )
    }

    /** 空状态 */
    fun emptyState(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(c(ctx, R.color.saym_text_muted))
        setPadding(0, dp(ctx, 32), 0, dp(ctx, 32))
    }

    // ============ 提示 ============

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun snack(root: View, msg: String) {
        try {
            Snackbar.make(root, msg, Snackbar.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(root.context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun c(ctx: Context, res: Int): Int = ContextCompat.getColor(ctx, res)
}
