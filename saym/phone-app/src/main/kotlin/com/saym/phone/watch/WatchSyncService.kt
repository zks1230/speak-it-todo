package com.saym.phone.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.saym.phone.MainActivity
import com.saym.phone.R

/**
 * 局域网同步服务（前台服务保活）。
 *
 * 为什么需要前台服务：
 *  - 手机一进入后台，普通服务随时可能被杀，手表就连不上手机了；
 *  - 前台服务带一个常驻通知，是 Android 上长期提供局域网服务的正规做法。
 *
 * 省电考虑（你明确要求手表不能太耗电，手机端也不该白耗）：
 *  - 这个服务**不轮询**：只在有手表连进来时才干活（ServerSocket.accept 阻塞等待，几乎不耗电）；
 *  - 通知写成低优先级（IMPORTANCE_MIN / 静默），不响不震不亮屏。
 */
class WatchSyncService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        WatchServer.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统重启后也能继续提供服务
        WatchServer.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        WatchServer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CH_ID, getString(R.string.channel_lan_name), NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.channel_lan_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ip = WatchServer.localIpv4() ?: "（未连 WiFi）"
        val n: Notification = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_saym_notify)
            .setContentTitle(getString(R.string.lan_notify_title))
            .setContentText(getString(R.string.lan_notify_text, ip, WatchServer.PORT_PUBLIC))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            // 前台服务被系统拒绝（个别精简 ROM）：退化为普通服务，至少 App 在前台时手表能同步
            try { startForeground(ID, n) } catch (_: Exception) {}
        }
    }

    companion object {
        const val CH_ID = "saym_lan_sync"
        private const val ID = 2001

        fun start(ctx: Context) {
            val i = Intent(ctx, WatchSyncService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, WatchSyncService::class.java)) } catch (_: Exception) {}
        }
    }
}
