package com.saym.phone.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.saym.phone.App

/**
 * 本地主库（新架构：手机是**唯一真相来源**，不再只是"弱网缓存"）。
 *
 * 与旧版的区别：
 *  - 旧版表名 `todos_cache`，只做弱网兜底，网络失败才读它；
 *  - 现在本地库就是正式数据：所有增删改先写本地，再由局域网/BLE 同步给手表。
 *    这样"打开应用即看到最新"不再依赖任何网络。
 */
@Entity(tableName = "todos_local")
data class TodoLocal(
    @PrimaryKey val id: String,
    val title: String,
    val deadline: String?,
    val subject: String?,
    val notes: String?,
    val sourceInputType: String,
    val sourceInputRaw: String?,
    val isCompleted: Boolean,
    val completedAt: String?,
    val isKeyTask: Boolean,
    val remindAt: String?,
    val remindOffsetMinutes: Int,
    val createdAt: String,
    val updatedAt: String,
    /** "phone" = 手机创建；"watch" = 手表创建/改的（同步冲突时用于排查） */
    val originDevice: String,
    /** 软删除标记：同步时作为 tombstone 发给手表，否则手表会一直留着已删条目 */
    val deleted: Boolean,
    val deletedAt: String?
)

/**
 * F1.4 输入暂存：未处理输入保留 30 天、可重提。
 * 新架构下完全本地化（不再上传云端），图片只存本地引用，不存 base64。
 */
@Entity(tableName = "inputs_local")
data class InputLocal(
    @PrimaryKey val id: String,
    val type: String,
    val text: String?,
    val imageRef: String?,
    val createdAt: String,
    val processed: Boolean,
    val extractedTodoIds: String?
)

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos_local WHERE deleted=0 AND isCompleted=0 AND (deadline IS NULL OR deadline='' OR deadline=:today) ORDER BY deadline,title")
    fun today(today: String): List<TodoLocal>

    /**
     * 明日：只看**明天到期**的（和「今日」一样按日期相等匹配）。
     * 参数必须全部在 SQL 里用到，否则 Room（KSP）会报 "Unused parameter" 中断编译。
     */
    @Query("SELECT * FROM todos_local WHERE deleted=0 AND isCompleted=0 AND deadline=:tomorrow ORDER BY deadline,title")
    fun tomorrow(tomorrow: String): List<TodoLocal>

    @Query("SELECT * FROM todos_local WHERE deleted=0 AND isCompleted=0 ORDER BY CASE WHEN deadline IS NULL OR deadline='' THEN 1 ELSE 0 END, deadline, title")
    fun pending(): List<TodoLocal>

    @Query("SELECT * FROM todos_local WHERE deleted=0 AND isCompleted=0 AND deadline IS NOT NULL AND deadline!='' AND deadline<:today ORDER BY deadline,title")
    fun overdue(today: String): List<TodoLocal>

    /**
     * 「本周」= 截止时间在本周日之前（含今天）的未完成条目。
     * 注意：参数必须全部在 SQL 里用到，否则 Room（KSP）会报 "Unused parameter" 并中断编译。
     */
    @Query("SELECT * FROM todos_local WHERE deleted=0 AND isCompleted=0 AND deadline IS NOT NULL AND deadline!='' AND deadline<=:weekEnd ORDER BY deadline,title")
    fun weekUpcoming(weekEnd: String): List<TodoLocal>

    @Query("SELECT * FROM todos_local WHERE deleted=0 ORDER BY isCompleted, CASE WHEN deadline IS NULL OR deadline='' THEN 1 ELSE 0 END, deadline, title")
    fun all(): List<TodoLocal>

    /** 手表拉取用：**包含** tombstone，让手表能删掉本地副本 */
    @Query("SELECT * FROM todos_local ORDER BY updatedAt")
    fun allForSync(): List<TodoLocal>

    @Query("SELECT * FROM todos_local WHERE id=:id")
    fun byId(id: String): TodoLocal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: TodoLocal)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<TodoLocal>)

    @Query("UPDATE todos_local SET isCompleted=:done, completedAt=:at, updatedAt=:at WHERE id=:id")
    fun setCompleted(id: String, done: Boolean, at: String)

    /** 批量完成（F3.2） */
    @Query("UPDATE todos_local SET isCompleted=:done, completedAt=:at, updatedAt=:at WHERE id IN (:ids) AND deleted=0")
    fun setCompletedBatch(ids: List<String>, done: Boolean, at: String): Int

    @Query("UPDATE todos_local SET deleted=1, deletedAt=:at, updatedAt=:at WHERE id=:id")
    fun softDelete(id: String, at: String)

    @Query("SELECT COUNT(*) FROM todos_local WHERE deleted=0 AND isCompleted=0")
    fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM todos_local WHERE deleted=0")
    fun totalCount(): Int
}

@Dao
interface InputDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: InputLocal)

    @Query("SELECT * FROM inputs_local WHERE createdAt>=:cutoff ORDER BY createdAt DESC")
    fun recent(cutoff: String): List<InputLocal>

    @Query("SELECT * FROM inputs_local ORDER BY createdAt DESC LIMIT :limit")
    fun listRecent(limit: Int): List<InputLocal>

    @Query("SELECT * FROM inputs_local WHERE id=:id")
    fun byId(id: String): InputLocal?

    @Query("UPDATE inputs_local SET processed=1, extractedTodoIds=:todoIds WHERE id=:id")
    fun markProcessed(id: String, todoIds: String)

    @Query("SELECT COUNT(*) FROM inputs_local WHERE processed=0")
    fun pendingCount(): Int

    @Query("DELETE FROM inputs_local WHERE createdAt<:cutoff")
    fun purgeBefore(cutoff: String)
}

@Database(entities = [TodoLocal::class, InputLocal::class], version = 3, exportSchema = false)
abstract class SaymDb : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    abstract fun inputDao(): InputDao

    companion object {
        @Volatile private var INST: SaymDb? = null
        fun get(): SaymDb = INST ?: synchronized(this) {
            INST ?: run {
                // 依赖 App.instance，而它只有在 AndroidManifest 的 <application> 上写了
                // android:name=".App" 时才会被初始化。曾经漏写这一行，导致所有数据读写都抛
                // "lateinit property instance has not been initialized" —— 这里给出能看懂的提示。
                if (!App.isReady()) {
                    error("应用初始化失败：AndroidManifest.xml 的 <application> 缺少 android:name=\".App\"")
                }
                Room.databaseBuilder(App.instance, SaymDb::class.java, "saym.db")
                    .fallbackToDestructiveMigration()
                    .build().also { INST = it }
            }
        }
    }
}
