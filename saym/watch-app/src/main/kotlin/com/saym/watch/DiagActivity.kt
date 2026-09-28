package com.saym.watch

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 诊断页 —— **没有 adb 的情况下唯一的排查手段**。
 *
 * 用法：出问题时打开这一页，把屏幕**拍照**发给开发者即可。
 * 另外还提供「导出到文件」，可以用 LocalSend / 文件管理器取走完整日志。
 *
 * 显示内容：
 *  - 最近日志（默认最后 60 条，可切换 20/60/全部）
 *  - 网络与配对状态、屏幕参数、内存占用
 */
class DiagActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var summaryView: TextView
    private var showCount = 60

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DLog.init(this)
        WatchUi.applyScale(this)
        build()
    }

    private fun build() {
        val scroll = ScrollView(this).apply { setBackgroundColor(WatchUi.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(WatchUi.pad(this@DiagActivity), WatchUi.pad(this@DiagActivity),
                WatchUi.pad(this@DiagActivity), WatchUi.pad(this@DiagActivity))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(WatchUi.title(this, "诊断"))
        root.addView(WatchUi.muted(this, "出问题时把这一页拍照发给开发者即可。"))

        // ---- 状态摘要 ----
        root.addView(WatchUi.spacer(this, 6))
        val sumCard = WatchUi.card(this)
        summaryView = TextView(this).apply {
            textSize = WatchUi.fontSmall()
            setTextColor(WatchUi.TEXT)
            setLineSpacing(WatchUi.dp(this@DiagActivity, 2).toFloat(), 1f)
        }
        sumCard.addView(summaryView)
        root.addView(sumCard)

        // ---- 操作 ----
        root.addView(WatchUi.row(
            this,
            WatchUi.button(this, "刷新") { build() },
            WatchUi.button(this, "清空") { DLog.clear(); build() }
        ))
        root.addView(WatchUi.spacer(this, 6))
        root.addView(WatchUi.row(
            this,
            WatchUi.button(this, "20 条") { showCount = 20; build() },
            WatchUi.button(this, "60 条") { showCount = 60; build() },
            WatchUi.button(this, "全部") { showCount = 200; build() }
        ))
        root.addView(WatchUi.spacer(this, 6))
        root.addView(WatchUi.row(
            this,
            WatchUi.button(this, "复制日志") { copyLogs() },
            WatchUi.button(this, "导出文件") { exportLogs() }
        ))
        root.addView(WatchUi.spacer(this, 6))
        root.addView(WatchUi.button(this, "快速自检（ping 手机）") { quickCheck() })

        // ---- 日志正文 ----
        root.addView(WatchUi.spacer(this, 8))
        val logCard = WatchUi.card(this)
        logCard.addView(WatchUi.body(this, "最近日志"))
        logView = TextView(this).apply {
            textSize = WatchUi.fontSmall()
            setTextColor(WatchUi.TEXT)
            setLineSpacing(WatchUi.dp(this@DiagActivity, 3).toFloat(), 1f)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logCard.addView(logView)
        root.addView(logCard)

        render()
    }

    private fun render() {
        val dm = resources.displayMetrics
        val host = WatchStore.phoneHost(this)
        val last = WatchStore.lastSyncAt(this)
        summaryView.text = buildString {
            append("配对：").append(if (host.isNullOrBlank()) "未配对" else "$host:${WatchStore.phonePort(this@DiagActivity)}")
            append("\n令牌：").append(WatchStore.token(this@DiagActivity)?.let { "${it.take(6)}…(${it.length}位)" } ?: "无")
            append("\n上次同步：").append(
                if (last == 0L) "从未" else java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(last))
            )
            append("\n待办：").append(WatchStore.pendingTodos(this@DiagActivity).size).append(" 条待完成")
            append("　离线队列：").append(WatchStore.pendingOps(this@DiagActivity).size).append(" 条")
            append("\n每日提醒：").append("%02d:%02d".format(WatchStore.dailyHour(this@DiagActivity), WatchStore.dailyMinute(this@DiagActivity)))
            append("\n屏幕：").append("${dm.widthPixels}×${dm.heightPixels} 密度 ${dm.density} 缩放档 ${WatchStore.uiScale(this@DiagActivity)}")
            append("\n系统：Android ").append(android.os.Build.VERSION.RELEASE)
            append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(") ").append(android.os.Build.MODEL)
            val rt = Runtime.getRuntime()
            append("\n内存：已用 ").append((rt.totalMemory() - rt.freeMemory()) / 1048576).append("MB / 上限 ")
            append(rt.maxMemory() / 1048576).append("MB")
            append("\n日志文件：").append(DLog.filePath() ?: "不可用")
        }

        val lines = DLog.recent(showCount)
        logView.text = if (lines.isEmpty()) "（暂无日志）" else lines.joinToString("\n")
    }

    private fun copyLogs() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("saym-log", DLog.recent(200).joinToString("\n")))
            toast("日志已复制")
        } catch (e: Exception) {
            toast("复制失败：${e.message}")
        }
    }

    private fun exportLogs() {
        val p = DLog.exportToExternal(this)
        if (p != null) {
            DLog.i("日志已导出：$p")
            toast("已导出到：$p")
            render()
        } else {
            toast("导出失败，请直接拍屏幕")
        }
    }

    /** 快速自检：依次试 UDP 发现 / 手填地址 TCP 连接，把每一步结果写进日志 */
    private fun quickCheck() {
        toast("自检中，看下面日志…")
        Thread {
            DLog.i("===== 开始自检 =====")
            // 1) UDP 广播
            PhoneSync.discoverPhone(this)
            // 2) 若已配对，试一次 PULL
            val host = WatchStore.phoneHost(this)
            if (!host.isNullOrBlank()) {
                val r = PhoneSync.sync(this, forceDiscover = false)
                DLog.i("自检同步结果：ok=${r.ok} msg=${r.message}")
            }
            DLog.i("===== 自检结束 =====")
            runOnUiThread { build() }
        }.start()
    }

    private fun toast(s: String) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }
}
