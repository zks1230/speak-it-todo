package com.saym.watch

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 手表端设置。
 *
 * 大部分情况下**不需要进这里**：配对信息由手机经"UDP 发现应答 / 蓝牙配对"自动下发。
 * 这里提供三类兜底：
 *  1. 手填手机 IP / 端口 / 令牌（广播发现不通时的最后手段）；
 *  2. **界面缩放 小/中/大** —— 因为拿不到 DW99 真实屏幕密度，字太小就在这里放大；
 *  3. 每日提醒时间、常用开关。
 */
class SettingsActivity : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchUi.applyScale(this)
        build()
    }

    override fun onResume() {
        super.onResume()
        WatchUi.applyScale(this)
        build()
    }

    private fun build() {
        val scroll = ScrollView(this).apply { setBackgroundColor(WatchUi.BG) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(WatchUi.pad(this@SettingsActivity), WatchUi.pad(this@SettingsActivity),
                WatchUi.pad(this@SettingsActivity), WatchUi.pad(this@SettingsActivity))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(WatchUi.title(this, "设置"))

        // ---- 界面缩放（重要：解决"字太小"的唯一手段） ----
        root.addView(WatchUi.spacer(this, 6))
        val scaleCard = WatchUi.card(this)
        scaleCard.addView(WatchUi.body(this, "界面缩放"))
        scaleCard.addView(WatchUi.muted(this, "如果字太小看不清、或太大显示不全，就在这里调。"))
        scaleCard.addView(WatchUi.spacer(this, 6))
        val cur = WatchStore.uiScale(this)
        scaleCard.addView(WatchUi.row(
            this,
            WatchUi.button(this, "小", primary = cur == 0) { setScale(0) },
            WatchUi.button(this, "中", primary = cur == 1) { setScale(1) },
            WatchUi.button(this, "大", primary = cur == 2) { setScale(2) }
        ))
        root.addView(scaleCard)

        // ---- 配对状态 ----
        root.addView(WatchUi.spacer(this, 6))
        val pairCard = WatchUi.card(this)
        pairCard.addView(WatchUi.body(this, "配对手机"))
        val host = WatchStore.phoneHost(this)
        pairCard.addView(WatchUi.muted(this,
            if (host.isNullOrBlank()) "未配对" else "已配对：$host:${WatchStore.phonePort(this)}\n令牌：${WatchStore.token(this)?.take(6)}…"))
        pairCard.addView(WatchUi.spacer(this, 6))
        pairCard.addView(WatchUi.button(this, "自动配对（广播找手机）", primary = true) {
            toast("正在广播…")
            Thread {
                val r = PhoneSync.sync(this, forceDiscover = true)
                runOnUiThread { toast(r.message); build() }
            }.start()
        })
        pairCard.addView(WatchUi.spacer(this, 6))
        pairCard.addView(WatchUi.button(this, "手动填写手机地址") { manualPair() })
        root.addView(pairCard)

        // ---- 提醒 ----
        root.addView(WatchUi.spacer(this, 6))
        val remindCard = WatchUi.card(this)
        remindCard.addView(WatchUi.body(this, "每日提醒时间"))
        remindCard.addView(WatchUi.muted(this,
            "当前 ${"%02d:%02d".format(WatchStore.dailyHour(this), WatchStore.dailyMinute(this))}。" +
                "到点会震动并弹通知，同时自动同步一次。"))
        remindCard.addView(WatchUi.spacer(this, 6))
        remindCard.addView(WatchUi.row(
            this,
            WatchUi.button(this, "-1 小时") { shiftDaily(-60) },
            WatchUi.button(this, "+1 小时") { shiftDaily(60) }
        ))
        remindCard.addView(WatchUi.spacer(this, 6))
        remindCard.addView(WatchUi.button(this, "立即测试提醒（震动+通知）") {
            WatchNotify.vibrate(this, longArrayOf(0, 400, 200, 400))
            WatchNotify.notify(this, "测试提醒", "如果你看到/感觉到这条，说明提醒是通的", 999)
            toast("已发送测试提醒")
        })
        root.addView(remindCard)

        // ---- 常驻通知 ----
        root.addView(WatchUi.spacer(this, 6))
        val svcCard = WatchUi.card(this)
        svcCard.addView(WatchUi.body(this, "任务栏常驻通知"))
        svcCard.addView(WatchUi.muted(this,
            "开启后通知栏会一直显示「还剩 N 条」，抬腕即可看到。\n" +
                "代价：需要维持一个前台服务，会稍微费电（这是你选的方案）。"))
        svcCard.addView(WatchUi.spacer(this, 6))
        svcCard.addView(WatchUi.row(
            this,
            WatchUi.button(this, "开启", primary = true) { WatchService.start(this); toast("已开启") },
            WatchUi.button(this, "关闭") { WatchService.stop(this); toast("已关闭") }
        ))
        root.addView(svcCard)

        // ---- 设备信息 ----
        root.addView(WatchUi.spacer(this, 6))
        val infoCard = WatchUi.card(this)
        infoCard.addView(WatchUi.body(this, "本机信息"))
        val dm = resources.displayMetrics
        infoCard.addView(WatchUi.muted(this,
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n" +
                "机型 ${android.os.Build.MODEL}\n" +
                "屏幕 ${dm.widthPixels}×${dm.heightPixels} 密度 ${dm.density}\n" +
                "设备 ID ${WatchStore.deviceId(this)}"))
        root.addView(infoCard)
    }

    private fun setScale(v: Int) {
        WatchStore.setUiScale(this, v)
        DLog.i("界面缩放改为 $v")
        WatchUi.applyScale(this)
        build()
    }

    private fun shiftDaily(deltaMinutes: Int) {
        var h = WatchStore.dailyHour(this)
        var m = WatchStore.dailyMinute(this)
        var total = (h * 60 + m + deltaMinutes + 1440) % 1440
        h = total / 60; m = total % 60
        WatchStore.setDailyTime(this, h, m)
        Alarms.scheduleDaily(this, h, m)
        DLog.i("每日提醒时间改为 %02d:%02d".format(h, m))
        build()
    }

    private fun manualPair() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val eHost = EditText(this).apply {
            hint = "手机 IP，如 192.168.1.5"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(WatchStore.phoneHost(this@SettingsActivity) ?: "")
        }
        val ePort = EditText(this).apply {
            hint = "端口（默认 38313）"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(WatchStore.phonePort(this@SettingsActivity).toString())
        }
        val eToken = EditText(this).apply {
            hint = "配对令牌（手机 设置→手表 页可看到）"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(WatchStore.token(this@SettingsActivity) ?: "")
        }
        box.addView(eHost); box.addView(ePort); box.addView(eToken)

        android.app.AlertDialog.Builder(this)
            .setTitle("手动填写手机地址")
            .setMessage("手机端：设置 → 手表，能看到局域网地址和配对令牌。")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                WatchStore.savePairing(
                    this,
                    eHost.text.toString().trim(),
                    ePort.text.toString().trim().toIntOrNull() ?: 38313,
                    eToken.text.toString().trim(),
                    "手机"
                )
                toast("已保存")
                build()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }
}
