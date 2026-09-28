package com.saym.shared.db

import com.saym.shared.models.AppConfig
import com.saym.shared.models.ConfigHistory
import com.saym.shared.models.DeviceInfo
import com.saym.shared.models.InputCreateRequest
import com.saym.shared.models.InputRecord
import com.saym.shared.models.InputType
import com.saym.shared.models.SyncRequest
import com.saym.shared.models.SyncResponse
import com.saym.shared.models.TodoBatchResult
import com.saym.shared.models.TodoCreateRequest
import com.saym.shared.models.TodoItem
import com.saym.shared.models.TodoUpdateRequest
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Neon（Serverless PostgreSQL）数据访问层。
 * 纯 JDBC + HikariCP，不用重型 ORM（D8 自用场景简单 SQL 即可）。
 *
 * 重要约定：
 *  - 所有端（手机/手表/Web）共享同一份 todos，**不按 device_id 切分数据**；
 *    device_id 只记录「谁创建/最后改的」，用于排查与 LWW 冲突处理（F4.5）。
 *  - 删除走软删除（deleted + deleted_at），以便同步时下发 tombstone，
 *    否则 A 端删除后 B 端会一直保留副本（F4.5 必须）。
 *
 * 表结构：
 *  - todos(id TEXT PK, title, deadline, subject, notes, source_input_type, source_input_raw,
 *          is_completed BOOL, completed_at, is_key_task BOOL, remind_at, remind_offset_minutes INT,
 *          created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, device_id TEXT,
 *          deleted BOOL, deleted_at TEXT)
 *  - inputs(id TEXT PK, device_id, type, text, image_ref, created_at, processed, processed_at,
 *          extracted_todo_ids TEXT, expired BOOL)     ← F1.4 输入暂存
 *  - app_config(id INT PK default 1, config_json TEXT, version BIGINT, updated_at TIMESTAMPTZ)
 *  - config_history(id TEXT PK, version BIGINT, changed_at TIMESTAMPTZ, changed_by TEXT, summary TEXT, snapshot_json TEXT)
 *  - devices(device_id TEXT PK, name TEXT, kind TEXT, bound_at TIMESTAMPTZ)
 *  - push_state(id TEXT PK, src_device_id, target_device_id, pushed_at TIMESTAMPTZ, acked BOOL)  ← F5 每日推送标记
 *
 * 敏感字段（API key）在 config_json 内是 AES-256 密文，由 backend/调用方在写入前加密、读出后解密。
 */
class NeonDataSource(private val ds: HikariDataSource) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** 可注入时钟，便于自检脚本模拟跨天/跨周（默认系统时间） */
    var clock: () -> Instant = { Instant.now() }

    /** 建表（首次启动时调用，幂等；含旧库列补齐） */
    fun ensureSchema() {
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.execute("""
                    CREATE TABLE IF NOT EXISTS todos (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        deadline TEXT,
                        subject TEXT,
                        notes TEXT,
                        source_input_type TEXT NOT NULL,
                        source_input_raw TEXT,
                        is_completed BOOLEAN NOT NULL DEFAULT FALSE,
                        completed_at TEXT,
                        is_key_task BOOLEAN NOT NULL DEFAULT FALSE,
                        remind_at TEXT,
                        remind_offset_minutes INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        device_id TEXT NOT NULL,
                        deleted BOOLEAN NOT NULL DEFAULT FALSE,
                        deleted_at TEXT
                    )
                """.trimIndent())
                // 旧库升级：补齐 v0.5 之后新增的列（幂等）
                st.execute("ALTER TABLE todos ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE")
                st.execute("ALTER TABLE todos ADD COLUMN IF NOT EXISTS deleted_at TEXT")
                st.execute("CREATE INDEX IF NOT EXISTS idx_todos_updated_at ON todos(updated_at)")
                st.execute("CREATE INDEX IF NOT EXISTS idx_todos_device ON todos(device_id)")
                st.execute("CREATE INDEX IF NOT EXISTS idx_todos_deadline ON todos(deadline)")
                st.execute("CREATE INDEX IF NOT EXISTS idx_todos_deleted ON todos(deleted)")

                st.execute("""
                    CREATE TABLE IF NOT EXISTS inputs (
                        id TEXT PRIMARY KEY,
                        device_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        text TEXT,
                        image_ref TEXT,
                        created_at TEXT NOT NULL,
                        processed BOOLEAN NOT NULL DEFAULT FALSE,
                        processed_at TEXT,
                        extracted_todo_ids TEXT NOT NULL DEFAULT '',
                        expired BOOLEAN NOT NULL DEFAULT FALSE
                    )
                """.trimIndent())
                st.execute("CREATE INDEX IF NOT EXISTS idx_inputs_created ON inputs(created_at)")
                st.execute("CREATE INDEX IF NOT EXISTS idx_inputs_device ON inputs(device_id)")

                st.execute("""
                    CREATE TABLE IF NOT EXISTS app_config (
                        id INTEGER PRIMARY KEY DEFAULT 1,
                        config_json TEXT NOT NULL,
                        version BIGINT NOT NULL DEFAULT 0,
                        updated_at TEXT NOT NULL,
                        CONSTRAINT single_row CHECK (id = 1)
                    )
                """.trimIndent())
                st.execute("""
                    CREATE TABLE IF NOT EXISTS config_history (
                        id TEXT PRIMARY KEY,
                        version BIGINT NOT NULL,
                        changed_at TEXT NOT NULL,
                        changed_by TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        snapshot_json TEXT NOT NULL
                    )
                """.trimIndent())
                st.execute("""
                    CREATE TABLE IF NOT EXISTS devices (
                        device_id TEXT PRIMARY KEY,
                        name TEXT,
                        kind TEXT NOT NULL,
                        bound_at TEXT NOT NULL
                    )
                """.trimIndent())
                st.execute("""
                    CREATE TABLE IF NOT EXISTS push_state (
                        id TEXT PRIMARY KEY,
                        src_device_id TEXT,
                        target_device_id TEXT NOT NULL,
                        pushed_at TEXT NOT NULL,
                        acked BOOLEAN NOT NULL DEFAULT FALSE
                    )
                """.trimIndent())
                st.execute("CREATE INDEX IF NOT EXISTS idx_push_target ON push_state(target_device_id, acked)")
            }
        }
    }

    // ============ Todo CRUD ============

    fun createTodo(req: TodoCreateRequest, deviceId: String): TodoItem {
        val now = nowIso()
        val item = TodoItem(
            id = UUID.randomUUID().toString(),
            title = req.title,
            deadline = req.deadline,
            subject = req.subject,
            notes = req.notes,
            sourceInputType = req.sourceInputType,
            sourceInputRaw = req.sourceInputRaw?.take(2000),
            isCompleted = false,
            completedAt = null,
            isKeyTask = req.isKeyTask,
            remindAt = req.remindAt,
            remindOffsetMinutes = req.remindOffsetMinutes,
            createdAt = now,
            updatedAt = now,
            deviceId = deviceId,
            deleted = false,
            deletedAt = null
        )
        ds.connection.use { c ->
            c.prepareStatement("""
                INSERT INTO todos(id,title,deadline,subject,notes,source_input_type,source_input_raw,
                    is_completed,completed_at,is_key_task,remind_at,remind_offset_minutes,
                    created_at,updated_at,device_id,deleted,deleted_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()).use { ps ->
                ps.setString(1, item.id); ps.setString(2, item.title)
                ps.setString(3, item.deadline); ps.setString(4, item.subject)
                ps.setString(5, item.notes); ps.setString(6, item.sourceInputType.name)
                ps.setString(7, item.sourceInputRaw); ps.setBoolean(8, item.isCompleted)
                ps.setString(9, item.completedAt); ps.setBoolean(10, item.isKeyTask)
                ps.setString(11, item.remindAt); ps.setInt(12, item.remindOffsetMinutes)
                ps.setString(13, item.createdAt); ps.setString(14, item.updatedAt)
                ps.setString(15, item.deviceId); ps.setBoolean(16, item.deleted)
                ps.setString(17, item.deletedAt)
                ps.executeUpdate()
            }
        }
        return item
    }

    fun getTodo(id: String): TodoItem? = ds.connection.use { c ->
        c.prepareStatement("SELECT * FROM todos WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toTodoItem() else null }
        }
    }

    /**
     * 列表查询。
     * @param deviceId 为 null 表示「不过滤设备」（Web 端 / 自用单人多端共享）；非 null 时只返回该设备创建的条目。
     */
    fun listTodos(filter: String, deviceId: String?): List<TodoItem> {
        val sql = when (filter) {
            "today" -> "SELECT * FROM todos WHERE deleted=FALSE AND is_completed=FALSE AND (deadline IS NULL OR deadline='' OR deadline=?)"
            "week" -> "SELECT * FROM todos WHERE deleted=FALSE AND is_completed=FALSE AND deadline IS NOT NULL AND deadline!='' AND deadline<=?"
            "overdue" -> "SELECT * FROM todos WHERE deleted=FALSE AND is_completed=FALSE AND deadline IS NOT NULL AND deadline!='' AND deadline<?"
            else -> "SELECT * FROM todos WHERE deleted=FALSE"
        } + (if (deviceId != null) " AND device_id=?" else "") + " ORDER BY is_completed, COALESCE(NULLIF(deadline,''),'9999'), title"
        return ds.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                var i = 1
                when (filter) {
                    "today" -> ps.setString(i++, todayDate())
                    "week" -> ps.setString(i++, weekEndDate())
                    "overdue" -> ps.setString(i++, todayDate())
                    else -> {}
                }
                if (deviceId != null) ps.setString(i, deviceId)
                val list = ArrayList<TodoItem>()
                ps.executeQuery().use { rs -> while (rs.next()) list.add(rs.toTodoItem()) }
                list
            }
        }
    }

    fun updateTodo(id: String, req: TodoUpdateRequest): TodoItem? {
        val existing = getTodo(id) ?: return null
        if (existing.deleted) return null
        val becameCompleted = req.isCompleted == true && !existing.isCompleted
        val updated = existing.copy(
            // title/subject/notes 传空串视为「不改」；要清空传 clearXxx 或直接给空串以外的值
            title = req.title?.takeIf { it.isNotBlank() } ?: existing.title,
            deadline = if (req.clearDeadline) null else (req.deadline ?: existing.deadline),
            subject = if (req.subject != null) req.subject.ifBlank { null } else existing.subject,
            notes = if (req.notes != null) req.notes.ifBlank { null } else existing.notes,
            isCompleted = req.isCompleted ?: existing.isCompleted,
            isKeyTask = req.isKeyTask ?: existing.isKeyTask,
            remindAt = if (req.clearRemindAt) null else (req.remindAt ?: existing.remindAt),
            remindOffsetMinutes = req.remindOffsetMinutes ?: existing.remindOffsetMinutes,
            completedAt = when {
                becameCompleted -> nowIso()
                req.isCompleted == false -> null   // 取消完成时清掉完成时间，避免连续天数统计错乱
                else -> existing.completedAt
            },
            updatedAt = nowIso()
        )
        ds.connection.use { c ->
            c.prepareStatement("""
                UPDATE todos SET title=?,deadline=?,subject=?,notes=?,is_completed=?,completed_at=?,
                    is_key_task=?,remind_at=?,remind_offset_minutes=?,updated_at=?
                WHERE id=?
            """.trimIndent()).use { ps ->
                ps.setString(1, updated.title); ps.setString(2, updated.deadline)
                ps.setString(3, updated.subject); ps.setString(4, updated.notes)
                ps.setBoolean(5, updated.isCompleted); ps.setString(6, updated.completedAt)
                ps.setBoolean(7, updated.isKeyTask); ps.setString(8, updated.remindAt)
                ps.setInt(9, updated.remindOffsetMinutes); ps.setString(10, updated.updatedAt)
                ps.setString(11, id)
                ps.executeUpdate()
            }
        }
        return updated
    }

    /** 批量完成/取消完成（F3.2）。单条 SQL 批量更新，避免 N 次往返。 */
    fun batchSetCompleted(ids: List<String>, completed: Boolean, deviceId: String?): TodoBatchResult {
        if (ids.isEmpty()) return TodoBatchResult(0, emptyList())
        val now = nowIso()
        val affected: Int
        ds.connection.use { c ->
            val placeholders = ids.joinToString(",") { "?" }
            val sql = StringBuilder("UPDATE todos SET is_completed=?, completed_at=?, updated_at=? WHERE deleted=FALSE AND id IN ($placeholders)")
            if (deviceId != null) sql.append(" AND device_id=?")
            c.prepareStatement(sql.toString()).use { ps ->
                var i = 1
                ps.setBoolean(i++, completed); ps.setString(i++, if (completed) now else null)
                ps.setString(i++, now)
                ids.forEach { ps.setString(i++, it) }
                if (deviceId != null) ps.setString(i, deviceId)
                affected = ps.executeUpdate()
            }
        }
        val items = ids.mapNotNull { getTodo(it) }
        return TodoBatchResult(affected, items)
    }

    /** 软删除（写 tombstone，同步时下发给其他端） */
    fun deleteTodo(id: String): Boolean = ds.connection.use { c ->
        c.prepareStatement("UPDATE todos SET deleted=TRUE, deleted_at=?, updated_at=? WHERE id=? AND deleted=FALSE").use { ps ->
            val now = nowIso()
            ps.setString(1, now); ps.setString(2, now); ps.setString(3, id)
            ps.executeUpdate() > 0
        }
    }

    // ============ 增量同步 ============
    /**
     * 同步：**不按 device_id 过滤**（多端共享同一份数据，D8/D10），
     * 只按 updated_at 做增量窗口，并把软删除条目作为 tombstone 一起下发。
     */
    fun sync(req: SyncRequest): SyncResponse {
        val sql = if (req.sinceUpdatedAt == null) {
            "SELECT * FROM todos ORDER BY updated_at LIMIT ?"
        } else {
            "SELECT * FROM todos WHERE updated_at>? ORDER BY updated_at LIMIT ?"
        }
        val limit = req.limit.coerceIn(1, 2000)
        val items = ds.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                var i = 1
                if (req.sinceUpdatedAt != null) ps.setString(i++, req.sinceUpdatedAt)
                ps.setInt(i, limit + 1)
                val list = ArrayList<TodoItem>()
                ps.executeQuery().use { rs -> while (rs.next()) list.add(rs.toTodoItem()) }
                list
            }
        }
        val hasMore = items.size > limit
        val page = items.take(limit).filter {
            (req.includeCompleted || !it.isCompleted) && (req.includeDeleted || !it.deleted)
        }
        return SyncResponse(
            items = page,
            serverTime = nowIso(),
            hasMore = hasMore,
            pendingInputs = pendingInputCount(req.deviceId)
        )
    }

    // ============ 输入暂存（F1.4） ============

    fun createInput(req: InputCreateRequest, deviceId: String): InputRecord {
        val rec = InputRecord(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId,
            type = req.type,
            text = req.text?.take(8000),
            imageRef = req.imageRef?.take(4000),
            createdAt = nowIso(),
            processed = false
        )
        ds.connection.use { c ->
            c.prepareStatement("""
                INSERT INTO inputs(id,device_id,type,text,image_ref,created_at,processed,processed_at,extracted_todo_ids,expired)
                VALUES(?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()).use { ps ->
                ps.setString(1, rec.id); ps.setString(2, rec.deviceId)
                ps.setString(3, rec.type.name); ps.setString(4, rec.text)
                ps.setString(5, rec.imageRef); ps.setString(6, rec.createdAt)
                ps.setBoolean(7, false); ps.setString(8, null)
                ps.setString(9, ""); ps.setBoolean(10, false)
                ps.executeUpdate()
            }
        }
        return rec
    }

    /** 标记输入已提炼，并记录产出的待办 id */
    fun markInputProcessed(id: String, todoIds: List<String>, processed: Boolean = true) {
        ds.connection.use { c ->
            c.prepareStatement("UPDATE inputs SET processed=?, processed_at=?, extracted_todo_ids=? WHERE id=?").use { ps ->
                ps.setBoolean(1, processed)
                ps.setString(2, if (processed) nowIso() else null)
                ps.setString(3, todoIds.joinToString(","))
                ps.setString(4, id)
                ps.executeUpdate()
            }
        }
    }

    /** 输入历史（默认只看未过期的 30 天窗口） */
    fun listInputs(deviceId: String?, includeExpired: Boolean = false, limit: Int = 100): List<InputRecord> {
        val sql = StringBuilder("SELECT * FROM inputs WHERE 1=1")
        if (deviceId != null) sql.append(" AND device_id=?")
        if (!includeExpired) sql.append(" AND expired=FALSE")
        sql.append(" ORDER BY created_at DESC LIMIT ?")
        return ds.connection.use { c ->
            c.prepareStatement(sql.toString()).use { ps ->
                var i = 1
                if (deviceId != null) ps.setString(i++, deviceId)
                ps.setInt(i, limit.coerceIn(1, 500))
                val list = ArrayList<InputRecord>()
                ps.executeQuery().use { rs -> while (rs.next()) list.add(rs.toInputRecord()) }
                list
            }
        }
    }

    fun getInput(id: String): InputRecord? = ds.connection.use { c ->
        c.prepareStatement("SELECT * FROM inputs WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toInputRecord() else null }
        }
    }

    fun pendingInputCount(deviceId: String?): Int {
        val sql = if (deviceId == null) "SELECT COUNT(*) FROM inputs WHERE processed=FALSE AND expired=FALSE"
        else "SELECT COUNT(*) FROM inputs WHERE processed=FALSE AND expired=FALSE AND device_id=?"
        return ds.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                if (deviceId != null) ps.setString(1, deviceId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    /** 30 天保留策略：把超期输入标记 expired（保留记录但不参与重提，便于排查） */
    fun expireOldInputs(days: Int = 30): Int {
        val cutoff = DateTimeFormatter.ISO_INSTANT.format(clock().minusSeconds(days * 86400L))
        return ds.connection.use { c ->
            c.prepareStatement("UPDATE inputs SET expired=TRUE WHERE expired=FALSE AND created_at<?").use { ps ->
                ps.setString(1, cutoff); ps.executeUpdate()
            }
        }
    }

    // ============ 配置中心 ============

    fun loadConfig(): AppConfig? = ds.connection.use { c ->
        c.prepareStatement("SELECT config_json, version FROM app_config WHERE id=1").use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val cfg = json.decodeFromString(AppConfig.serializer(), rs.getString(1))
                    cfg.copy(version = rs.getLong(2))
                } else null
            }
        }
    }

    /** 保存配置（config_json 中敏感字段需调用方先加密）。返回新版本号。 */
    fun saveConfig(cfg: AppConfig, changedBy: String, summary: String): AppConfig {
        val newVersion = (loadConfig()?.version ?: 0L) + 1
        val toSave = cfg.copy(version = newVersion)
        val now = nowIso()
        ds.connection.use { c ->
            c.prepareStatement("""
                INSERT INTO app_config(id, config_json, version, updated_at)
                VALUES(1, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET config_json=EXCLUDED.config_json,
                    version=EXCLUDED.version, updated_at=EXCLUDED.updated_at
            """.trimIndent()).use { ps ->
                ps.setString(1, json.encodeToString(AppConfig.serializer(), toSave))
                ps.setLong(2, newVersion); ps.setString(3, now)
                ps.executeUpdate()
            }
        }
        return toSave
    }

    /**
     * 写入配置历史（F6.6）。
     * 注意：此前 SQL 占位符数量与列数不一致（6 个 ? / 7 列），会直接抛 SQLException 连累保存流程，已修正。
     */
    fun saveConfigHistory(cfg: AppConfig, changedBy: String, summary: String) {
        ds.connection.use { c ->
            c.prepareStatement("""
                INSERT INTO config_history(id, version, changed_at, changed_by, summary, snapshot_json)
                VALUES(?,?,?,?,?,?)
            """.trimIndent()).use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setLong(2, cfg.version); ps.setString(3, nowIso())
                ps.setString(4, changedBy); ps.setString(5, summary)
                ps.setString(6, json.encodeToString(AppConfig.serializer(), cfg))
                ps.executeUpdate()
            }
        }
    }

    fun listConfigHistory(limit: Int = 50): List<ConfigHistory> = ds.connection.use { c ->
        c.prepareStatement("SELECT * FROM config_history ORDER BY version DESC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            val list = ArrayList<ConfigHistory>()
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    list.add(ConfigHistory(
                        id = rs.getString("id"),
                        version = rs.getLong("version"),
                        changedAt = rs.getString("changed_at"),
                        changedBy = rs.getString("changed_by"),
                        summary = rs.getString("summary"),
                        snapshotJson = rs.getString("snapshot_json")
                    ))
                }
            }
            list
        }
    }

    fun getConfigHistoryVersion(version: Long): ConfigHistory? = ds.connection.use { c ->
        c.prepareStatement("SELECT * FROM config_history WHERE version=? ORDER BY changed_at DESC LIMIT 1").use { ps ->
            ps.setLong(1, version)
            ps.executeQuery().use { rs ->
                if (rs.next()) ConfigHistory(
                    id = rs.getString("id"),
                    version = rs.getLong("version"),
                    changedAt = rs.getString("changed_at"),
                    changedBy = rs.getString("changed_by"),
                    summary = rs.getString("summary"),
                    snapshotJson = rs.getString("snapshot_json")
                ) else null
            }
        }
    }

    // ============ 完成率看板 ============

    data class CompletionStats(
        val weekCompleted: Int, val weekOverdue: Int, val completionRate: Float,
        val streakDays: Int, val totalPending: Int
    )

    fun completionStats(deviceId: String? = null): CompletionStats {
        val today = todayDate()
        val weekStart = weekStartDate()
        val weekEnd = weekEndDate()
        return ds.connection.use { c ->
            val weekCompleted = c.prepareStatement(
                "SELECT COUNT(*) FROM todos WHERE deleted=FALSE AND is_completed=TRUE AND completed_at>=? AND completed_at<=?"
            ).use { ps ->
                ps.setString(1, weekStart); ps.setString(2, weekEnd + "T23:59:59Z")
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            val weekOverdue = c.prepareStatement(
                "SELECT COUNT(*) FROM todos WHERE deleted=FALSE AND is_completed=FALSE AND deadline IS NOT NULL AND deadline!='' AND deadline<?"
            ).use { ps ->
                ps.setString(1, today)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            val totalCompleted = c.prepareStatement(
                "SELECT COUNT(*) FROM todos WHERE deleted=FALSE AND is_completed=TRUE"
            ).use { ps -> ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
            val totalPending = c.prepareStatement(
                "SELECT COUNT(*) FROM todos WHERE deleted=FALSE AND is_completed=FALSE"
            ).use { ps -> ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
            val total = totalCompleted + totalPending
            val rate = if (total == 0) 0f else totalCompleted.toFloat() / total
            CompletionStats(
                weekCompleted = weekCompleted,
                weekOverdue = weekOverdue,
                completionRate = rate,
                streakDays = computeStreak(c),
                totalPending = totalPending
            )
        }
    }

    private fun computeStreak(c: Connection): Int {
        // 连续完成天数：从今天往前推，每天都至少完成 1 条，直到某天没完成则停
        var streak = 0
        var cur = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        for (i in 0 until 365) {
            val day = cur.toString()
            val next = cur.plusDays(1).toString()
            val n = c.prepareStatement(
                "SELECT COUNT(*) FROM todos WHERE deleted=FALSE AND is_completed=TRUE AND completed_at>=? AND completed_at<?"
            ).use { ps ->
                ps.setString(1, "${day}T00:00:00Z")
                ps.setString(2, "${next}T00:00:00Z")
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            if (n > 0) { streak++; cur = cur.minusDays(1) } else break
        }
        return streak
    }

    // ============ 设备绑定（F4.1 / F4.2） ============

    fun bindDevice(deviceId: String, name: String, kind: String) {
        ds.connection.use { c ->
            c.prepareStatement("""
                INSERT INTO devices(device_id, name, kind, bound_at)
                VALUES(?,?,?,?)
                ON CONFLICT(device_id) DO UPDATE SET name=EXCLUDED.name, kind=EXCLUDED.kind
            """.trimIndent()).use { ps ->
                ps.setString(1, deviceId); ps.setString(2, name)
                ps.setString(3, kind); ps.setString(4, nowIso())
                ps.executeUpdate()
            }
        }
    }

    fun listDevices(): List<DeviceInfo> = ds.connection.use { c ->
        c.prepareStatement("SELECT * FROM devices ORDER BY bound_at").use { ps ->
            val list = ArrayList<DeviceInfo>()
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(DeviceInfo(
                    deviceId = rs.getString("device_id"),
                    name = rs.getString("name"),
                    kind = rs.getString("kind"),
                    boundAt = rs.getString("bound_at")
                ))
            }
            list
        }
    }

    // ============ 每日推送标记（F5.1 / F5.5） ============
    /** 后端不能直推手表，只把「待推送」标记落库，由手机/手表 pull 时取走并本地设定闹钟 */
    fun markDailyPending(targetDeviceId: String, srcDeviceId: String?) {
        ds.connection.use { c ->
            c.prepareStatement("INSERT INTO push_state(id,src_device_id,target_device_id,pushed_at,acked) VALUES(?,?,?,?,FALSE)")
                .use { ps ->
                    ps.setString(1, UUID.randomUUID().toString())
                    ps.setString(2, srcDeviceId)
                    ps.setString(3, targetDeviceId)
                    ps.setString(4, nowIso())
                    ps.executeUpdate()
                }
        }
    }

    fun hasDailyPending(targetDeviceId: String): Boolean = ds.connection.use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM push_state WHERE target_device_id=? AND acked=FALSE").use { ps ->
            ps.setString(1, targetDeviceId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) > 0 else false }
        }
    }

    fun ackDailyPending(targetDeviceId: String) {
        ds.connection.use { c ->
            c.prepareStatement("UPDATE push_state SET acked=TRUE WHERE target_device_id=? AND acked=FALSE").use { ps ->
                ps.setString(1, targetDeviceId)
                ps.executeUpdate()
            }
        }
    }

    override fun close() = ds.close()

    // ============ 工具 ============
    private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(clock())
    private fun todayDate(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
    private fun weekStartDate(): String =
        LocalDate.now(ZoneId.of("Asia/Shanghai")).with(java.time.DayOfWeek.MONDAY).toString()
    private fun weekEndDate(): String {
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val sunday = today.with(java.time.DayOfWeek.SUNDAY)
        return if (sunday.isBefore(today)) sunday.plusWeeks(1).toString() else sunday.toString()
    }

    private fun ResultSet.toTodoItem(): TodoItem = TodoItem(
        id = getString("id"),
        title = getString("title"),
        deadline = getString("deadline"),
        subject = getString("subject"),
        notes = getString("notes"),
        sourceInputType = runCatching { InputType.valueOf(getString("source_input_type")) }.getOrDefault(InputType.TEXT),
        sourceInputRaw = getString("source_input_raw"),
        isCompleted = getBoolean("is_completed"),
        completedAt = getString("completed_at"),
        isKeyTask = getBoolean("is_key_task"),
        remindAt = getString("remind_at"),
        remindOffsetMinutes = getInt("remind_offset_minutes"),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
        deviceId = getString("device_id"),
        deleted = runCatching { getBoolean("deleted") }.getOrDefault(false),
        deletedAt = runCatching { getString("deleted_at") }.getOrNull()
    )

    private fun ResultSet.toInputRecord(): InputRecord = InputRecord(
        id = getString("id"),
        deviceId = getString("device_id"),
        type = runCatching { InputType.valueOf(getString("type")) }.getOrDefault(InputType.TEXT),
        text = getString("text"),
        imageRef = getString("image_ref"),
        createdAt = getString("created_at"),
        processed = getBoolean("processed"),
        processedAt = getString("processed_at"),
        extractedTodoIds = (getString("extracted_todo_ids") ?: "").split(",").filter { it.isNotBlank() },
        expired = getBoolean("expired")
    )
}

/** 工厂：从 JDBC 连接串构造 NeonDataSource */
fun neonDataSource(jdbcUrl: String, user: String, password: String, maxPool: Int = 5): NeonDataSource {
    val cfg = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = user
        this.password = password
        maximumPoolSize = maxPool
        minimumIdle = 0
        connectionTimeout = 10_000
        idleTimeout = 30_000
        maxLifetime = 600_000
        leakDetectionThreshold = 60_000
        poolName = "saym-neon"
        // 不在构造时立即尝试连接（Neon Serverless 冷启动慢，避免 backend 因 DB 未就绪而 crash）
        initializationFailTimeout = -1
        validate()
    }
    return NeonDataSource(HikariDataSource(cfg))
}
