package com.saym.phone.remind

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.saym.phone.App
import com.saym.phone.MainActivity
import com.saym.phone.Prefs
import com.saym.phone.R
import com.saym.phone.cfg.LocalConfigStore
import com.saym.phone.data.TodoRepo
import com.saym.phone.model.TodoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 通知栏「完成」动作的 action 名 */
const val ACTION_QUICK_DONE = "com.saym.phone.QUICK_DONE"

/**
 * F5.1 每日清单推送 / F5.2 关键任务定点提醒。
 *
 * 新架构要点：数据全在本地，**不再请求任何后端**，所以断网也照常提醒。
 * 每日推送时间来自本地配置中心（F5.5），改完立即重排闹钟。
 */
object DailyPushScheduler {
    private const val REQ_DAILY = 1001

    fun schedule(ctx: Context) {
        val cfg = LocalConfigStore.load(ctx)
        if (cfg.dailyPushEnabled) scheduleAt(ctx, cfg.dailyPushHour, cfg.dailyPushMinute)
        else cancel(ctx)
    }

    fun scheduleAt(ctx: Context, hour: Int, minute: Int) {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDateTime.now(zone)
        var next = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val atMillis = next.atZone(zone).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_DAILY, Intent(ctx, DailyPushReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        setExactOrFallback(ctx, atMillis, pi)
    }

    fun cancel(ctx: Context) {
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_DAILY, Intent(ctx, DailyPushReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        ) ?: return
        (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pi)
    }

    /** 精确闹钟 + 失败回退（Android 12+ 用户可能关掉了「闹钟和提醒」权限） */
    fun setExactOrFallback(ctx: Context, atMillis: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (e: SecurityException) {
            try { am.set(AlarmManager.RTC_WAKEUP, atMillis, pi) } catch (_: Exception) {}
        } catch (_: Exception) {}
    }
}

class DailyPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (LocalConfigStore.load(context).dailyPushEnabled) {
                    dailySummary(context)?.let {
                        showNotification(context, context.getString(R.string.notify_daily_title), it, App.CH_DAILY, 100)
                    }
                }
            } catch (_: Exception) {
            } finally {
                DailyPushScheduler.schedule(context)
                pending.finish()
            }
        }
    }

    companion object {
        /** 今日清单文案（读本地库，断网可用） */
        private suspend fun dailySummary(context: Context): String? {
            val titles = TodoRepo.pendingTitles()
            return when {
                titles.isEmpty() -> "今天没有待办，休息一下"
                else -> "还剩 ${titles.size} 条：\n" +
                    titles.take(8).joinToString("\n") { "• $it" } +
                    (if (titles.size > 8) "\n…还有 ${titles.size - 8} 条" else "")
            }
        }

        /** 进 App 时兜底补推一次（闹钟被系统杀过/错过到点也不会漏，F5.1「不漏推」） */
        suspend fun pullAndNotify(context: Context) {
            if (!LocalConfigStore.load(context).dailyPushEnabled) return
            val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))
            if (Prefs.lastPushedDate(context) == today) return
            val text = dailySummary(context)
            Prefs.setLastPushedDate(context, today)
            if (text != null) {
                showNotification(context, context.getString(R.string.notify_daily_title), text, App.CH_DAILY, 100)
            }
        }
    }
}

/** 关键任务定点提醒接收器（F5.2），同时处理通知栏的「完成」动作（F5.4）。 */
class KeyTaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val todoId = intent.getStringExtra("todoId")
        // 通知栏点了「完成」：直接标完成，不用打开 App
        if (intent.action == ACTION_QUICK_DONE && todoId != null) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    TodoRepo.setCompleted(todoId, true)
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                        ?.cancel(todoId.hashCode())
                } catch (_: Exception) {
                } finally { pending.finish() }
            }
            return
        }
        val title = intent.getStringExtra("title") ?: context.getString(R.string.notify_key_title)
        val id = intent.getIntExtra("notifyId", 1)
        showNotification(context, context.getString(R.string.notify_key_title), title, App.CH_KEY, id, todoId)
        vibrate(context)
    }

    companion object {
        fun vibrate(context: Context) {
            try {
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 200, 200, 200), -1))
                } else @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 200, 200, 200), -1)
            } catch (_: Exception) {}
        }

        fun schedule(ctx: Context, atMillis: Long, title: String, notifyId: Int, todoId: String? = null) {
            if (atMillis <= System.currentTimeMillis()) return
            val intent = Intent(ctx, KeyTaskReminderReceiver::class.java).apply {
                putExtra("title", title)
                putExtra("notifyId", notifyId)
                todoId?.let { putExtra("todoId", it) }
            }
            val pi = PendingIntent.getBroadcast(
                ctx, notifyId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            DailyPushScheduler.setExactOrFallback(ctx, atMillis, pi)
        }

        fun cancel(ctx: Context, notifyId: Int) {
            val pi = PendingIntent.getBroadcast(
                ctx, notifyId, Intent(ctx, KeyTaskReminderReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            ) ?: return
            (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pi)
        }

        /** 按一条关键任务安排定点闹钟（触发时刻 = remindAt - 提前量） */
        fun scheduleForTodo(ctx: Context, t: TodoItem) {
            val at = parseRemindAt(t.remindAt ?: t.deadline) ?: return
            schedule(ctx, at - t.remindOffsetMinutes * 60_000L, t.title, t.id.hashCode(), t.id)
        }

        /**
         * 兼容多种时间写法：
         *  - "yyyy-MM-dd"（只有日期）→ 当天 8:00
         *  - 完整 ISO（带 Z 或带偏移）
         *  - "yyyy-MM-ddTHH:mm:ss" / "yyyy-MM-dd HH:mm:ss"（本地时间）
         */
        fun parseRemindAt(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            val s = raw.trim()
            val zone = ZoneId.of("Asia/Shanghai")
            if (s.length <= 10) {
                return runCatching {
                    LocalDate.parse(s).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
                }.getOrNull()
            }
            runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()?.let { return it }
            runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
            runCatching {
                LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()?.let { return it }
            return runCatching {
                LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }
}

/** 开机 / 应用更新后重排所有闹钟（F5.1、F5.2） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != null && action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        DailyPushScheduler.schedule(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (LocalConfigStore.load(context).keyTaskRemindEnabled) {
                    // 重排所有未完成的关键任务定点提醒
                    // 注意：用枚举而不是旧的 list(3) 魔法数字（加「明日」后序号会错位）
                    TodoRepo.list(com.saym.phone.data.TodoFilter.ALL)
                        .filter { it.isKeyTask && !it.isCompleted && it.remindAt != null }
                        .forEach { KeyTaskReminderReceiver.scheduleForTodo(context, it) }
                }
            } catch (_: Exception) {
            } finally { pending.finish() }
        }
    }
}

/** 通知工具：关键任务通知带「完成」动作按钮，点一下直接标完成，不用打开 App（F5.4） */
private fun showNotification(
    ctx: Context,
    title: String,
    content: String,
    channel: String,
    id: Int,
    todoId: String? = null
) {
    val open = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val builder = NotificationCompat.Builder(ctx, channel)
        .setSmallIcon(R.drawable.ic_saym_notify)
        .setContentTitle(title)
        .setContentText(content)
        .setStyle(NotificationCompat.BigTextStyle().bigText(content))
        .setContentIntent(open)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
    if (todoId != null) {
        val doneIntent = Intent(ctx, KeyTaskReminderReceiver::class.java).apply {
            action = ACTION_QUICK_DONE
            putExtra("todoId", todoId)
            putExtra("notifyId", todoId.hashCode())
        }
        val donePi = PendingIntent.getBroadcast(
            ctx, todoId.hashCode() + 7, doneIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.addAction(0, ctx.getString(R.string.complete), donePi)
    }
    try {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, builder.build())
    } catch (e: SecurityException) { /* 通知权限未授，忽略 */ }
}
