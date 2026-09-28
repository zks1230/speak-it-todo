package com.saym.watch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.Locale

/**
 * 闹钟排程（F5.1 每日 / F5.2 定点）。
 *
 * 精确闹钟在 Android 12+ 需要用户授权，被关掉时**不能崩**，所以统一走
 * [setExactOrFallback]：能精确就精确，不能就退化为 setAndAllowWhileIdle。
 */
object Alarms {

    private const val REQ_DAILY = 900
    private const val REQ_KEY = 901

    fun setExactOrFallback(c: Context, atMillis: Long, pi: PendingIntent) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                DLog.i("已设精确闹钟：${fmt(atMillis)}")
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                DLog.w("系统不允许精确闹钟，已退化为非精确：${fmt(atMillis)}")
            }
        } catch (e: SecurityException) {
            try { am.set(AlarmManager.RTC_WAKEUP, atMillis, pi) } catch (_: Exception) {}
            DLog.w("精确闹钟被拒绝（SecurityException），已退化")
        } catch (e: Exception) {
            DLog.e("排闹钟失败", e)
        }
    }

    /** 每日固定时刻拉取并提醒 */
    fun scheduleDaily(c: Context, hour: Int, minute: Int) {
        val cal = Calendar.getInstance().apply {
            if (get(Calendar.HOUR_OF_DAY) > hour ||
                (get(Calendar.HOUR_OF_DAY) == hour && get(Calendar.MINUTE) >= minute)
            ) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val pi = PendingIntent.getBroadcast(
            c, REQ_DAILY, Intent(c, DailyReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        setExactOrFallback(c, cal.timeInMillis, pi)
    }

    /** 关键任务定点提醒（触发时刻 = remindAt - 提前量） */
    fun scheduleKeyTask(c: Context, id: String, title: String, atMillis: Long) {
        if (atMillis <= System.currentTimeMillis()) return
        val pi = PendingIntent.getBroadcast(
            c, REQ_KEY + (id.hashCode() and 0xFFFF),
            Intent(c, KeyReceiver::class.java).apply {
                putExtra("id", id)
                putExtra("title", title)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        setExactOrFallback(c, atMillis, pi)
    }

    /**
     * 按本地缓存的待办重排所有关键任务提醒。
     * 每次同步成功后调用，保证提醒跟着数据走。
     */
    fun rescheduleAllFromCache(c: Context) {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        var n = 0
        WatchStore.pendingTodos(c)
            .filter { it.isKeyTask && it.remindAt != null }
            .forEach { t ->
                val base = parseTime(t.remindAt!!, zone) ?: return@forEach
                val at = base - t.remindOffsetMinutes * 60_000L
                if (at > System.currentTimeMillis()) {
                    scheduleKeyTask(c, t.id, t.title, at)
                    n++
                }
            }
        DLog.i("已重排关键任务提醒 $n 条")
    }

    /**
     * 兼容多种时间写法（和手机端一致）：
     *  - "yyyy-MM-dd" → 当天 8:00
     *  - 完整 ISO / 带偏移 / "yyyy-MM-ddTHH:mm:ss"
     */
    fun parseTime(raw: String, zone: java.time.ZoneId): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.length <= 10) {
            return runCatching {
                java.time.LocalDate.parse(s).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
        }
        runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        runCatching {
            java.time.LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        return runCatching {
            java.time.LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun fmt(ms: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(java.util.Date(ms))
}

/** 每日定时：拉一次 + 震动提醒 + 重排下一天 */
class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DLog.i("每日定时触发")
        val pendingResult = goAsync()
        Thread {
            try {
                val r = PhoneSync.sync(context)
                DLog.i("每日同步：${r.message}")
                WatchService.refresh(context)
                val n = WatchStore.pendingTodos(context).size
                if (n > 0) {
                    WatchNotify.vibrate(context)
                    WatchNotify.notify(context, "今日待办", "还剩 $n 条，点开查看", 200)
                }
            } catch (e: Exception) {
                DLog.e("每日同步异常", e)
            } finally {
                Alarms.scheduleDaily(context, WatchStore.dailyHour(context), WatchStore.dailyMinute(context))
                pendingResult.finish()
            }
        }.start()
    }
}

/** 关键任务到点：震动 + 通知 + 全屏提醒页 */
class KeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: ""
        val title = intent.getStringExtra("title") ?: "关键任务"
        DLog.i("关键任务到点：$title")
        WatchNotify.vibrate(context, longArrayOf(0, 400, 200, 400))
        WatchNotify.notify(context, "该做了", title, id.hashCode() and 0xFFFF)
        try {
            context.startActivity(Intent(context, ReminderActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("id", id)
                putExtra("title", title)
            })
        } catch (e: Exception) {
            DLog.w("全屏提醒启动失败（可能被系统限制后台启动界面）：${e.message}")
        }
    }
}

/** 开机 / 应用更新后重排闹钟并刷新通知 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != null &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        DLog.i("开机/更新事件：$action —— 重排闹钟")
        DLog.init(context)
        Alarms.scheduleDaily(context, WatchStore.dailyHour(context), WatchStore.dailyMinute(context))
        Alarms.rescheduleAllFromCache(context)
        WatchService.start(context)
    }
}
