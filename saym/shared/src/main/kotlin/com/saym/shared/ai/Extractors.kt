package com.saym.shared.ai

import com.saym.shared.models.ExtractInput
import com.saym.shared.models.ExtractResult
import com.saym.shared.models.InputType
import com.saym.shared.models.ModelConfig
import com.saym.shared.models.ModelKind
import com.saym.shared.models.TodoDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * OpenAI 兼容协议的提炼器实现（DeepSeek / 豆包视觉版 / 通义 Qwen-VL 都是该协议）。
 *
 * 关键修正（相对初版）：
 *  1. 文本模型（如 deepseek-chat）**不能**收 image_url 内容块，会直接 400。图片输入只在
 *     目标模型 supportsVision=true 时按视觉报文发送，否则抛可读错误并触发 AiRouter 回退。
 *  2. isKeyTask / confidence 的取值不再假设一定是字符串 "true"/"0.3"（部分模型返回真实
 *     布尔或数字），布尔与数字字面量都能正确解析。
 *  3. 解析支持三种常见返回形态：JSON 数组、{"drafts":[...]} 包装、单对象。
 */
open class OpenAiCompatibleExtractor(
    override val kind: ModelKind,
    private val label: String
) : TaskExtractor {

    override suspend fun extract(input: ExtractInput, model: ModelConfig): ExtractResult =
        withContext(Dispatchers.IO) {
            if (model.apiKey.isBlank()) {
                error("$label 未配置 API Key，请到 设置 → 配置中心 → AI 模型 里填写")
            }
            if (model.baseUrl.isBlank()) error("$label 未配置 Base URL")
            if (input.type == InputType.IMAGE && !model.supportsVision) {
                error("模型 ${model.modelName}（$label）未标记为支持图片，无法处理图片输入。请在配置中心开启「支持图片」或把图片场景分流到视觉模型。")
            }
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_1_1)
                .build()
            val body = buildRequestBody(input, model)
            val req = HttpRequest.newBuilder()
                .uri(URI.create(model.baseUrl.trimEnd('/') + "/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer ${model.apiKey}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                error("$label 调用失败: HTTP ${resp.statusCode()} ${resp.body().take(500)}")
            }
            val raw = resp.body()
            val drafts = parseOpenAiJsonResponse(raw)
            if (drafts.isEmpty()) {
                error("$label 返回内容无法解析出待办（原始响应前 300 字：${raw.take(300)}）")
            }
            ExtractResult(drafts = drafts, modelUsed = model.modelName, raw = raw)
        }

    private fun buildRequestBody(input: ExtractInput, model: ModelConfig): String {
        val systemPrompt = """
            你是"待办提炼助手"。从用户输入中提炼出"要做的事"，每条任务输出一个 JSON 对象。
            必含字段：title(任务标题，简洁可执行)、deadline(ISO-8601 日期或日期时间，可空)、subject(学科/分类，可空)、notes(关键备注，可空)、isKeyTask(是否关键任务，布尔值)、confidence(0~1 的数字，表示你的把握度)。
            时间识别规则：支持"明天""周五""下周一""月底前"等自然语言，识别后转为 ISO-8601（只有日期就用 yyyy-MM-dd，带时间就用 yyyy-MM-ddTHH:mm:ss）。
            今天是 {{TODAY}}，请以此为基准推算相对时间。
            无明确时间则 deadline 填 null，不要编造。
            批量场景：一段话或一张图含多条任务，请拆成多条。
            如果输入内容不是任何待办（例如纯闲聊、无法辨认的图片），返回空数组 []。
            仅输出一个 JSON 数组，不要前后多余文字、不要 markdown 代码块标记。
        """.trimIndent().replace("{{TODAY}}", todayForPrompt())
        val userContent = when (input.type) {
            InputType.TEXT -> buildJsonObject {
                put("type", "text")
                put("text", input.text ?: "")
            }
            InputType.IMAGE -> {
                val imageUrl = "data:${input.mimeType ?: "image/jpeg"};base64,${input.imageBase64 ?: ""}"
                buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", imageUrl) })
                }
            }
        }
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") { add(userContent) }
            })
        }
        val req = buildJsonObject {
            put("model", model.modelName)
            put("messages", messages)
            put("temperature", model.temperature.toDouble())
            put("max_tokens", model.maxTokens)
        }
        return Json.encodeToString(JsonObject.serializer(), req)
    }

    /** 解析 OpenAI 兼容响应：choices[0].message.content 是模型输出（应为 JSON 数组） */
    fun parseOpenAiJsonResponse(raw: String): List<TodoDraft> {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val choices = root["choices"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        if (choices.isEmpty()) return emptyList()
        val content = choices[0].jsonObject["message"]?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
            ?: return emptyList()
        return parseDraftsFromContent(content)
    }

    internal fun parseDraftsFromContent(contentRaw: String): List<TodoDraft> {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val cleaned = contentRaw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        // 容错：模型偶尔在 JSON 前后带解释文字，截取第一个 [ 到最后一个 ]
        val candidate = extractJsonPayload(cleaned)
        val element = runCatching { json.parseToJsonElement(candidate) }.getOrNull()
            ?: return listOf(
                TodoDraft(
                    title = cleaned.take(200),
                    confidence = 0.3f,
                    needsConfirm = true,
                    rawExtraction = contentRaw.take(500)
                )
            )
        val arr = when {
            element is kotlinx.serialization.json.JsonArray -> element
            element is JsonObject -> {
                val inner = element["drafts"] ?: element["todos"] ?: element["items"]
                when (inner) {
                    is kotlinx.serialization.json.JsonArray -> inner
                    is JsonObject -> kotlinx.serialization.json.JsonArray(listOf(inner))
                    else -> kotlinx.serialization.json.JsonArray(listOf(element))
                }
            }
            else -> return emptyList()
        }
        return arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = o.str("title") ?: o.str("task") ?: return@mapNotNull null
            TodoDraft(
                title = title.trim(),
                deadline = o.str("deadline")?.takeIf { it.isNotBlank() && !it.equals("null", true) },
                subject = o.str("subject")?.takeIf { it.isNotBlank() && !it.equals("null", true) },
                notes = o.str("notes")?.takeIf { it.isNotBlank() && !it.equals("null", true) },
                isKeyTask = o.bool("isKeyTask"),
                confidence = o.float("confidence") ?: 1f,
                rawExtraction = o.toString().take(500)
            )
        }
    }

    /** 从可能带前后缀的文本里截出 JSON 主体 */
    private fun extractJsonPayload(s: String): String {
        val startArr = s.indexOf('[')
        val startObj = s.indexOf('{')
        val start = when {
            startArr >= 0 && startObj >= 0 -> minOf(startArr, startObj)
            startArr >= 0 -> startArr
            startObj >= 0 -> startObj
            else -> return s
        }
        val end = maxOf(s.lastIndexOf(']'), s.lastIndexOf('}'))
        return if (end > start) s.substring(start, end + 1) else s.substring(start)
    }

    // ---- JSON 取值容错：既能吃 "true"/"0.3"，也能吃 true/0.3 ----
    private fun JsonObject.str(key: String): String? = runCatching {
        val v = this[key] ?: return null
        v.jsonPrimitive.contentOrNull
    }.getOrNull()

    private fun JsonObject.bool(key: String): Boolean {
        val p = this[key] ?: return false
        return runCatching { p.jsonPrimitive.contentOrNull }.getOrNull()
            ?.let { it.equals("true", true) || it == "1" } ?: false
    }

    private fun JsonObject.float(key: String): Float? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() }.getOrNull()

    private fun todayForPrompt(): String =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
}

/** DeepSeek（文本为主，默认文本场景，D5） */
class DeepSeekExtractor : OpenAiCompatibleExtractor(ModelKind.DEEPSEEK, "DeepSeek")

/** 豆包视觉版（火山引擎，OpenAI 兼容，图片场景分流用，D5 应对策略 2） */
class DoubaoVisionExtractor : OpenAiCompatibleExtractor(ModelKind.DOUBAO_VISION, "豆包视觉版")

/** 通义 Qwen-VL-Max（DashScope 兼容模式，图片场景分流用，D5 应对策略 2） */
class QwenVlExtractor : OpenAiCompatibleExtractor(ModelKind.QWEN_VL, "通义 Qwen-VL-Max")

/** 默认 Extractor 注册表（按 kind 取） */
fun defaultExtractors(): Map<ModelKind, TaskExtractor> = mapOf(
    ModelKind.DEEPSEEK to DeepSeekExtractor(),
    ModelKind.DOUBAO_VISION to DoubaoVisionExtractor(),
    ModelKind.QWEN_VL to QwenVlExtractor()
)

/** 各模型默认是否支持图片（写入默认配置，用户可在配置中心改） */
fun defaultSupportsVision(kind: ModelKind): Boolean = when (kind) {
    // deepseek-chat 是纯文本；若换成支持视觉的型号，请到配置中心打开「支持图片」开关
    ModelKind.DEEPSEEK -> false
    ModelKind.DOUBAO_VISION -> true
    ModelKind.QWEN_VL -> true
    ModelKind.CUSTOM -> false
}

/** 仅测试/自检用的本地兜底提炼器：不调外部 API，按行拆分。 */
class RuleBasedExtractor : TaskExtractor {
    override val kind: ModelKind = ModelKind.CUSTOM

    override suspend fun extract(input: ExtractInput, model: ModelConfig): ExtractResult {
        val text = input.text ?: return ExtractResult(emptyList(), "rule-based")
        val drafts = text.lines()
            .flatMap { it.split("，", "。", ";", "；") }
            .map { it.trim() }
            .filter { it.length >= 2 }
            .map { TodoDraft(title = it, confidence = 0.4f, needsConfirm = true) }
        return ExtractResult(drafts = drafts, modelUsed = "rule-based")
    }
}
