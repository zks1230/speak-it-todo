package com.saym.shared.models

import kotlinx.serialization.Serializable

// ============ 数据模型 ============

/** 输入类型（用于 AI 路由分流） */
@Serializable
enum class InputType { TEXT, IMAGE }

/** 提炼前的输入封装 */
@Serializable
data class ExtractInput(
    val type: InputType,
    val text: String? = null,        // 文本场景
    val imageBase64: String? = null, // 图片场景（单张，base64 字符串无 data: 前缀）
    val mimeType: String? = null     // image/jpeg 等
)

/** AI 提炼出的单条草稿（待用户预览确认） */
@Serializable
data class TodoDraft(
    val title: String,
    val deadline: String? = null,       // ISO-8601 日期或日期时间（null 表示无截止）
    val subject: String? = null,
    val notes: String? = null,
    val isKeyTask: Boolean = false,
    val confidence: Float = 1f,           // 0~1，AI 把握度（< 0.7 时前端高亮，F2.6）
    val needsConfirm: Boolean = false,    // 后端按阈值算好的「请确认」标记（F2.6）
    val rawExtraction: String? = null
)

/** 提炼结果 */
@Serializable
data class ExtractResult(
    val drafts: List<TodoDraft>,
    val modelUsed: String,
    val raw: String? = null
)

/** 完整待办项 */
@Serializable
data class TodoItem(
    val id: String,
    val title: String,
    val deadline: String? = null,
    val subject: String? = null,
    val notes: String? = null,
    val sourceInputType: InputType,
    val sourceInputRaw: String? = null,  // 原始输入（截断保留）
    val isCompleted: Boolean = false,
    val completedAt: String? = null,
    val isKeyTask: Boolean = false,
    val remindAt: String? = null,        // 关键任务定点提醒（ISO-8601 时间）
    val remindOffsetMinutes: Int = 0,     // 提前量（分钟），默认 0（F5.3）
    val createdAt: String,
    val updatedAt: String,
    val deviceId: String,
    /** 软删除标记：同步时作为 tombstone 下发，让各端删掉本地副本（F4.5 冲突处理的必要组成） */
    val deleted: Boolean = false,
    val deletedAt: String? = null
)

/** 待办创建请求体（前端提交） */
@Serializable
data class TodoCreateRequest(
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

/** 待办更新请求（字段为 null 表示「不改」；要清空用 clearXxx=true） */
@Serializable
data class TodoUpdateRequest(
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

/** 批量完成请求（F3.2） */
@Serializable
data class TodoBatchCompleteRequest(
    val ids: List<String>,
    val isCompleted: Boolean = true,
    /** 可选过滤：只处理某设备归属的条目（Web 端批量整理用） */
    val deviceId: String? = null
)

/** 批量操作结果 */
@Serializable
data class TodoBatchResult(
    val affected: Int,
    val items: List<TodoItem> = emptyList()
)

/** 同步增量请求（手表/手机/Web 共用同一份 Neon 数据，不再按设备切分） */
@Serializable
data class SyncRequest(
    val deviceId: String,
    val sinceUpdatedAt: String? = null,    // ISO-8601 时间戳，null 表示全量
    val includeCompleted: Boolean = true,
    val includeDeleted: Boolean = true,    // 是否下发 tombstone
    val limit: Int = 500                   // 增量窗口上限，防止一次拉爆内存
)

/** 同步响应 */
@Serializable
data class SyncResponse(
    val items: List<TodoItem>,
    val serverTime: String,
    val hasMore: Boolean = false,
    /** 该设备待补提交的暂存输入条数（F1.4 弱网补提，纯本地暂存则为 0） */
    val pendingInputs: Int = 0
)

// ============ 输入暂存（F1.4） ============

/**
 * 未处理输入记录：保留 30 天、可重提。
 * 手机弱网时先落本地 Room，联网后补提交到 /api/inputs 并补 AI 提炼。
 */
@Serializable
data class InputRecord(
    val id: String,
    val deviceId: String,
    val type: InputType,
    val text: String? = null,
    /** 图片不入库（体积大）：本地缓存仅存缩略图 base64，云端只存文本类输入 */
    val imageRef: String? = null,
    val createdAt: String,
    /** 是否已成功提炼过 */
    val processed: Boolean = false,
    val processedAt: String? = null,
    /** 该输入提炼出的待办 id 列表（用于「来源回看」F3 详情） */
    val extractedTodoIds: List<String> = emptyList(),
    /** 已过期（超过 30 天），仅等待清理 */
    val expired: Boolean = false
)

@Serializable
data class InputCreateRequest(
    val type: InputType,
    val text: String? = null,
    val imageRef: String? = null,
    val deviceId: String? = null
)

// ============ 配置模型 ============

/** 模型类型（用于路由） */
@Serializable
enum class ModelKind { DEEPSEEK, DOUBAO_VISION, QWEN_VL, CUSTOM }

/** 单个模型配置（敏感字段如 apiKey 在 DB 中加密，此处为解密后的运行时视图） */
@Serializable
data class ModelConfig(
    val kind: ModelKind,
    val apiKey: String,        // 运行时已解密
    val baseUrl: String,
    val modelName: String,
    val temperature: Float = 0.3f,
    val maxTokens: Int = 2048,
    val enabled: Boolean = true,
    /** 该模型是否支持图片（视觉）输入。DeepSeek 文本模型为 false，防止图片被发到纯文本接口 */
    val supportsVision: Boolean = false
)

/** 分流规则（F6.3） */
@Serializable
data class RoutingRule(
    val textModel: ModelKind = ModelKind.DEEPSEEK,
    val imageModel: ModelKind = ModelKind.DEEPSEEK, // 默认也走 DeepSeek（D5：先实测，不达标再分流）
    val enableImageRouting: Boolean = false,         // 默认关闭，实测不达标再开启
    val imageConfidenceThreshold: Float = 0.7f,     // 低于此置信度标「请确认」（F2.6）
    /** 图片统一压缩后的最长边（像素），控制 base64 体积与耗时（性能：单条 ≤5 秒） */
    val imageMaxEdge: Int = 1280
)

/** 运行时配置（解密后用于 AI 路由 & 业务） */
@Serializable
data class AppConfig(
    val models: Map<ModelKind, ModelConfig> = emptyMap(),
    val routing: RoutingRule = RoutingRule(),
    // ---- 提醒（F5）----
    val dailyPushHour: Int = 8,            // 每日推送时间（小时，0-23）
    val dailyPushMinute: Int = 0,
    val dailyPushEnabled: Boolean = true,
    val defaultRemindOffsetMinutes: Int = 0,
    val keyTaskRemindEnabled: Boolean = true,
    // ---- AI 行为 ----
    val autoSuggestDeadline: Boolean = false,  // F2.4 默认关闭（§8 不在 MVP，留开关）
    val maxDraftsPerInput: Int = 20,           // 单次提炼条数上限
    // ---- 其他 ----
    val defaultSubject: String? = null,
    val configHistoryEnabled: Boolean = true,  // F6.7 改动日志可关
    val version: Long = 0L                  // 配置版本号（每次更新自增）
)

/** 配置历史（F6.6，每次改动留版本记录可回滚） */
@Serializable
data class ConfigHistory(
    val id: String,
    val version: Long,
    val changedAt: String,
    val changedBy: String,                 // 设备 ID（自用，便于排查）
    val summary: String,                   // 改动摘要
    val snapshotJson: String               // 配置快照
)

// ============ 设备绑定（F4.1 / F4.2） ============

@Serializable
data class DeviceBindRequest(
    val deviceId: String,
    val name: String,
    /** phone / watch / web */
    val kind: String
)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String? = null,
    val kind: String,
    val boundAt: String
)

// ============ 每日推送（F5.1，后端只标记，手机/手表 pull 时取走） ============

@Serializable
data class DailyPushState(
    /** yyyy-MM-dd，本次待推送清单对应的日期 */
    val date: String,
    /** 是否还有未取走的推送标记 */
    val pending: Boolean,
    /** 该清单应在什么时刻提醒（由 app_config 的 dailyPushHour/Minute 算出） */
    val pushAt: String,
    val items: List<TodoItem> = emptyList()
)
