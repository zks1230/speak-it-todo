package com.saym.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 通知与震动工具。
 *
 * 通道设计：
 *  - [CH_TASKS] 「待办」：常驻通知用，**低优先级、不响不震**（省电，用户选了常驻）
 *  - [CH_REMIND]「提醒」：到点提醒用，高优先级 + 震动
 */
object WatchNotify {
    const val CH_TASKS = "saym_tasks"
    const val CH_REMIND = "saym_remind"

    fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_TASKS, "今日待办", NotificationManager.IMPORTANCE_LOW).apply {
                description = "常驻显示还剩哪些待办"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "到点提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "到点提醒该做什么"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 300)
            }
        )
    }

    fun vibrate(c: Context, pattern: LongArray = longArrayOf(0, 300, 150, 300)) {
        try {
            val v = c.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            } else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
        } catch (_: Exception) {}
    }

    /** 普通通知（到点提醒） */
    fun notify(c: Context, title: String, content: String, id: Int, channel: String = CH_REMIND) {
        ensureChannels(c)
        val pi = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(c, channel) else @Suppress("DEPRECATION") Notification.Builder(c)
        val n = builder
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        } catch (_: SecurityException) {}
    }
}

/**
 * 任务栏常驻通知服务（你选的方案）。
 *
 * ⚠️ 代价说明：常驻通知必须由**前台服务**维持，会占一点内存和电。
 * 因此这里**不做任何轮询**：只在下面三个时机刷新通知内容——
 *   ① 服务启动时  ② 用户同步完成后（外部调用 [refresh]）  ③ 用户完成某条后
 *
 * 通知样式：
 *  标题：今日还剩 N 条
 *  内容：前几条待办（最多 5 条）
 *  操作：一个「同步」按钮（点了立刻去拉一次）
 */
class WatchService : Service() {

    override fun onCreate() {
        super.onCreate()
        DLog.init(this)
        DLog.i("常驻通知服务启动")
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            DLog.i("通知栏点了「同步」")
            Thread { doSyncAndRefresh(this) }.start()
        } else {
            refresh(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        DLog.w("常驻通知服务被销毁（系统可能杀了后台）")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely() {
        WatchNotify.ensureChannels(this)
        val n = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            // 个别精简 ROM 拒绝前台服务类型：退化为普通 startForeground
            try { startForeground(ID, n) } catch (e2: Exception) {
                DLog.w("前台服务被系统拒绝：${e2.message}")
            }
        }
    }

    companion object {
        const val ID = 1001
        const val ACTION_REFRESH = "com.saym.watch.REFRESH"

        fun start(ctx: Context) {
            val i = Intent(ctx, WatchService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                DLog.w("启动常驻服务失败：${e.message}")
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, WatchService::class.java)) } catch (_: Exception) {}
        }

        /** 重新构建并更新常驻通知（同步完成后调用） */
        fun refresh(ctx: Context) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(ID, buildNotification(ctx))
            } catch (e: Exception) {
                DLog.w("刷新通知失败：${e.message}")
            }
        }

        fun doSyncAndRefresh(ctx: Context) {
            val r = PhoneSync.sync(ctx)
            DLog.i("通知触发的同步结果：${r.message}")
            refresh(ctx)
        }

        /** 常驻通知内容：还剩几条 + 前 5 条标题 */
        private fun buildNotification(ctx: Context): Notification {
            WatchNotify.ensureChannels(ctx)
            val pending = WatchStore.pendingTodos(ctx)
            val n = pending.size
            val title = if (n == 0) "今日没有待办" else "还剩 $n 条待办"
            val body = if (n == 0) {
                val last = WatchStore.lastSyncAt(ctx)
                if (last == 0L) "还没同步过，点「同步」连接手机"
                else "上次同步：${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(last))}"
            } else {
                pending.take(5).joinToString("\n") { t ->
                    val star = if (t.isKeyTask) "★ " else "· "
                    val dl = t.deadline?.let { " (${it.take(10)})" } ?: ""
                    "$star${t.title}$dl"
                } + if (n > 5) "\n…还有 ${n - 5} 条" else ""
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val syncPi = PendingIntent.getService(
                ctx, 1,
                Intent(ctx, WatchService::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(ctx, WatchNotify.CH_TASKS)
            else @Suppress("DEPRECATION") Notification.Builder(ctx)

            builder
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(body.lineSequence().firstOrNull() ?: "")
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setOngoing(true)              // 常驻
                .setShowWhen(false)
                .setOnlyAlertOnce(true)        // 更新时不响不震（省电关键）
                .addAction(0, "同步", syncPi)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setPriority(Notification.PRIORITY_LOW)
            }
            return builder.build()
        }
    }
}
