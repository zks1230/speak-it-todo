package com.saym.backend

import com.saym.shared.ai.AiRouter
import com.saym.shared.ai.defaultExtractors
import com.saym.shared.ai.defaultSupportsVision
import com.saym.shared.crypto.BcryptUtil
import com.saym.shared.crypto.CryptoUtil
import com.saym.shared.db.NeonDataSource
import com.saym.shared.db.neonDataSource
import com.saym.shared.models.AppConfig
import com.saym.shared.models.ModelConfig
import com.saym.shared.models.ModelKind
import com.saym.shared.models.RoutingRule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

lateinit var ds: NeonDataSource
lateinit var router: AiRouter
lateinit var startup: StartupConfig
val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

/**
 * 后端入口：HTTP server（JDK 自带 com.sun.net.httpserver，避免引入 Ktor 减小依赖）。
 * 端口默认 8080，可由 SAYM_PORT 覆盖。
 */
fun main(args: Array<String>) {
    startup = StartupConfigLoader.load()
    println("[saym-backend] 启动配置加载完成: port=${startup.port}, bind=${startup.bindAddr}, dbPool=${startup.dbPool}")

    // Neon 数据源 + 建表
    ds = neonDataSource(startup.neonJdbcUrl, startup.neonUser, startup.neonPassword, startup.dbPool)
    try {
        ds.ensureSchema()
        println("[saym-backend] Neon schema 就绪")
        // 默认配置兜底（首次启动无配置时，给一个空 AppConfig 让流程能跑）
        if (ds.loadConfig() == null) {
            ds.saveConfig(defaultAppConfig(), "system", "首次启动：写入默认空配置")
            println("[saym-backend] 已写入默认配置（请尽快到配置中心填 API key）")
        }
    } catch (e: Exception) {
        println("[saym-backend] ⚠️ Neon 连接/建表失败：${e.message}")
        println("[saym-backend] 后端仍会启动，但 DB 相关接口将报错。请检查启动配置 NEON_* 是否正确。")
        e.printStackTrace()
    }

    router = AiRouter(defaultExtractors())

    val server = HttpServer.create(InetSocketAddress(startup.bindAddr, startup.port), 0)
    server.executor = Executors.newFixedThreadPool(8)

    // 静态 PWA 资源：优先运行目录下的 pwa-static/（便于改完直接生效），
    // 其次源码树 src/main/resources/pwa-static/，最后从 **classpath**（打进 jar 的资源）读取。
    val staticDir = File("pwa-static").takeIf { it.exists() }
        ?: File("src/main/resources/pwa-static").takeIf { it.exists() }
    if (staticDir != null) {
        server.createContext("/", StaticHandler(staticDir))
        println("[saym-backend] PWA 静态资源目录：${staticDir.absolutePath}")
    } else if (ClasspathStaticHandler::class.java.getResource("/pwa-static/index.html") != null) {
        server.createContext("/", ClasspathStaticHandler())
        println("[saym-backend] PWA 静态资源来自 classpath:/pwa-static（jar 内置）")
    } else {
        server.createContext("/", HtmlHandler(fallbackIndexHtml()))
        println("[saym-backend] ⚠️ 未找到 pwa-static 资源，Web 端将只显示提示页")
    }

    // API
    server.createContext("/api/extract", ExtractHandler())
    server.createContext("/api/todos", TodosHandler())
    server.createContext("/api/inputs", InputsHandler())
    server.createContext("/api/sync", SyncHandler())
    server.createContext("/api/devices", DevicesHandler())
    server.createContext("/api/remind", RemindHandler())
    server.createContext("/api/stats/completion", StatsHandler())
    server.createContext("/api/config", ConfigHandler())
    server.createContext("/api/health", HealthHandler())

    server.start()
    println("[saym-backend] HTTP server 监听 http://${startup.bindAddr}:${startup.port}")
    println("[saym-backend] 提示：手机端配置中心填入本机 IP:8080 即可；Web 端用浏览器直接访问")

    // 每日推送标记：8:00 在 app_config 中读 dailyPushHour/Minute；后端不能直接给手表推送，
    // 只负责把"今日待办待推送"标记记在内存里供手机/手表 pull（决策 D11）。
    startDailyPushScheduler()

    // 阻塞主线程
    Thread.currentThread().join()
}

fun defaultAppConfig(): AppConfig {
    // 默认路由：文本→DeepSeek，图片→DeepSeek（enableImageRouting=false，先实测，D5）
    val models = ModelKind.values().associateWith { k ->
        ModelConfig(
            kind = k,
            apiKey = "",                       // 必须前端配置中心填
            baseUrl = when (k) {
                ModelKind.DEEPSEEK -> "https://api.deepseek.com/v1"
                ModelKind.DOUBAO_VISION -> "https://ark.cn-beijing.volces.com/api/v3"
                ModelKind.QWEN_VL -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
                ModelKind.CUSTOM -> ""
            },
            modelName = when (k) {
                ModelKind.DEEPSEEK -> "deepseek-chat"
                ModelKind.DOUBAO_VISION -> "doubao-vision-pro"
                ModelKind.QWEN_VL -> "qwen-vl-max"
                ModelKind.CUSTOM -> "rule-based"
            },
            // 关键：DeepSeek 文本型号不接收图片内容块，默认标记 false，
            // 若实测发现 deepseek 新版支持视觉，到配置中心把该开关打开即可（D5 实测留白）
            supportsVision = defaultSupportsVision(k)
        )
    }
    return AppConfig(models = models, routing = RoutingRule())
}

// ============ 主密码校验 ============
fun checkMasterPwd(exchange: HttpExchange): Boolean {
    val provided = exchange.requestHeaders.getFirst("X-Master-Password") ?: return false
    return BcryptUtil.check(provided, startup.masterHash)
}

fun denyMaster(exchange: HttpExchange) {
    sendJson(exchange, 401, buildJsonObject { put("error", "主密码校验失败") })
}

// ============ 工具 ============
fun sendJson(exchange: HttpExchange, code: Int, body: Any) {
    val text = when (body) {
        is String -> body
        is JsonObject -> json.encodeToString(JsonObject.serializer(), body)
        else -> body.toString()
    }
    val bytes = text.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
    exchange.responseHeaders.set("Access-Control-Allow-Headers", "*")
    exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS")
    exchange.sendResponseHeaders(code, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

fun readBody(exchange: HttpExchange): String =
    exchange.requestBody.bufferedReader().use { it.readText() }

// CORS preflight 处理
fun handleCors(exchange: HttpExchange): Boolean {
    if (exchange.requestMethod == "OPTIONS") {
        sendJson(exchange, 204, "")
        return true
    }
    return false
}

// 设备 ID 解析：从 X-Device-Id header 或 query ?device_id=
fun resolveDeviceId(exchange: HttpExchange): String =
    exchange.requestHeaders.getFirst("X-Device-Id")
        ?: startup.defaultDeviceId
        ?: "default-device"

fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
