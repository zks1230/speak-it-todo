package com.saym.shared.ai

import com.saym.shared.models.AppConfig
import com.saym.shared.models.ExtractInput
import com.saym.shared.models.ExtractResult
import com.saym.shared.models.InputType
import com.saym.shared.models.ModelConfig
import com.saym.shared.models.ModelKind

/** AI 提炼接口（按输入类型路由到不同实现） */
interface TaskExtractor {
    val kind: ModelKind
    suspend fun extract(input: ExtractInput, model: ModelConfig): ExtractResult
}

/** 一次提炼实际用到的模型信息 */
data class AiExtraction(
    val result: ExtractResult,
    val kind: ModelKind,
    val modelName: String,
    /** 是否因为首选模型不可用而降级到了备用模型 */
    val fallbackFrom: ModelKind? = null
)

/**
 * 模型路由（D5 / §6.1）：
 *  - 文本场景 → routing.textModel（默认 DeepSeek）
 *  - 图片场景 → enableImageRouting ? routing.imageModel : routing.textModel（默认先实测 DeepSeek）
 *  - **回退链**：图片场景下若首选模型没配 key / 未启用 / 不支持图片，自动尝试另一个候选模型，
 *    避免「配置了图片分流但豆包 key 没填」时整个图片输入直接失败。
 */
class AiRouter(
    private val extractors: Map<ModelKind, TaskExtractor>
) {
    /** 按输入类型排出候选模型（第一个为优先） */
    fun candidates(input: ExtractInput, config: AppConfig): List<ModelKind> {
        val routing = config.routing
        val primary = if (input.type == InputType.IMAGE && routing.enableImageRouting) {
            routing.imageModel
        } else {
            routing.textModel
        }
        val list = ArrayList<ModelKind>()
        list.add(primary)
        // 图片场景：找另一个支持视觉的模型兜底
        if (input.type == InputType.IMAGE) {
            val alt = if (primary == routing.imageModel) routing.textModel else routing.imageModel
            if (alt != primary) list.add(alt)
            if (list.none { it == ModelKind.DOUBAO_VISION }) list.add(ModelKind.DOUBAO_VISION)
            if (list.none { it == ModelKind.QWEN_VL }) list.add(ModelKind.QWEN_VL)
        } else {
            val alt = if (primary == ModelKind.DEEPSEEK) ModelKind.QWEN_VL else ModelKind.DEEPSEEK
            if (alt != primary) list.add(alt)
        }
        return list.distinct()
    }

    /** 兼容旧调用点：返回首选 (kind, extractor) */
    fun forInput(input: ExtractInput, config: AppConfig): Pair<ModelKind, TaskExtractor> {
        val kind = candidates(input, config).first()
        val extractor = extractors[kind]
            ?: extractors[config.routing.textModel]
            ?: error("没有可用的 AI 提炼器（kind=$kind），请到配置中心检查模型配置")
        return kind to extractor
    }

    /** 按候选顺序尝试，返回第一个成功的提炼结果 */
    suspend fun extractDetailed(input: ExtractInput, config: AppConfig): AiExtraction {
        val tries = ArrayList<String>()
        // 记录「第一个配置完备的候选」，用于判断本次是否发生了降级
        var firstUsable: ModelKind? = null
        for (kind in candidates(input, config)) {
            // 注意：不能用 `?: run { ...; continue }`（Kotlin 里 continue 在内联 lambda 中
            // 属实验特性，需要额外 opt-in），所以这里拆成显式判断。
            val model = config.models[kind]
            if (model == null) {
                tries.add("$kind: 缺少配置")
                continue
            }
            if (!model.enabled) {
                tries.add("$kind: 已停用")
                continue
            }
            if (model.apiKey.isBlank()) {
                tries.add("$kind: 未填 API Key")
                continue
            }
            if (input.type == InputType.IMAGE && !model.supportsVision) {
                tries.add("$kind: 未标记支持图片")
                continue
            }
            val extractor = extractors[kind]
            if (extractor == null) {
                tries.add("$kind: 无实现")
                continue
            }
            val primary = firstUsable ?: kind.also { firstUsable = it }
            try {
                val r = extractor.extract(input, model)
                val fixed = applyConfidence(r, config)
                return AiExtraction(
                    result = fixed,
                    kind = kind,
                    modelName = model.modelName,
                    fallbackFrom = if (kind != primary) primary else null
                )
            } catch (e: Exception) {
                tries.add("$kind: ${e.message?.take(120)}")
            }
        }
        error("所有候选模型都不可用 → ${tries.joinToString("；")}。请到 设置 → 配置中心 → AI 模型 检查 API Key / 模型名 / 是否支持图片。")
    }

    /** 兼容旧调用点 */
    suspend fun extract(input: ExtractInput, config: AppConfig): ExtractResult =
        extractDetailed(input, config).result

    /** 按阈值标注「请确认」（F2.6），并截断到 maxDraftsPerInput */
    private fun applyConfidence(r: ExtractResult, config: AppConfig): ExtractResult {
        val threshold = config.routing.imageConfidenceThreshold
        val max = config.maxDraftsPerInput.coerceAtLeast(1)
        return r.copy(
            drafts = r.drafts.take(max).map { it.copy(needsConfirm = it.confidence < threshold) }
        )
    }
}
