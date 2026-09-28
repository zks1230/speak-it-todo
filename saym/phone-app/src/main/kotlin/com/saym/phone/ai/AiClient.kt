package com.saym.phone.ai

import android.content.Context
import android.util.Base64
import com.saym.phone.model.ExtractOutcome
import com.saym.phone.model.TodoDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/**
 * 手机端直连大模型的 AI 提炼客户端（新架构：不再经过任何后端）。
 *
 * 对应决策：
 *  - D5：文本默认 DeepSeek；图片场景可分流到豆包视觉版 / 通义 Qwen-VL-Max
 *  - §6.1：候选模型**回退链**——首选没配 key / 停用 / 不支持图片时自动换下一个
 *  - F2.5/F2.6：草稿返回给 UI 预览，低置信度标「请确认」
 *
 * 安全说明：
 *  - API Key 只从 [com.saym.phone.cfg.LocalConfigStore] 读取（Keystore 加密存储），
 *    不写进代码、不写进日志、不出现在任何报错文案里。
 *  - 这里直接用 HttpURLConnection，不引第三方 HTTP 库，减小 APK 体积。
 */
object AiClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** 单次请求超时（图片场景给足，模型要识图 + 长输出） */
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 90_000

    /**
     * 提炼入口。
     * @param type "TEXT" 或 "IMAGE"
     * @param text 文本内容
     * @param imageBase64 图片 base64（不含 data: 前缀）
     * @param mimeType 图片 MIME
     */
    suspend fun extract(
        ctx: Context,
        type: String,
        text: String? = null,
        imageBase64: String? = null,
        mimeType: String? = "image/jpeg"
    ): ExtractOutcome = withContext(Dispatchers.IO) {
        val cfg = com.saym.phone.cfg.LocalConfigStore.load(ctx)
        val candidates = candidates(type, cfg)
        val tries = ArrayList<String>()
        var firstUsable: String? = null
        var lastError: String? = null

        for (kind in candidates) {
            val model = cfg.models[kind]
            if (model == null) { tries.add("$kind 缺少配置"); continue }
            if (!model.enabled) { tries.add("$kind 已停用"); continue }
            if (model.apiKey.isBlank()) { tries.add("$kind 未填 API Key"); continue }
            if (type == "IMAGE" && !model.supportsVision) { tries.add("$kind 未标记支持图片"); continue }
            val primary = firstUsable ?: kind.also { firstUsable = it }
            try {
                val drafts = callAndParse(kind, model, type, text, imageBase64, mimeType, cfg.routing.imageConfidenceThreshold)
                return@withContext ExtractOutcome(
                    drafts = drafts,
                    modelUsed = model.modelName,
                    modelKind = kind,
                    fallbackFrom = if (kind != primary) primary else null,
                    needsConfirmCount = drafts.count { it.needsConfirm }
                )
            } catch (e: Exception) {
                lastError = e.message
                tries.add("$kind: ${e.message?.take(100)}")
            }
        }
        val detail = tries.joinToString("；")
        throw IllegalStateException(
            "所有候选模型都不可用 → $detail" +
                (lastError?.let { "（最后错误：${it.take(80)}）" } ?: "") +
                "。请到 设置 → 配置中心 → AI 模型 检查 API Key / 模型名 / 是否支持图片。"
        )
    }

    /** 候选模型顺序：图片场景优先视觉模型，文本场景优先 DeepSeek（D5 / §6.1） */
    private fun candidates(type: String, cfg: com.saym.phone.cfg.LocalConfig): List<String> {
        val r = cfg.routing
        val primary = if (type == "IMAGE" && r.enableImageRouting) r.imageModel else r.textModel
        val list = arrayListOf(primary)
        if (type == "IMAGE") {
            val alt = if (primary == r.imageModel) r.textModel else r.imageModel
            if (alt != primary) list.add(alt)
            for (k in listOf("DOUBAO_VISION", "QWEN_VL")) if (k !in list) list.add(k)
        } else {
            val alt = if (primary == "DEEPSEEK") "QWEN_VL" else "DEEPSEEK"
            if (alt != primary) list.add(alt)
        }
        return list.distinct()
    }

    private fun callAndParse(
        kind: String,
        model: com.saym.phone.cfg.ModelCfg,
        type: String,
        text: String?,
        imageBase64: String?,
        mimeType: String?,
        threshold: Float
    ): List<TodoDraft> {
        if (model.baseUrl.isBlank()) error("未配置 Base URL")
        val body = buildRequestBody(model, type, text, imageBase64, mimeType)
        var conn: HttpURLConnection? = null
        try {
            val url = URL(model.baseUrl.trimEnd('/') + "/chat/completions")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${model.apiKey}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                // 注意：不要把 Authorization / apiKey 带进错误信息
                error(humanizeHttpError(code, resp))
            }
            val content = extractContent(resp) ?: error("响应里没有 choices[0].message.content")
            val drafts = parseDrafts(content)
            if (drafts.isEmpty()) error("没能从模型输出里解析出待办（模型可能没按 JSON 数组返回）")
            return drafts.map { it.copy(needsConfirm = it.confidence < threshold) }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            // 网络层异常（DNS / 超时 / TLS / 连接被拒）也翻译成人话
            error(humanizeNetworkError(e))
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun buildRequestBody(
        model: com.saym.phone.cfg.ModelCfg,
        type: String,
        text: String?,
        imageBase64: String?,
        mimeType: String?
    ): String {
        if (type == "IMAGE" && !model.supportsVision) {
            error("模型 ${model.modelName} 未标记支持图片")
        }
        if (type == "IMAGE" && imageBase64.isNullOrBlank()) error("图片数据为空")
        val systemPrompt = """
            你是"待办提炼助手"。从用户输入里提炼出"要做的事"，每条任务输出一个 JSON 对象。
            必含字段：title(任务标题，简洁可执行)、deadline(ISO-8601 日期或日期时间，可空)、subject(学科/分类，可空)、notes(关键备注，可空)、isKeyTask(是否关键任务，布尔值)、confidence(0~1 的数字，你的把握度)。
            时间识别规则：支持"明天""周五""下周一""月底前"等说法；只有日期用 yyyy-MM-dd，带时间用 yyyy-MM-ddTHH:mm:ss。
            今天是 {{TODAY}}，请以此为基准推算。
            没有明确时间就把 deadline 填 null，不要编造。
            一段话或一张图含多条任务时，拆成多条。
            如果内容不是任何待办（纯闲聊、无法辨认的图片），返回空数组 []。
            只输出一个 JSON 数组，前后不要任何多余文字、不要 markdown 代码块标记。
        """.trimIndent().replace("{{TODAY}}", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString())

        val userContent: JsonObject = if (type == "IMAGE") {
            val dataUrl = "data:${mimeType ?: "image/jpeg"};base64,$imageBase64"
            buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", dataUrl) })
            }
        } else {
            buildJsonObject {
                put("type", "text")
                put("text", text ?: "")
            }
        }
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "system"); put("content", systemPrompt)
            })
            add(buildJsonObject {
                put("role", "user"); putJsonArray("content") { add(userContent) }
            })
        }
        val req = buildJsonObject {
            put("model", model.modelName)
            put("messages", messages)
            put("temperature", model.temperature.toDouble())
            put("max_tokens", model.maxTokens)
        }
        return json.encodeToString(JsonObject.serializer(), req)
    }

    private fun extractContent(raw: String): String? = try {
        json.parseToJsonElement(raw).jsonObject["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) { null }

    /**
     * 把 HTTP 错误翻译成能直接照着做的人话。
     * 各家的错误体格式不完全一样，这里只抓关键词，抓不到就原样附上服务端返回。
     */
    internal fun humanizeHttpError(code: Int, body: String): String {
        val b = body.lowercase()
        val hint = when {
            code == 401 || b.contains("invalid api key") || b.contains("authentication") ||
                b.contains("invalid_api_key") || b.contains("unauthorized") ->
                "API Key 不对或已失效。请到 DeepSeek 平台重新复制一个（注意别带上空格或换行），再回设置里覆盖保存。"
            code == 402 || b.contains("insufficient") || b.contains("balance") ||
                b.contains("quota") || b.contains("exceeded your current") ->
                "账户余额不足或额度用完了。请到 DeepSeek 平台充值后重试。"
            code == 403 || b.contains("permission") ->
                "这个 Key 没有调用该模型的权限。请确认 Key 是同一个账号下创建的、且已开通该模型。"
            code == 404 || b.contains("model not found") || b.contains("does not exist") ->
                "模型名不对。DeepSeek 现行模型名是 deepseek-flash（旧的 deepseek-chat 已停用），" +
                    "请到设置 → 模型里核对「模型名」和「Base URL」。"
            code == 429 || b.contains("rate limit") || b.contains("too many requests") ->
                "请求太频繁，被限流了。等几十秒再试。"
            code == 413 || b.contains("too large") || b.contains("request body") ->
                "上传内容太大（图片超过限制）。请到设置 → 分流里把「图片压缩最长边」调小，比如 1024。"
            code == 400 && (b.contains("image") || b.contains("vision")) ->
                "这个模型不接受图片输入。DeepSeek 请用 deepseek-flash，并打开「该模型支持图片输入」开关；" +
                    "或者把图片场景分流到豆包 / 通义。"
            code == 400 ->
                "请求被拒绝（多半是参数问题）。"
            code >= 500 ->
                "模型服务端出错，通常是临时的，过一会儿再试。"
            else -> ""
        }
        val detail = body.take(200).ifBlank { "（无返回内容）" }
        return buildString {
            append("调用失败：HTTP $code")
            if (hint.isNotBlank()) append("\n原因建议：$hint")
            append("\n服务端原文：$detail")
        }
    }

    /** 把网络异常也翻译成人话 */
    internal fun humanizeNetworkError(e: Exception): String = when {
        e is java.net.UnknownHostException ->
            "连不上服务器（域名解析失败）。检查手机网络，或看设置里的 Base URL 有没有写错。"
        e is java.net.SocketTimeoutException ->
            "请求超时。可能是网络太慢，或图片太大 —— 可以把设置 → 分流里的「图片压缩最长边」调小。"
        e is javax.net.ssl.SSLException ->
            "HTTPS 握手失败。请确认 Base URL 用的是 https 且域名正确。"
        e is java.net.ConnectException ->
            "无法建立连接。检查网络，或 Base URL 是否写错（注意别少了 https://）。"
        else -> e.message ?: "未知错误"
    }

    /** 解析容错：JSON 数组 / {"drafts":[...]} / 单个对象 / 带前后缀说明文字 都能处理 */
    internal fun parseDrafts(contentRaw: String): List<TodoDraft> {
        val cleaned = contentRaw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        val candidate = extractJsonPayload(cleaned)
        val el = runCatching { json.parseToJsonElement(candidate) }.getOrNull()
            ?: return listOf(TodoDraft(title = cleaned.take(120), confidence = 0.3f, needsConfirm = true))
        val arr: JsonArray = when (el) {
            is JsonArray -> el
            is JsonObject -> {
                val inner = el["drafts"] ?: el["todos"] ?: el["items"]
                when (inner) {
                    is JsonArray -> inner
                    is JsonObject -> JsonArray(listOf(inner))
                    else -> JsonArray(listOf(el))
                }
            }
            else -> return emptyList()
        }
        return arr.mapNotNull { item ->
            val o = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = o.str("title") ?: o.str("task") ?: return@mapNotNull null
            TodoDraft(
                title = title.trim(),
                deadline = o.str("deadline")?.takeIf { it.isNotBlank() && !it.equals("null", true) },
                subject = o.str("subject")?.takeIf { it.isNotBlank() && !it.equals("null", true) },
                notes = o.str("notes")?.takeIf { it.isNotBlank() && !it.equals("null", true) },
                isKeyTask = o.bool("isKeyTask"),
                confidence = o.float("confidence") ?: 1f
            )
        }
    }

    private fun extractJsonPayload(s: String): String {
        val a = s.indexOf('[')
        val b = s.indexOf('{')
        val start = when {
            a >= 0 && b >= 0 -> minOf(a, b)
            a >= 0 -> a
            b >= 0 -> b
            else -> return s
        }
        val end = maxOf(s.lastIndexOf(']'), s.lastIndexOf('}'))
        return if (end > start) s.substring(start, end + 1) else s.substring(start)
    }

    private fun JsonObject.str(k: String): String? =
        runCatching { this[k]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.bool(k: String): Boolean =
        runCatching { this[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.let { it.equals("true", true) || it == "1" } ?: false

    private fun JsonObject.float(k: String): Float? =
        runCatching { this[k]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() }.getOrNull()

    /** 供配置中心「测试连接」用：只发一个最小文本请求，验证 key/URL/模型名是否可用 */
    suspend fun testConnection(ctx: Context, kind: String): String = withContext(Dispatchers.IO) {
        val cfg = com.saym.phone.cfg.LocalConfigStore.load(ctx)
        val model = cfg.models[kind] ?: error("找不到 $kind 配置")
        if (model.apiKey.isBlank()) error("$kind 还没填 API Key")
        val out = callAndParse(kind, model, "TEXT", "明天交数学卷子第三章", null, null, 0.7f)
        "连接成功：${model.modelName} 返回 ${out.size} 条草稿" +
            (out.firstOrNull()?.let { "（第一条：${it.title}）" } ?: "")
    }
}
