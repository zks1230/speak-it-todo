package com.saym.phone.model

import kotlinx.serialization.Serializable

/** 手机端本地模型（从 backend JSON 反序列化；与 shared 同名但精简，避免把 JDBC/HikariCP 拉进 APK） */

@Serializable
enum class InputType { TEXT, IMAGE }

@Serializable
data class TodoDraft(
    val title: String,
    val deadline: String? = null,
    val subject: String? = null,
    val notes: String? = null,
    val isKeyTask: Boolean = false,
    val confidence: Float = 1f,
    /** 后端按阈值算好的「请确认」标记（F2.6）；老后端不返回时会用 confidence 兜底 */
    val needsConfirm: Boolean = false,
    val rawExtraction: String? = null
)

/** POST /api/extract 的响应 */
@Serializable
data class ExtractOutcome(
    val drafts: List<TodoDraft> = emptyList(),
    val modelUsed: String? = null,
    val modelKind: String? = null,
    val fallbackFrom: String? = null,
    /** 后端落库的输入暂存 id，入库后回填产出的待办 id（F1.4） */
    val inputId: String? = null,
    val needsConfirmCount: Int = 0
)

@Serializable
data class TodoItem(
    val id: String,
    val title: String,
    val deadline: String? = null,
    val subject: String? = null,
    val notes: String? = null,
    val sourceInputType: InputType = InputType.TEXT,
    val sourceInputRaw: String? = null,
    val isCompleted: Boolean = false,
    val completedAt: String? = null,
    val isKeyTask: Boolean = false,
    val remindAt: String? = null,
    val remindOffsetMinutes: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deviceId: String? = null,
    val deleted: Boolean = false,
    val deletedAt: String? = null
)

@Serializable
data class TodoCreateReq(
    val title: String,
    val deadline: String? = null,
    val subject: String? = null,
    val notes: String? = null,
    val isKeyTask: Boolean = false,
    val remindAt: String? = null,
    val remindOffsetMinutes: Int = 0,
    val sourceInputType: InputType = InputType.TEXT,
    val sourceInputRaw: String? = null
)

@Serializable
data class TodoUpdateReq(
    val title: String? = null,
    val deadline: String? = null,
    val subject: String? = null,
    val notes: String? = null,
    val isCompleted: Boolean? = null,
    val isKeyTask: Boolean? = null,
    val remindAt: String? = null,
    val remindOffsetMinutes: Int? = null,
    val clearDeadline: Boolean = false,
    val clearRemindAt: Boolean = false
)

/** F3.2 批量完成 */
@Serializable
data class TodoBatchCompleteReq(
    val ids: List<String>,
    val isCompleted: Boolean = true,
    val deviceId: String? = null
)

@Serializable
data class TodoBatchResult(
    val affected: Int = 0,
    val items: List<TodoItem> = emptyList()
)

@Serializable
data class SyncReq(
    val deviceId: String,
    val sinceUpdatedAt: String? = null,
    val includeCompleted: Boolean = true,
    val includeDeleted: Boolean = true,
    val limit: Int = 500
)

@Serializable
data class SyncResp(
    val items: List<TodoItem> = emptyList(),
    val serverTime: String? = null,
    val hasMore: Boolean = false,
    val pendingInputs: Int = 0
)

@Serializable
data class Stats(
    val weekCompleted: Int = 0,
    val weekOverdue: Int = 0,
    val completionRate: Float = 0f,
    val streakDays: Int = 0,
    val totalPending: Int = 0
)

// ============ F1.4 输入暂存 ============

@Serializable
data class InputRecordView(
    val id: String,
    val deviceId: String? = null,
    val type: InputType = InputType.TEXT,
    val text: String? = null,
    val imageRef: String? = null,
    val createdAt: String,
    val processed: Boolean = false,
    val processedAt: String? = null,
    val extractedTodoIds: List<String> = emptyList(),
    val expired: Boolean = false
)

@Serializable
data class InputCreateReq(
    val type: InputType,
    val text: String? = null,
    val imageRef: String? = null,
    val deviceId: String? = null
)

// ============ F4.1 设备绑定 ============

@Serializable
data class DeviceBindReq(val deviceId: String, val name: String, val kind: String)

@Serializable
data class DeviceInfoView(
    val deviceId: String,
    val name: String? = null,
    val kind: String = "phone",
    val boundAt: String = ""
)

// ============ F5.1 每日推送 ============

@Serializable
data class DailyPushStateView(
    val pending: Boolean = false,
    val date: String? = null,
    val pushAt: String? = null,
    val enabled: Boolean = true,
    val defaultRemindOffsetMinutes: Int = 0,
    val imageMaxEdge: Int = 1280,
    val keyTaskRemindEnabled: Boolean = true,
    val items: List<TodoItem> = emptyList()
)

// ============ F6 配置中心 ============

@Serializable
data class AppConfigView(
    val version: Long = 0,
    val models: Map<String, ModelCfgView> = emptyMap(),
    val routing: RoutingView = RoutingView(),
    val dailyPushHour: Int = 8,
    val dailyPushMinute: Int = 0,
    val dailyPushEnabled: Boolean = true,
    val defaultRemindOffsetMinutes: Int = 0,
    val keyTaskRemindEnabled: Boolean = true,
    val autoSuggestDeadline: Boolean = false,
    val maxDraftsPerInput: Int = 20,
    val defaultSubject: String? = null,
    val configHistoryEnabled: Boolean = true
)

@Serializable
data class ModelCfgView(
    val kind: String,
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val temperature: Float = 0.3f,
    val maxTokens: Int = 2048,
    val enabled: Boolean = true,
    val supportsVision: Boolean = false
)

@Serializable
data class RoutingView(
    val textModel: String = "DEEPSEEK",
    val imageModel: String = "DEEPSEEK",
    val enableImageRouting: Boolean = false,
    val imageConfidenceThreshold: Float = 0.7f,
    val imageMaxEdge: Int = 1280
)

@Serializable
data class ConfigHistoryView(
    val id: String,
    val version: Long,
    val changedAt: String,
    val changedBy: String,
    val summary: String,
    val snapshotJson: String = ""
)

@Serializable
data class DbInfoView(
    val jdbcUrl: String = "",
    val user: String = "",
    val note: String = "",
    val pool: Int = 0
)
