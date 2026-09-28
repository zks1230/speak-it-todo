package com.saym.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.saym.phone.remind.DailyPushScheduler
import com.saym.phone.watch.WatchSyncService

/**
 * 应用入口。
 *
 * ⚠️ 这里**故意不启动前台服务**：
 * Android 12+ 禁止 App 在"还没有任何界面"时启动前台服务（会抛
 * ForegroundServiceStartNotAllowedException 直接崩溃），而 Application.onCreate()
 * 恰好就是这个时刻。局域网同步服务改由 MainActivity（界面已存在）启动。
 *
 * 这里只做两件安全的事：建通知渠道、排闹钟（AlarmManager 没有这个限制）。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        try {
            DailyPushScheduler.schedule(this)
        } catch (e: Exception) {
            // 排闹钟失败不能拖垮启动
            Log.w("SaymApp", "排每日推送闹钟失败：${e.message}")
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_DAILY, getString(R.string.channel_daily_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = getString(R.string.channel_daily_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_KEY, getString(R.string.channel_key_name), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = getString(R.string.channel_key_desc)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 200, 200, 200)
                }
        )
        // 局域网同步的常驻通知：静默、不响不震（省心也省电）
        nm.createNotificationChannel(
            NotificationChannel(
                WatchSyncService.CH_ID,
                getString(R.string.channel_lan_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.channel_lan_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    companion object {
        const val CH_DAILY = "saym_daily"
        const val CH_KEY = "saym_key_task"
        lateinit var instance: App
            private set

        /** 供外部安全检查（不要直接读 instance.isInitialized，那是私有 backing field） */
        fun isReady(): Boolean = ::instance.isInitialized
    }
}
