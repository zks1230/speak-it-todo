package com.saym.watch

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 全屏到点提醒（F5.4 快速操作）。
 *
 * 手表屏幕小，到点时直接把界面占满，只给三个大按钮：
 * 「完成」/「推迟 10 分钟」/「知道了」，避免用户还要自己在列表里翻。
 */
class ReminderActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchUi.applyScale(this)
        val id = intent.getStringExtra("id") ?: ""
        val title = intent.getStringExtra("title") ?: "该做这件事了"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(WatchUi.pad(this@ReminderActivity), WatchUi.pad(this@ReminderActivity),
                WatchUi.pad(this@ReminderActivity), WatchUi.pad(this@ReminderActivity))
            setBackgroundColor(WatchUi.BG)
        }
        root.addView(TextView(this).apply {
            text = "该做了"
            textSize = WatchUi.fontSmall()
            setTextColor(WatchUi.PRIMARY)
            gravity = Gravity.CENTER
        })
        root.addView(WatchUi.spacer(this, 6))
        root.addView(TextView(this).apply {
            text = title
            textSize = WatchUi.fontTitle()
            setTextColor(WatchUi.TEXT)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(WatchUi.spacer(this, 12))

        root.addView(WatchUi.button(this, "完成", primary = true) {
            Thread {
                val r = PhoneSync.setCompleted(this, id, true)
                runOnUiThread {
                    toast(r.message)
                    WatchService.refresh(this)
                    finish()
                }
            }.start()
        })
        root.addView(WatchUi.spacer(this, 6))
        root.addView(WatchUi.button(this, "推迟 10 分钟") {
            Alarms.scheduleKeyTask(this, id, title, System.currentTimeMillis() + 10 * 60 * 1000L)
            toast("已推迟 10 分钟")
            finish()
        })
        root.addView(WatchUi.spacer(this, 6))
        root.addView(WatchUi.button(this, "知道了") { finish() })

        setContentView(root)
        WatchNotify.vibrate(this, longArrayOf(0, 400, 200, 400))
        DLog.i("全屏提醒已展示：$title")
    }

    private fun toast(s: String) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }
}
