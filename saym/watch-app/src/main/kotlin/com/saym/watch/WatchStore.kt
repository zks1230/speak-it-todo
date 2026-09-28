package com.saym.watch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 手表端本地存储：SharedPreferences + JSON 字符串。
 * 刻意不引 Room（手表内存小、APK 要小），待办量级就几十条，JSON 足够。
 */
object WatchStore {
    private const val PREF = "saym_watch"

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ============ 身份 ============

    fun deviceId(c: Context): String {
        sp(c).getString("device_id", null)?.let { return it }
        val id = "watch-" + UUID.randomUUID().toString().take(13)
        sp(c).edit().putString("device_id", id).apply()
        return id
    }

    // ============ 配对信息（手机下发或手填） ============

    /** 手机局域网地址；为空表示还没配对 */
    fun phoneHost(c: Context): String? = sp(c).getString("phone_host", null)
    fun phonePort(c: Context): Int = sp(c).getInt("phone_port", 38313)
    fun token(c: Context): String? = sp(c).getString("token", null)
    fun phoneName(c: Context): String? = sp(c).getString("phone_name", null)

    fun savePairing(c: Context, host: String?, port: Int, token: String?, name: String?) {
        sp(c).edit().apply {
            host?.let { putString("phone_host", it) }
            putInt("phone_port", port)
            token?.let { putString("token", it) }
            name?.let { putString("phone_name", it) }
            putLong("paired_at", System.currentTimeMillis())
        }.apply()
    }

    fun clearPairing(c: Context) {
        sp(c).edit().remove("phone_host").remove("token").remove("phone_name").apply()
    }

    // ============ 同步状态 ============

    fun lastSyncAt(c: Context): Long = sp(c).getLong("last_sync_at", 0L)
    fun setLastSyncAt(c: Context, v: Long) {
        sp(c).edit().putLong("last_sync_at", v).apply()
    }
    fun lastSyncResult(c: Context): String? = sp(c).getString("last_sync_result", null)
    fun setLastSyncResult(c: Context, v: String) {
        sp(c).edit().putString("last_sync_result", v).apply()
    }

    // ============ 待办缓存 ============

    fun saveTodos(c: Context, itemsJson: String) {
        sp(c).edit().putString("todos", itemsJson).apply()
    }

    fun loadTodos(c: Context): List<Todo> {
        val raw = sp(c).getString("todos", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Todo.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) {
            DLog.w("本地待办解析失败：${e.message}")
            emptyList()
        }
    }

    /** 未完成的待办（首页列表 + 通知栏用） */
    fun pendingTodos(c: Context): List<Todo> =
        loadTodos(c).filter { !it.isCompleted && !it.deleted }

    /** 本地标记完成（先改本地，保证断网也有反馈） */
    fun markCompletedLocally(c: Context, id: String, done: Boolean) {
        val list = loadTodos(c).map { if (it.id == id) it.copy(isCompleted = done) else it }
        saveTodos(c, JSONArray(list.map { it.toJson() }).toString())
    }

    /** 用同步回来的数据合并（远端 updatedAt 更新才覆盖本地） */
    fun mergeFromServer(c: Context, incoming: List<Todo>) {
        val local = loadTodos(c).associateBy { it.id }.toMutableMap()
        incoming.forEach { remote ->
            val cur = local[remote.id]
            if (cur == null || remote.updatedAt >= cur.updatedAt) local[remote.id] = remote
        }
        saveTodos(c, JSONArray(local.values.map { it.toJson() }).toString())
    }

    // ============ 离线回写队列（F4.4） ============

    /** 手表上的完成/取消操作先排队，同步成功后清掉 */
    fun enqueueOp(c: Context, id: String, done: Boolean) {
        val set = pendingOps(c).toMutableMap()
        set[id] = done
        val arr = JSONArray()
        set.forEach { (k, v) -> arr.put(JSONObject().put("id", k).put("done", v)) }
        sp(c).edit().putString("pending_ops", arr.toString()).apply()
        DLog.i("离线队列 +1：${if (done) "完成" else "取消完成"} $id（当前 ${set.size} 条）")
    }

    fun pendingOps(c: Context): Map<String, Boolean> {
        val raw = sp(c).getString("pending_ops", null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).associate {
                val o = arr.getJSONObject(it)
                o.optString("id") to o.optBoolean("done", true)
            }
        } catch (e: Exception) { emptyMap() }
    }

    fun clearOps(c: Context, synced: Collection<String>) {
        if (synced.isEmpty()) return
        val rest = pendingOps(c).filterKeys { it !in synced }
        val arr = JSONArray()
        rest.forEach { (k, v) -> arr.put(JSONObject().put("id", k).put("done", v)) }
        sp(c).edit().putString("pending_ops", arr.toString()).apply()
    }

    // ============ 提醒时间（手机可下发） ============

    fun dailyHour(c: Context): Int = sp(c).getInt("daily_hour", 8)
    fun dailyMinute(c: Context): Int = sp(c).getInt("daily_minute", 0)
    fun setDailyTime(c: Context, h: Int, m: Int) {
        sp(c).edit().putInt("daily_hour", h.coerceIn(0, 23)).putInt("daily_minute", m.coerceIn(0, 59)).apply()
    }

    // ============ 界面缩放（拿不到真实屏幕密度时的兜底） ============

    /** 0=小 1=中 2=大 */
    fun uiScale(c: Context): Int = sp(c).getInt("ui_scale", 1)
    fun setUiScale(c: Context, v: Int) {
        sp(c).edit().putInt("ui_scale", v.coerceIn(0, 2)).apply()
    }
}

/** 手表侧的待办模型（字段与手机端 WatchServer 下发的 JSON 对齐） */
data class Todo(
    val id: String,
    val title: String,
    val deadline: String?,
    val subject: String?,
    val isCompleted: Boolean,
    val isKeyTask: Boolean,
    val remindAt: String?,
    val remindOffsetMinutes: Int,
    val updatedAt: String,
    val deleted: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title)
        deadline?.let { put("deadline", it) }
        subject?.let { put("subject", it) }
        put("isCompleted", isCompleted)
        put("isKeyTask", isKeyTask)
        remindAt?.let { put("remindAt", it) }
        put("remindOffsetMinutes", remindOffsetMinutes)
        put("updatedAt", updatedAt)
        put("deleted", deleted)
    }

    companion object {
        fun fromJson(o: JSONObject): Todo = Todo(
            id = o.optString("id", java.util.UUID.randomUUID().toString()),
            title = o.optString("title", "(无标题)"),
            deadline = o.optString("deadline", "").ifBlank { null },
            subject = o.optString("subject", "").ifBlank { null },
            isCompleted = o.optBoolean("isCompleted", false),
            isKeyTask = o.optBoolean("isKeyTask", false),
            remindAt = o.optString("remindAt", "").ifBlank { null },
            remindOffsetMinutes = o.optInt("remindOffsetMinutes", 0),
            updatedAt = o.optString("updatedAt", ""),
            deleted = o.optBoolean("deleted", false)
        )
    }
}
