package com.saym.phone.data

import android.content.Context
import com.saym.phone.db.InputLocal
import com.saym.phone.db.SaymDb
import com.saym.phone.db.TodoLocal
import com.saym.phone.model.InputType
import com.saym.phone.model.TodoCreateReq
import com.saym.phone.model.TodoItem
import com.saym.phone.model.TodoUpdateReq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 首页分组（F3.1）。
 *
 * 用枚举而不是 int 序号：以前是 `TodoRepo.list(3)` 这种魔法数字，
 * 一加「明日」就会让所有序号错位，而且错了还不报错。枚举以后不会再错。
 */
enum class TodoFilter(val label: String) {
    TODAY("今日"),
    TOMORROW("明日"),
    WEEK("本周"),
    OVERDUE("逾期"),
    ALL("全部")
}

/**
 * 待办仓库：**所有读写都走这里**，是手机端唯一的数据入口。
 *
 * 设计原则（对应你选的"手机本地做主库"）：
 *  - 增删改一律先落本地 Room，**立即返回**，界面不等待任何网络；
 *  - 落库后由 [com.saym.phone.watch.WatchPusher] 尽力推给手表（推失败不影响本地成功）；
 *  - 时间统一用 ISO-8601 UTC 字符串，截止时间允许 `yyyy-MM-dd` 或完整 ISO。
 */
object TodoRepo {
    private fun dao() = SaymDb.get().todoDao()
    private fun inputDao() = SaymDb.get().inputDao()

    private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    private fun today(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
    private fun tomorrow(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString()

    // ============ 查询 ============

    /** 首页分组查询（F3.1） */
    suspend fun list(filter: TodoFilter): List<TodoItem> = withContext(Dispatchers.IO) {
        val d = dao()
        val rows = when (filter) {
            TodoFilter.TODAY -> d.today(today())
            TodoFilter.TOMORROW -> d.tomorrow(tomorrow())
            TodoFilter.WEEK -> d.weekUpcoming(weekEnd())
            TodoFilter.OVERDUE -> d.overdue(today())
            TodoFilter.ALL -> d.all()
        }
        rows.map { it.toItem() }
    }

    /** 手表要的全量（含 tombstone） */
    suspend fun allForSync(): List<TodoLocal> = withContext(Dispatchers.IO) { dao().allForSync() }

    suspend fun byId(id: String): TodoItem? = withContext(Dispatchers.IO) { dao().byId(id)?.toItem() }

    /** 手表通知栏用的「今天还剩什么」（只取未完成） */
    suspend fun pendingTitles(): List<String> = withContext(Dispatchers.IO) {
        dao().pending().map { it.title }
    }

    suspend fun countPending(): Int = withContext(Dispatchers.IO) { dao().pendingCount() }

    // ============ 增 ============

    suspend fun create(req: TodoCreateReq, originDevice: String = "phone", id: String? = null): TodoItem =
        withContext(Dispatchers.IO) {
            val now = nowIso()
            val item = TodoLocal(
                id = id ?: UUID.randomUUID().toString(),
                title = req.title.trim(),
                deadline = req.deadline?.trim()?.ifBlank { null },
                subject = req.subject?.trim()?.ifBlank { null },
                notes = req.notes?.trim()?.ifBlank { null },
                sourceInputType = req.sourceInputType.name,
                sourceInputRaw = req.sourceInputRaw?.take(2000),
                isCompleted = false,
                completedAt = null,
                isKeyTask = req.isKeyTask,
                remindAt = req.remindAt?.trim()?.ifBlank { null },
                remindOffsetMinutes = req.remindOffsetMinutes,
                createdAt = now,
                updatedAt = now,
                originDevice = originDevice,
                deleted = false,
                deletedAt = null
            )
            dao().upsert(item)
            item.toItem()
        }

    // ============ 改 ============

    suspend fun update(id: String, req: TodoUpdateReq, originDevice: String = "phone"): TodoItem? =
        withContext(Dispatchers.IO) {
            val cur = dao().byId(id) ?: return@withContext null
            if (cur.deleted) return@withContext null
            val now = nowIso()
            val becameCompleted = req.isCompleted == true && !cur.isCompleted
            val updated = cur.copy(
                title = req.title?.takeIf { it.isNotBlank() } ?: cur.title,
                deadline = if (req.clearDeadline) null else (req.deadline?.ifBlank { null } ?: cur.deadline),
                subject = if (req.subject != null) req.subject.ifBlank { null } else cur.subject,
                notes = if (req.notes != null) req.notes.ifBlank { null } else cur.notes,
                isCompleted = req.isCompleted ?: cur.isCompleted,
                completedAt = when {
                    becameCompleted -> now
                    req.isCompleted == false -> null
                    else -> cur.completedAt
                },
                isKeyTask = req.isKeyTask ?: cur.isKeyTask,
                remindAt = if (req.clearRemindAt) null else (req.remindAt?.ifBlank { null } ?: cur.remindAt),
                remindOffsetMinutes = req.remindOffsetMinutes ?: cur.remindOffsetMinutes,
                updatedAt = now,
                originDevice = originDevice
            )
            dao().upsert(updated)
            updated.toItem()
        }

    /** 单条完成/撤销 */
    suspend fun setCompleted(id: String, done: Boolean) = withContext(Dispatchers.IO) {
        dao().setCompleted(id, done, nowIso())
    }

    /** F3.2 批量完成 */
    suspend fun setCompletedBatch(ids: List<String>, done: Boolean): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) 0 else dao().setCompletedBatch(ids, done, nowIso())
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao().softDelete(id, nowIso())
    }

    // ============ 手表回写（originDevice = "watch"） ============

    /** 手表标记完成时调用；本地优先更新，不依赖网络 */
    suspend fun completeFromWatch(id: String, done: Boolean) = withContext(Dispatchers.IO) {
        dao().setCompleted(id, done, nowIso())
    }

    // ============ F1.4 输入暂存 ============

    suspend fun addInput(type: InputType, text: String?, imageRef: String? = null): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            inputDao().upsert(
                InputLocal(
                    id = id,
                    type = type.name,
                    text = text,
                    imageRef = imageRef,
                    createdAt = nowIso(),
                    processed = false,
                    extractedTodoIds = null
                )
            )
            id
        }

    suspend fun listInputs(): List<InputLocal> = withContext(Dispatchers.IO) { inputDao().listRecent(200) }

    suspend fun inputById(id: String): InputLocal? = withContext(Dispatchers.IO) { inputDao().byId(id) }

    suspend fun markInputProcessed(id: String, todoIds: List<String>) = withContext(Dispatchers.IO) {
        inputDao().markProcessed(id, todoIds.joinToString(","))
    }

    /** 30 天保留策略：清理更早的本地暂存 */
    suspend fun purgeOldInputs(days: Long = 30) = withContext(Dispatchers.IO) {
        val cutoff = Instant.now().minusSeconds(days * 86400).toString()
        inputDao().purgeBefore(cutoff)
    }

    // ============ 统计（F3.8，全部本地算，不需要网络） ============

    data class Stats(
        val weekCompleted: Int,
        val weekOverdue: Int,
        val completionRate: Float,
        val streakDays: Int,
        val totalPending: Int
    )

    suspend fun stats(): Stats = withContext(Dispatchers.IO) {
        val all = dao().all()
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.now(zone)
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        val sunday = monday.plusDays(6)
        fun dateOf(iso: String?): LocalDate? =
            iso?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }

        val weekCompleted = all.count { it.isCompleted && dateOf(it.completedAt)?.let { d -> !d.isBefore(monday) && !d.isAfter(sunday) } == true }
        val weekOverdue = all.count { !it.isCompleted && it.deadline != null && it.deadline!!.take(10) < today.toString() }
        val totalCompleted = all.count { it.isCompleted }
        val totalPending = all.count { !it.isCompleted }
        val total = totalCompleted + totalPending
        val rate = if (total == 0) 0f else totalCompleted.toFloat() / total

        // 连续完成天数：从今天往前，每天至少完成 1 条
        val doneDays = all.filter { it.isCompleted }.mapNotNull { dateOf(it.completedAt) }.toSet()
        var streak = 0
        var cur = today
        while (doneDays.contains(cur) && streak < 365) {
            streak++
            cur = cur.minusDays(1)
        }
        Stats(weekCompleted, weekOverdue, rate, streak, totalPending)
    }

    private fun weekEnd(): String {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.now(zone)
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        return monday.plusDays(6).toString()
    }

    private fun weekStart(): String {
        val zone = ZoneId.of("Asia/Shanghai")
        return LocalDate.now(zone).with(java.time.DayOfWeek.MONDAY).toString()
    }
}

/** 本地实体 → UI 模型 */
fun TodoLocal.toItem(): TodoItem = TodoItem(
    id = id, title = title, deadline = deadline, subject = subject, notes = notes,
    sourceInputType = runCatching { InputType.valueOf(sourceInputType) }.getOrDefault(InputType.TEXT),
    sourceInputRaw = sourceInputRaw,
    isCompleted = isCompleted, completedAt = completedAt, isKeyTask = isKeyTask,
    remindAt = remindAt, remindOffsetMinutes = remindOffsetMinutes,
    createdAt = createdAt, updatedAt = updatedAt, deviceId = originDevice,
    deleted = deleted, deletedAt = deletedAt
)
