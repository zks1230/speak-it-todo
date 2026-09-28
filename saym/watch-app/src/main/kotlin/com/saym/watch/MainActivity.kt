package com.saym.watch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 手表端主界面：今日待办列表 + 完成 + 推迟 + 进入设置/诊断。
 *
 * 功能对应 §7.2 手表定位：只做「看 + 完成 + 推迟 + 收提醒」，不做输入、不做 AI。
 *  - 打开 App 自动同步一次（**不轮询**，省电）
 *  - 每条待办：一键「完成」/「推迟10分」
 *  - 刚完成的显示在下方，可撤销（小屏容易误触，必须给后悔药）
 *  - 常驻通知由 [WatchService] 维护（用户选择：任务栏常驻）
 */
class MainActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DLog.init(this)
        WatchUi.applyScale(this)
        DLog.i("MainActivity 创建，界面缩放=${WatchStore.uiScale(this)}")
        buildUi()
        WatchService.start(this)
        Alarms.scheduleDaily(this, WatchStore.dailyHour(this), WatchStore.dailyMinute(this))
        render()
        syncAsync(forceDiscover = false)   // 打开就同步一次
    }

    override fun onResume() {
        super.onResume()
        WatchUi.applyScale(this)
        render()
    }

    // ============ UI ============

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(WatchUi.pad(this@MainActivity), WatchUi.pad(this@MainActivity),
                WatchUi.pad(this@MainActivity), WatchUi.pad(this@MainActivity))
            setBackgroundColor(WatchUi.BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusView = TextView(this).apply {
            textSize = WatchUi.fontTitle()
            setTextColor(WatchUi.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(statusView)
        header.addView(WatchUi.button(this, "同步") { syncAsync(forceDiscover = true) })
        root.addView(header)

        val sv = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(listContainer)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(WatchUi.row(
            this,
            WatchUi.button(this, "设置") { startActivity(Intent(this, SettingsActivity::class.java)) },
            WatchUi.button(this, "诊断") { startActivity(Intent(this, DiagActivity::class.java)) }
        ))

        setContentView(root)
    }

    private fun render() {
        val phone = WatchStore.phoneHost(this)
        val pending = WatchStore.pendingTodos(this)
        val ops = WatchStore.pendingOps(this).size
        statusView.text = if (pending.isEmpty()) "今日无待办" else "还剩 ${pending.size} 条"

        listContainer.removeAllViews()

        // ---- 状态卡 ----
        val statusCard = WatchUi.card(this)
        if (phone.isNullOrBlank()) {
            statusCard.addView(WatchUi.body(this, "还没配对手机"))
            statusCard.addView(WatchUi.muted(this, "点下面的按钮，手表会广播找手机（两边要连同一个 WiFi，或手表连手机热点）。"))
            statusCard.addView(WatchUi.spacer(this, 6))
            statusCard.addView(WatchUi.button(this, "开始配对（广播找手机）", primary = true) {
                syncAsync(forceDiscover = true)
            })
        } else {
            val last = WatchStore.lastSyncAt(this)
            val whenText = if (last == 0L) "从未同步" else {
                val s = (System.currentTimeMillis() - last) / 1000
                when {
                    s < 60 -> "${s} 秒前"
                    s < 3600 -> "${s / 60} 分钟前"
                    else -> "${s / 3600} 小时前"
                }
            }
            statusCard.addView(WatchUi.muted(this,
                "手机 ${WatchStore.phoneName(this) ?: ""} @ $phone:${WatchStore.phonePort(this)}"))
            statusCard.addView(WatchUi.muted(this,
                "上次同步：$whenText" + if (ops > 0) "　待补交 $ops 条" else ""))
            WatchStore.lastSyncResult(this)?.let { statusCard.addView(WatchUi.muted(this, it)) }
        }
        listContainer.addView(statusCard)

        // ---- 待办列表 ----
        if (pending.isEmpty()) {
            val card = WatchUi.card(this)
            card.addView(WatchUi.empty(this,
                if (phone.isNullOrBlank()) "先配对手机才能看到待办" else "没有待办了 🎉"))
            listContainer.addView(card)
        } else {
            pending.forEach { t ->
                val card = WatchUi.card(this)
                card.addView(TextView(this).apply {
                    text = (if (t.isKeyTask) "★ " else "") + t.title
                    textSize = WatchUi.fontItem()
                    setTextColor(WatchUi.TEXT)
                })
                val sub = buildString {
                    t.deadline?.let { append(it.take(10)) }
                    t.subject?.let { if (isNotEmpty()) append(" · "); append(it) }
                    if (t.remindAt != null) { if (isNotEmpty()) append(" · "); append("⏰") }
                }
                if (sub.isNotEmpty()) card.addView(WatchUi.muted(this, sub))
                card.addView(WatchUi.spacer(this, 6))
                card.addView(WatchUi.row(
                    this,
                    WatchUi.button(this, "完成", primary = true) { complete(t, true) },
                    WatchUi.button(this, "推迟10分") { postpone(t) }
                ))
                listContainer.addView(card)
            }
        }

        // ---- 刚完成的（可撤销） ----
        val completed = WatchStore.loadTodos(this).filter { it.isCompleted && !it.deleted }.take(3)
        if (completed.isNotEmpty()) {
            val card = WatchUi.card(this)
            card.addView(WatchUi.muted(this, "刚完成的（可撤销）"))
            completed.forEach { t ->
                card.addView(WatchUi.spacer(this, 4))
                card.addView(WatchUi.row(
                    this,
                    WatchUi.body(this, t.title),
                    WatchUi.button(this, "撤销") { complete(t, false) }
                ))
            }
            listContainer.addView(card)
        }
    }

    // ============ 操作 ============

    private fun complete(t: Todo, done: Boolean) {
        Thread {
            val r = PhoneSync.setCompleted(this, t.id, done)
            runOnUiThread {
                toast(r.message)
                render()
                WatchService.refresh(this)
            }
        }.start()
    }

    /** 推迟 10 分钟：纯本地闹钟，不需要网络 */
    private fun postpone(t: Todo) {
        val at = System.currentTimeMillis() + 10 * 60 * 1000L
        Alarms.scheduleKeyTask(this, t.id, t.title, at)
        WatchNotify.vibrate(this, longArrayOf(0, 80, 80, 80))
        toast("已推迟 10 分钟提醒")
        DLog.i("推迟提醒：${t.title}")
    }

    private fun syncAsync(forceDiscover: Boolean) {
        toast(if (forceDiscover) "正在广播找手机…" else "正在同步…")
        statusView.text = "同步中…"
        Thread {
            val r = PhoneSync.sync(this, forceDiscover)
            runOnUiThread {
                toast(r.message)
                render()
                WatchService.refresh(this)
                if (r.ok) Alarms.rescheduleAllFromCache(this)
            }
        }.start()
    }

    private fun toast(s: String) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }
}
