package com.saym.backend

import com.saym.shared.crypto.CryptoUtil
import com.saym.shared.models.AppConfig
import com.saym.shared.models.DeviceBindRequest
import com.saym.shared.models.ExtractInput
import com.saym.shared.models.InputCreateRequest
import com.saym.shared.models.InputType
import com.saym.shared.models.ModelKind
import com.saym.shared.models.SyncRequest
import com.saym.shared.models.TodoBatchCompleteRequest
import com.saym.shared.models.TodoCreateRequest
import com.saym.shared.models.TodoItem
import com.saym.shared.models.TodoUpdateRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * HTTP 接口层。
 *
 * 与需求文档的对应关系：
 *  - /api/extract            F2 AI 提炼（文本/图片，含模型分流回退）
 *  - /api/todos{,/today,/week,/overdue,/{id}}  F3.1/F3.2/F3.3
 *  - /api/todos/batch        F3.2 批量完成
 *  - /api/inputs             F1.4 输入暂存（30 天可重提）
 *  - /api/sync               F4.3 增量同步（多端共享，tombstone 下发）
 *  - /api/devices            F4.1 设备绑定
 *  - /api/remind/daily       F5.1 每日推送标记取走/确认
 *  - /api/stats/completion   F3.8 完成率看板
 *  - /api/config{,/db,/master/check,/history}  F6 配置中心
 */
private const val MASKED = "***"

// ============ /api/health ============
class HealthHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        val dbReady = try { ds.loadConfig(); true } catch (e: Exception) { false }
        sendJson(exchange, 200, buildJsonObject {
            put("status", "ok")
            put("time", nowIso())
            put("dbReady", dbReady)
            put("version", "0.2.0")
        })
    }
}

// ============ /api/extract ============
class ExtractHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        if (exchange.requestMethod != "POST") { sendJson(exchange, 405, "{\"error\":\"只支持 POST\"}"); return }
        try {
            val body = readBody(exchange)
            val obj = json.parseToJsonElement(body).jsonObject
            val type = runCatching {
                InputType.valueOf(obj["type"]?.jsonPrimitive?.contentOrNull ?: "TEXT")
            }.getOrDefault(InputType.TEXT)
            val text = obj["text"]?.jsonPrimitive?.contentOrNull
            val input = ExtractInput(
                type = type,
                text = text,
                imageBase64 = obj["imageBase64"]?.jsonPrimitive?.contentOrNull,
                mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull
            )
            if (type == InputType.TEXT && text.isNullOrBlank()) {
                sendJson(exchange, 400, buildJsonObject { put("error", "文本为空") }); return
            }
            if (type == InputType.IMAGE && input.imageBase64.isNullOrBlank()) {
                sendJson(exchange, 400, buildJsonObject { put("error", "图片数据为空") }); return
            }
            val deviceId = resolveDeviceId(exchange)
            // 同步可改变的 body 字段：inputId 表示这是「暂存重提」，saveInput 决定是否落暂存
            val inputId = obj["inputId"]?.jsonPrimitive?.contentOrNull
            val saveInput = obj["saveInput"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: (inputId == null)

            val cfg = try { ds.loadConfig() } catch (e: Exception) { null } ?: defaultAppConfig()
            val dec = decryptAppConfig(cfg)
            val extraction = runBlocking { router.extractDetailed(input, dec) }

            // F1.4：把原始输入落暂存（图片只存引用标记，不存 base64，避免撑爆 DB）
            var recordId = inputId
            if (saveInput && recordId == null) {
                recordId = try {
                    ds.createInput(
                        InputCreateRequest(
                            type = type,
                            text = text,
                            imageRef = if (type == InputType.IMAGE) "inline-image(${(input.imageBase64?.length ?: 0)}B)" else null
                        ),
                        deviceId
                    ).id
                } catch (e: Exception) { null }
            }

            sendJson(exchange, 200, buildJsonObject {
                put("drafts", json.encodeToString(extraction.result.drafts))
                put("modelUsed", JsonPrimitive(extraction.modelName))
                put("modelKind", JsonPrimitive(extraction.kind.name))
                put("fallbackFrom", JsonPrimitive(extraction.fallbackFrom?.name ?: ""))
                put("inputId", JsonPrimitive(recordId))
                put("needsConfirmCount", JsonPrimitive(extraction.result.drafts.count { it.needsConfirm }))
            })
        } catch (e: Exception) {
            e.printStackTrace()
            sendJson(exchange, 500, buildJsonObject { put("error", e.message ?: "AI 提炼失败") })
        }
    }
}

// ============ /api/todos ============
class TodosHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        try {
            val path = exchange.requestURI.path.removePrefix("/api/todos")
            val deviceId = resolveDeviceId(exchange)
            // ?scope=all → 不过滤设备（Web 端共享视图）；默认只看本设备创建的
            val shared = exchange.requestURI.query?.split("&")
                ?.any { it.equals("scope=all", true) } == true
            val filterDevice = if (shared) null else deviceId
            // 说明：URI.getPath() 本身不含 query，所以带 ?scope=all 时 path 仍是 /today 这类裸路径

            when {
                path.isEmpty() || path == "/" -> when (exchange.requestMethod) {
                    "GET" -> sendJson(exchange, 200, json.encodeToString(ds.listTodos("all", filterDevice)))
                    "POST" -> {
                        val req = json.decodeFromString(TodoCreateRequest.serializer(), readBody(exchange))
                        if (req.title.isBlank()) { sendJson(exchange, 400, "{\"error\":\"title 不能为空\"}"); return }
                        val item = ds.createTodo(req, deviceId)
                        sendJson(exchange, 201, json.encodeToString(item))
                    }
                    else -> sendJson(exchange, 405, "{\"error\":\"method\"}")
                }
                path == "/today" -> sendList(exchange, ds.listTodos("today", filterDevice))
                path == "/week" -> sendList(exchange, ds.listTodos("week", filterDevice))
                path == "/overdue" -> sendList(exchange, ds.listTodos("overdue", filterDevice))
                // 兼容 /api/todos/today?scope=all 这类带作用域的写法
                path.startsWith("/today") -> sendList(exchange, ds.listTodos("today", filterDevice))
                path.startsWith("/week") -> sendList(exchange, ds.listTodos("week", filterDevice))
                path.startsWith("/overdue") -> sendList(exchange, ds.listTodos("overdue", filterDevice))
                path == "/batch" -> {
                    if (exchange.requestMethod != "POST") { sendJson(exchange, 405, "{\"error\":\"POST\"}"); return }
                    val req = json.decodeFromString(TodoBatchCompleteRequest.serializer(), readBody(exchange))
                    val r = ds.batchSetCompleted(req.ids, req.isCompleted, req.deviceId)
                    sendJson(exchange, 200, json.encodeToString(r))
                }
                path.startsWith("/") -> {
                    val id = path.removePrefix("/")
                    when (exchange.requestMethod) {
                        "GET" -> {
                            val item = ds.getTodo(id)
                            if (item == null || item.deleted) sendJson(exchange, 404, "{\"error\":\"not found\"}")
                            else sendJson(exchange, 200, json.encodeToString(item))
                        }
                        "PUT", "POST" -> {
                            // POST 是给手表/旧客户端（无法发 PUT 的场景）兜底
                            val req = json.decodeFromString(TodoUpdateRequest.serializer(), readBody(exchange))
                            val item = ds.updateTodo(id, req)
                            if (item == null) sendJson(exchange, 404, "{\"error\":\"not found\"}")
                            else sendJson(exchange, 200, json.encodeToString(item))
                        }
                        "DELETE" -> {
                            if (ds.deleteTodo(id)) sendJson(exchange, 204, "")
                            else sendJson(exchange, 404, "{\"error\":\"not found\"}")
                        }
                        else -> sendJson(exchange, 405, "{\"error\":\"method\"}")
                    }
                }
                else -> sendJson(exchange, 404, "{\"error\":\"not found\"}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sendJson(exchange, 500, buildJsonObject { put("error", e.message ?: "internal") })
        }
    }

    private fun sendList(exchange: HttpExchange, items: List<TodoItem>) {
        sendJson(exchange, 200, json.encodeToString(items))
    }
}

// ============ /api/inputs（F1.4 输入暂存） ============
class InputsHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        val deviceId = resolveDeviceId(exchange)
        val path = exchange.requestURI.path.removePrefix("/api/inputs")
        try {
            when {
                path.isEmpty() || path == "/" -> when (exchange.requestMethod) {
                    "GET" -> {
                        val includeExpired = exchange.requestURI.query?.contains("includeExpired=true") == true
                        sendJson(exchange, 200, json.encodeToString(ds.listInputs(deviceId, includeExpired)))
                    }
                    "POST" -> {
                        val req = json.decodeFromString(InputCreateRequest.serializer(), readBody(exchange))
                        val rec = ds.createInput(req, req.deviceId ?: deviceId)
                        sendJson(exchange, 201, json.encodeToString(rec))
                    }
                    else -> sendJson(exchange, 405, "{\"error\":\"method\"}")
                }
                path == "/pending" -> {
                    sendJson(exchange, 200, buildJsonObject { put("count", JsonPrimitive(ds.pendingInputCount(deviceId))) })
                }
                path == "/cleanup" -> {
                    val n = ds.expireOldInputs(30)
                    sendJson(exchange, 200, buildJsonObject { put("expired", JsonPrimitive(n)) })
                }
                path.startsWith("/") -> {
                    val id = path.removePrefix("/")
                    val rec = ds.getInput(id)
                    if (rec == null) { sendJson(exchange, 404, "{\"error\":\"not found\"}"); return }
                    when (exchange.requestMethod) {
                        "GET" -> sendJson(exchange, 200, json.encodeToString(rec))
                        "PUT", "POST" -> {
                            // 重提后回填产出的待办 id
                            val o = json.parseToJsonElement(readBody(exchange)).jsonObject
                            val ids = o["todoIds"]?.jsonPrimitive?.contentOrNull
                                ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                            val pending = o["processed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                            ds.markInputProcessed(id, ids, pending)
                            sendJson(exchange, 200, "{\"ok\":true}")
                        }
                        else -> sendJson(exchange, 405, "{\"error\":\"method\"}")
                    }
                }
                else -> sendJson(exchange, 404, "{\"error\":\"not found\"}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sendJson(exchange, 500, buildJsonObject { put("error", e.message ?: "inputs error") })
        }
    }
}

// ============ /api/sync ============
class SyncHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        if (exchange.requestMethod != "POST") { sendJson(exchange, 405, "{\"error\":\"POST only\"}"); return }
        try {
            val req = json.decodeFromString(SyncRequest.serializer(), readBody(exchange))
            val resp = ds.sync(req)
            sendJson(exchange, 200, json.encodeToString(resp))
        } catch (e: Exception) {
            e.printStackTrace()
            sendJson(exchange, 500, buildJsonObject { put("error", e.message ?: "sync error") })
        }
    }
}

// ============ /api/stats/completion ============
class StatsHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        try {
            val shared = exchange.requestURI.query?.contains("scope=all") == true
            val s = ds.completionStats(if (shared) null else resolveDeviceId(exchange))
            sendJson(exchange, 200, buildJsonObject {
                put("weekCompleted", JsonPrimitive(s.weekCompleted))
                put("weekOverdue", JsonPrimitive(s.weekOverdue))
                put("completionRate", JsonPrimitive(s.completionRate))
                put("streakDays", JsonPrimitive(s.streakDays))
                put("totalPending", JsonPrimitive(s.totalPending))
            })
        } catch (e: Exception) {
            sendJson(exchange, 500, buildJsonObject { put("error", e.message ?: "stats error") })
        }
    }
}

// ============ /api/devices（F4.1 设备绑定） ============
class DevicesHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        try {
            val path = exchange.requestURI.path.removePrefix("/api/devices")
            when {
                (path.isEmpty() || path == "/") && exchange.requestMethod == "GET" ->
                    sendJson(exchange, 200, json.encodeToString(ds.listDevices()))
                (path.isEmpty() || path == "/") && exchange.requestMethod == "POST" -> {
                    val req = json.decodeFromString(DeviceBindRequest.serializer(), readBody(exchange))
                    ds.bindDevice(req.deviceId, req.name, req.kind)
                    sendJson(exchange, 200, buildJsonObject { put("ok", JsonPrimitive(true)) })
                }
                else -> sendJson(exchange, 404, "{\"error\":\"not found\"}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sendJson(exchange, 500, buildJsonObject { put("error", e.message ?: "devices error") })
        }
    }
}

// ============ /api/remind/daily（F5.1 / F5.5） ============
class RemindHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        val path = exchange.requestURI.path.removePrefix("/api/remind")
        val deviceId = resolveDeviceId(exchange)
        try {
            when {
                path == "/daily" && exchange.requestMethod == "GET" -> {
                    val cfg = try { ds.loadConfig() } catch (e: Exception) { null } ?: defaultAppConfig()
                    val pending = try { ds.hasDailyPending(deviceId) } catch (e: Exception) { false }
                    val items = if (pending) try { ds.listTodos("today", null) } catch (e: Exception) { emptyList() } else emptyList()
                    sendJson(exchange, 200, buildJsonObject {
                        put("pending", JsonPrimitive(pending))
                        put("date", JsonPrimitive(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()))
                        put("pushAt", JsonPrimitive("%02d:%02d".format(cfg.dailyPushHour, cfg.dailyPushMinute)))
                        put("enabled", JsonPrimitive(cfg.dailyPushEnabled))
                        // 顺带把端侧要用到的参数带回，省一次 /api/config 往返
                        put("defaultRemindOffsetMinutes", JsonPrimitive(cfg.defaultRemindOffsetMinutes))
                        put("imageMaxEdge", JsonPrimitive(cfg.routing.imageMaxEdge))
                        put("keyTaskRemindEnabled", JsonPrimitive(cfg.keyTaskRemindEnabled))
                        put("items", json.encodeToString(items))
                    })
                }
                path == "/daily/ack" && exchange.requestMethod == "POST" -> {
                    ds.ackDailyPending(deviceId)
                    sendJson(exchange, 200, "{\"ok\":true}")
                }
                // 关键任务到点提醒的待办清单（手表/手机 pull 后各自设定点闹钟，F5.2）
                path == "/keytasks" && exchange.requestMethod == "GET" -> {
                    val all = ds.listTodos("all", null)
                    val items = all.filter { it.isKeyTask && !it.isCompleted && !it.deleted && it.remindAt != null }
                    sendJson(exchange, 200, json.encodeToString(items))
                }
                else -> sendJson(exchange, 404, "{\"error\":\"not found\"}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sendJson(exchange, 500, buildJsonObject { put("error", e.message ?: "remind error") })
        }
    }
}

// ============ /api/config ============
class ConfigHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        val path = exchange.requestURI.path.removePrefix("/api/config")
        try {
            when {
                path.isEmpty() || path == "/" -> when (exchange.requestMethod) {
                    "GET" -> {
                        // F6.2 安全要求：只回传「是否已配置」，绝不回传明文 key（旧实现会泄密，已修正）
                        val cfg = ds.loadConfig() ?: defaultAppConfig()
                        sendJson(exchange, 200, json.encodeToString(maskAppConfig(cfg)))
                    }
                    "POST" -> {
                        // 主密码门禁（F6.1）
                        if (!checkMasterPwd(exchange)) { denyMaster(exchange); return }
                        val req = json.decodeFromString(AppConfig.serializer(), readBody(exchange))
                        val prev = ds.loadConfig()
                        // 掩码占位符表示「保持原值不变」，避免前端拿到明文后再回写
                        val merged = mergeMaskedKeys(req, prev ?: defaultAppConfig())
                        val enc = encryptAppConfig(merged)
                        val changedBy = resolveDeviceId(exchange)
                        val saved = ds.saveConfig(enc, changedBy, diffSummary(prev, merged))
                        if (saved.configHistoryEnabled) {
                            try { ds.saveConfigHistory(saved, changedBy, diffSummary(prev, merged)) }
                            catch (e: Exception) { println("[saym-backend] 配置历史写入失败：${e.message}") }
                        }
                        sendJson(exchange, 200, json.encodeToString(maskAppConfig(saved)))
                    }
                    else -> sendJson(exchange, 405, "{\"error\":\"method\"}")
                }
                path == "/db" -> {
                    // F6.4 只读展示当前 Neon 库（不返回密码）
                    sendJson(exchange, 200, buildJsonObject {
                        put("jdbcUrl", JsonPrimitive(redactPassword(startup.neonJdbcUrl)))
                        put("user", JsonPrimitive(startup.neonUser))
                        put("note", JsonPrimitive("DB 连接串属启动配置，不在此处修改（D13 鸡生蛋边界）。改请编辑服务器 .env 或环境变量后重启。"))
                        put("pool", JsonPrimitive(startup.dbPool))
                    })
                }
                path == "/master/check" -> {
                    if (exchange.requestMethod != "POST") { sendJson(exchange, 405, "{\"error\":\"POST\"}"); return }
                    if (checkMasterPwd(exchange)) sendJson(exchange, 200, "{\"ok\":true}")
                    else denyMaster(exchange)
                }
                path == "/history" -> {
                    if (exchange.requestMethod != "GET") { sendJson(exchange, 405, "{\"error\":\"GET\"}"); return }
                    sendJson(exchange, 200, json.encodeToString(ds.listConfigHistory()))
                }
                // F6.6 回滚：把历史版本重新写回为最新版本
                path == "/rollback" -> {
                    if (exchange.requestMethod != "POST") { sendJson(exchange, 405, "{\"error\":\"POST\"}"); return }
                    if (!checkMasterPwd(exchange)) { denyMaster(exchange); return }
                    val o = json.parseToJsonElement(readBody(exchange)).jsonObject
                    val v = o["version"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: run { sendJson(exchange, 400, "{\"error\":\"缺少 version\"}"); return }
                    val hist = ds.getConfigHistoryVersion(v)
                        ?: run { sendJson(exchange, 404, "{\"error\":\"找不到该版本\"}"); return }
                    val snap = json.decodeFromString(AppConfig.serializer(), hist.snapshotJson)
                    val enc = encryptAppConfig(snap)
                    val by = resolveDeviceId(exchange)
                    val saved = ds.saveConfig(enc, by, "回滚到 v$v")
                    try { ds.saveConfigHistory(saved, by, "回滚到 v$v") } catch (e: Exception) {}
                    sendJson(exchange, 200, json.encodeToString(maskAppConfig(saved)))
                }
                else -> sendJson(exchange, 404, "{\"error\":\"not found\"}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sendJson(exchange, 500, buildJsonObject { put("error", e.message ?: "config error") })
        }
    }
}

// ============ 静态资源 + fallback ============
class StaticHandler(private val dir: File) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        if (exchange.requestURI.path.startsWith("/api/")) {
            sendJson(exchange, 404, "{\"error\":\"not found\"}")
            return
        }
        var rel = exchange.requestURI.path.removePrefix("/").ifEmpty { "index.html" }
        // 防目录穿越
        rel = rel.replace("..", "").trimStart('/')
        var f = File(dir, rel)
        if (f.isDirectory) f = File(f, "index.html")
        if (!f.exists()) {
            // PWA 单页：找不到文件时回 index.html
            f = File(dir, "index.html")
            if (!f.exists()) {
                sendJson(exchange, 200, fallbackIndexHtml()); return
            }
        }
        try {
            val bytes = Files.readAllBytes(f.toPath())
            exchange.responseHeaders.set("Content-Type", mimeOf(f.name))
            exchange.responseHeaders.set("Cache-Control", if (f.name == "index.html" || f.name == "sw.js") "no-cache" else "public, max-age=600")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (e: IOException) {
            sendJson(exchange, 404, "{\"error\":\"file not found: ${f.name}\"}")
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "webmanifest", "json.manifest" -> "application/manifest+json"
        else -> "application/octet-stream"
    }
}

class HtmlHandler(private val html: String) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        if (exchange.requestURI.path.startsWith("/api/")) {
            sendJson(exchange, 404, "{\"error\":\"not found\"}"); return
        }
        val b = html.toByteArray()
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, b.size.toLong())
        exchange.responseBody.use { it.write(b) }
    }
}

/**
 * 从 classpath 提供 PWA 静态资源（打包成 jar 后用 `java -jar backend.jar` 启动的场景）。
 * 初版只查文件系统目录，导致脱离源码树运行时前端 404 —— 这里补齐。
 */
class ClasspathStaticHandler(private val root: String = "/pwa-static/") : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (handleCors(exchange)) return
        if (exchange.requestURI.path.startsWith("/api/")) {
            sendJson(exchange, 404, "{\"error\":\"not found\"}"); return
        }
        var rel = exchange.requestURI.path.removePrefix("/").ifEmpty { "index.html" }
        rel = rel.replace("..", "").trimStart('/')
        var bytes = readResource(rel)
        if (bytes == null) bytes = readResource("index.html")
        if (bytes == null) {
            sendJson(exchange, 200, fallbackIndexHtml()); return
        }
        exchange.responseHeaders.set("Content-Type", mimeOf(rel))
        exchange.responseHeaders.set("Cache-Control", if (rel.endsWith(".html") || rel == "sw.js") "no-cache" else "public, max-age=600")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun readResource(rel: String): ByteArray? = try {
        javaClass.getResourceAsStream(root + rel)?.use { it.readBytes() }
    } catch (e: Exception) { null }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "webmanifest" -> "application/manifest+json"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }
}

// ============ 工具：配置加解密 / 掩码 ============
internal fun decryptAppConfig(cfg: AppConfig): AppConfig {
    val key = startup.configKey
    return cfg.copy(models = cfg.models.mapValues { (_, mc) ->
        try {
            if (mc.apiKey.isBlank()) mc
            else mc.copy(apiKey = CryptoUtil.decrypt(mc.apiKey, key))
        } catch (e: Exception) { mc } // 兼容历史明文配置：解密失败当明文用
    })
}

internal fun encryptAppConfig(cfg: AppConfig): AppConfig {
    val key = startup.configKey
    return cfg.copy(models = cfg.models.mapValues { (_, mc) ->
        if (mc.apiKey.isBlank()) mc
        else try {
            // 已是密文则不再加密；明文则加密
            if (looksLikeCipher(mc.apiKey)) mc
            else mc.copy(apiKey = CryptoUtil.encrypt(mc.apiKey, key))
        } catch (e: Exception) { mc }
    })
}

/** 对外只暴露「有没有配 key」，不暴露明文 */
internal fun maskAppConfig(cfg: AppConfig): AppConfig =
    cfg.copy(models = cfg.models.mapValues { (_, mc) ->
        mc.copy(apiKey = if (mc.apiKey.isBlank()) "" else MASKED)
    })

/** 前端回传掩码时保留库里原值（原值可能是密文，这里保持密文不动） */
internal fun mergeMaskedKeys(incoming: AppConfig, prev: AppConfig): AppConfig =
    incoming.copy(models = incoming.models.mapValues { (kind, mc) ->
        if (mc.apiKey == MASKED || mc.apiKey.isBlank()) {
            val old = prev.models[kind]?.apiKey
            if (mc.apiKey == MASKED && !old.isNullOrBlank()) mc.copy(apiKey = old)
            else if (mc.apiKey.isBlank()) mc.copy(apiKey = "")
            else mc
        } else mc
    })

internal fun looksLikeCipher(s: String): Boolean {
    if (s.length < 24) return false
    return try { java.util.Base64.getDecoder().decode(s); true } catch (e: Exception) { false }
}

/** 修正：旧实现的正则匹配不到 Neon 连接串常见的 ?password= 形式 */
internal fun redactPassword(jdbcUrl: String): String =
    jdbcUrl.replace(Regex("(?i)(password=)[^&;]*"), "$1***")

/** 配置改动摘要（F6.7 改动日志） */
internal fun diffSummary(prev: AppConfig?, next: AppConfig): String {
    if (prev == null) return "首次写入配置"
    val changed = ArrayList<String>()
    if (prev.routing != next.routing) changed.add("分流规则")
    next.models.forEach { (kind, mc) ->
        val old = prev.models[kind]
        when {
            old == null -> changed.add("${kind} 新增")
            old.apiKey != mc.apiKey -> changed.add("${kind} API Key")
            old.baseUrl != mc.baseUrl || old.modelName != mc.modelName -> changed.add("${kind} 接入点")
            old.temperature != mc.temperature || old.maxTokens != mc.maxTokens -> changed.add("${kind} 参数")
            old.supportsVision != mc.supportsVision -> changed.add("${kind} 图片能力")
        }
    }
    if (prev.dailyPushHour != next.dailyPushHour || prev.dailyPushMinute != next.dailyPushMinute) changed.add("每日推送时间")
    if (prev.dailyPushEnabled != next.dailyPushEnabled) changed.add("每日推送开关")
    if (prev.defaultRemindOffsetMinutes != next.defaultRemindOffsetMinutes) changed.add("默认提前量")
    if (prev.keyTaskRemindEnabled != next.keyTaskRemindEnabled) changed.add("定点提醒开关")
    if (prev.autoSuggestDeadline != next.autoSuggestDeadline) changed.add("自动建议时间")
    if (prev.maxDraftsPerInput != next.maxDraftsPerInput) changed.add("单次提炼上限")
    return if (changed.isEmpty()) "无实质改动" else changed.joinToString("；")
}

// ============ 每日推送标记（F5.1 / F5.5） ============
private var lastPushDate: String? = null

/**
 * 后端不能直接给手机/手表推送（D11：由端侧 AlarmManager 本地提醒），
 * 这里只按 app_config 配置的时间把「今日清单待推送」标记落库，
 * 端侧 /api/remind/daily 取走后本地设定点闹钟。
 */
fun startDailyPushScheduler() {
    val exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "saym-daily-push").apply { setDaemon(true) }
    }
    exec.scheduleAtFixedRate({
        try {
            val cfg = ds.loadConfig() ?: return@scheduleAtFixedRate
            if (!cfg.dailyPushEnabled) return@scheduleAtFixedRate
            val zone = java.time.ZoneId.of("Asia/Shanghai")
            val now = java.time.ZonedDateTime.now(zone)
            val today = now.toLocalDate().toString()
            if (lastPushDate == today) return@scheduleAtFixedRate
            // 修复：原写法拿 LocalDateTime 和 LocalTime 比较（类型不匹配，编译不过）
            val target: java.time.LocalTime =
                java.time.LocalTime.of(cfg.dailyPushHour.coerceIn(0, 23), cfg.dailyPushMinute.coerceIn(0, 59))
            if (!now.toLocalTime().isBefore(target)) {
                ds.listDevices().forEach { d -> if (d.kind != "watch") ds.markDailyPending(d.deviceId, null) }
                ds.expireOldInputs(30)
                lastPushDate = today
                println("[saym-backend] 已标记 $today 的每日清单待推送（$target）")
            }
        } catch (e: Exception) {
            println("[saym-backend] 每日推送调度异常（已忽略）：${e.message}")
        }
    }, 30, 60, java.util.concurrent.TimeUnit.SECONDS)
}

// PWA 最小 HTML（fallback；正常走 src/main/resources/pwa-static/ 下的完整 PWA）
fun fallbackIndexHtml(): String = """
<!DOCTYPE html><html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>说一嘴 · 待办提炼</title></head><body>
<h1>说一嘴 · 待办提炼</h1>
<p>⚠️ 未找到 PWA 静态资源目录（pwa-static/ 或 src/main/resources/pwa-static/）。</p>
<p>请确认发布包内含 <code>pwa-static/index.html</code> 后再访问本页。</p>
<p>API 仍可用：<code>/api/health</code>、<code>/api/todos/today</code>、<code>/api/extract</code>。</p>
</body></html>
""".trimIndent()
