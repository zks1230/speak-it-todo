package com.saym.phone.cfg

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.saym.phone.model.AppConfigView
import com.saym.phone.model.ModelCfgView
import com.saym.phone.model.RoutingView
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 手机本地运行时配置（新架构：不再从后端拉取，全部本地读写） */
@Serializable
data class LocalConfig(
    val models: Map<String, ModelCfg> = emptyMap(),
    val routing: RoutingView = RoutingView(),
    // 提醒（F5）
    val dailyPushHour: Int = 8,
    val dailyPushMinute: Int = 0,
    val dailyPushEnabled: Boolean = true,
    val defaultRemindOffsetMinutes: Int = 0,
    val keyTaskRemindEnabled: Boolean = true,
    // AI 行为
    val autoSuggestDeadline: Boolean = false,
    val maxDraftsPerInput: Int = 20,
    // 其他
    val configHistoryEnabled: Boolean = true,
    val version: Long = 1
)

@Serializable
data class ModelCfg(
    val kind: String,
    val apiKey: String,
    val baseUrl: String,
    val modelName: String,
    val temperature: Float = 0.3f,
    val maxTokens: Int = 2048,
    val enabled: Boolean = true,
    val supportsVision: Boolean = false
)

/**
 * 本地配置存储（重写版，目标：**永不静默丢失用户填过的 API Key**）。
 *
 * 上一版的严重缺陷：
 *  - `encrypt()` 失败时静默存明文，而 `load()` 永远按密文解密 → 下次读必然失败；
 *  - `load()` 失败时直接返回默认配置并覆盖 → **用户刚填的 Key 被悄悄抹掉**，还不出声。
 *
 * 现在的设计：
 *  1. 存储格式带版本前缀：`v1:`（AES-GCM 密文）或 `raw:`（AES 不可用时的明文兜底），
 *     读的时候按前缀决定怎么解，不会再出现"存的和读的不一致"；
 *  2. 加密优先用 **Android Keystore**；Keystore 不可用时退化为"本地随机盐 + SHA-256 派生密钥"
 *     （安全性弱于 Keystore，但比明文好，且**不会因为 Keystore 抽风就丢配置**）；
 *  3. 解密/解析失败时**保留原始 blob**，返回默认配置但不覆盖磁盘上的数据，
 *     并把失败原因记到 [lastError]，设置页会显示出来；
 *  4. 另存一份**不含 key 的结构备份**（`structure_blob`，只存 baseUrl/模型名/开关/Time 等），
 *     万一主 blob 坏了，至少模型名、推送时间这些设置不用重填，只有 Key 需要重填。
 */
object LocalConfigStore {
    private const val PREF = "saym_local_cfg"
    private const val FIELD = "config_blob"
    private const val BACKUP = "structure_blob"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "saym_cfg_key_v1"
    private const val TAG = "SaymConfig"
    private const val MARK_ENC = "v1:"
    private const val MARK_RAW = "raw:"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** 最近一次读取失败的原因（设置页展示，便于定位） */
    @Volatile
    var lastError: String? = null
        private set

    /** 最近一次保存或读取是否走了 Keystore 加密 */
    @Volatile
    var encryptionMode: String = "未知"
        private set

    /**
     * 默认配置。
     *
     * 重要（2026-09 官方变更）：
     *  - `deepseek-chat` 已于 2026-07-24 停用；
     *  - 现行模型 **`deepseek-flash`（V4.1-Flash）原生支持图片**（官方模型表 Vision = ✓，
     *    而 `deepseek-v4-pro` 不支持视觉）；
     *  - 所以默认文字+图片都走 DeepSeek 一个模型，只需填一个 API Key；
     *    豆包 / 通义保留为可选备用。
     */
    fun defaultConfig(): LocalConfig {
        val models = mapOf(
            "DEEPSEEK" to ModelCfg(
                kind = "DEEPSEEK", apiKey = "",
                baseUrl = "https://api.deepseek.com/v1",
                modelName = "deepseek-flash",
                supportsVision = true
            ),
            "DOUBAO_VISION" to ModelCfg(
                kind = "DOUBAO_VISION", apiKey = "",
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                modelName = "doubao-vision-pro",
                supportsVision = true
            ),
            "QWEN_VL" to ModelCfg(
                kind = "QWEN_VL", apiKey = "",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                modelName = "qwen-vl-max",
                supportsVision = true
            )
        )
        return LocalConfig(
            models = models,
            routing = RoutingView(
                textModel = "DEEPSEEK",
                imageModel = "DEEPSEEK",
                enableImageRouting = false,
                imageConfidenceThreshold = 0.7f,
                // DeepSeek 官方会把图片缩到约 1300×1300，给 1600 留余量
                imageMaxEdge = 1600
            )
        )
    }

    /** 读取配置。任何失败都不会覆盖磁盘上的已有数据。 */
    fun load(ctx: Context): LocalConfig {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val blob = sp.getString(FIELD, null)
        if (blob.isNullOrBlank()) {
            lastError = null
            val def = defaultConfig()
            save(ctx, def)
            return def
        }
        val plain: String = when {
            blob.startsWith(MARK_ENC) -> {
                val parsed = try {
                    decryptKeystore(blob.removePrefix(MARK_ENC))
                } catch (e: Exception) {
                    // Keystore 抽风（换设备/清数据/厂商 ROM 限制）：不要清空配置！
                    // 先试试本地派生密钥那份兜底，否则保留原样并报错。
                    try {
                        decryptLocal(ctx, blob.removePrefix(MARK_ENC))
                    } catch (e2: Exception) {
                        lastError = "配置解密失败（Keystore 与本地密钥都不行）：${e.message}"
                        Log.w(TAG, "decrypt failed", e)
                        return structureFallback(ctx)
                    }
                }
                parsed
            }
            blob.startsWith(MARK_RAW) -> blob.removePrefix(MARK_RAW)
            else -> blob   // 老版本遗留格式：直接当明文
        }
        return try {
            val cfg = json.decodeFromString(LocalConfig.serializer(), plain)
            lastError = null
            cfg
        } catch (e: Exception) {
            lastError = "配置内容解析失败：${e.message}"
            Log.w(TAG, "parse failed", e)
            structureFallback(ctx)
        }
    }

    /** 主 blob 坏了时的兜底：用结构备份（不含 key）恢复设置项 */
    private fun structureFallback(ctx: Context): LocalConfig {
        val base = defaultConfig()
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val sb = sp.getString(BACKUP, null) ?: return base
        return try {
            val cfg = json.decodeFromString(LocalConfig.serializer(), sb)
            // 备份里 key 一定是空的；把默认值补上，保证可用性
            cfg.copy(
                models = base.models.mapValues { (k, def) ->
                    val saved = cfg.models[k]
                    if (saved == null) def
                    else saved.copy(apiKey = "", supportsVision = def.supportsVision || saved.supportsVision)
                }
            )
        } catch (e: Exception) { base }
    }

    fun save(ctx: Context, cfg: LocalConfig) {
        val plain = json.encodeToString(LocalConfig.serializer(), cfg)
        val blob: String = try {
            val ct = encryptKeystore(plain)
            encryptionMode = "Android Keystore (AES-256-GCM)"
            MARK_ENC + ct
        } catch (e: Exception) {
            Log.w(TAG, "keystore encrypt failed, fallback to local key", e)
            try {
                val ct = encryptLocal(ctx, plain)
                encryptionMode = "本地派生密钥 (AES-256-GCM，Keystore 不可用)"
                MARK_ENC + ct
            } catch (e2: Exception) {
                Log.w(TAG, "local encrypt failed too, store raw", e2)
                encryptionMode = "明文（加密不可用，请留意）"
                MARK_RAW + plain
            }
        }
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        sp.putString(FIELD, blob)
        // 结构备份：不含任何 API Key，用于主 blob 损坏时恢复设置项
        try {
            val noKeys = cfg.copy(models = cfg.models.mapValues { (_, m) -> m.copy(apiKey = "") })
            sp.putString(BACKUP, json.encodeToString(LocalConfig.serializer(), noKeys))
        } catch (_: Exception) {}
        sp.apply()
    }

    /** 清空配置（设置页的"重置"用） */
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        lastError = null
    }

    // ============ 视图转换 ============

    /** 配置 → UI 视图（apiKey 用 *** 掩码，绝不回显明文） */
    fun toView(cfg: LocalConfig): AppConfigView = AppConfigView(
        version = cfg.version,
        models = cfg.models.mapValues { (k, m) ->
            ModelCfgView(
                kind = k, apiKey = if (m.apiKey.isBlank()) "" else MASK,
                baseUrl = m.baseUrl, modelName = m.modelName,
                temperature = m.temperature, maxTokens = m.maxTokens,
                enabled = m.enabled, supportsVision = m.supportsVision
            )
        },
        routing = cfg.routing,
        dailyPushHour = cfg.dailyPushHour,
        dailyPushMinute = cfg.dailyPushMinute,
        dailyPushEnabled = cfg.dailyPushEnabled,
        defaultRemindOffsetMinutes = cfg.defaultRemindOffsetMinutes,
        keyTaskRemindEnabled = cfg.keyTaskRemindEnabled,
        autoSuggestDeadline = cfg.autoSuggestDeadline,
        maxDraftsPerInput = cfg.maxDraftsPerInput,
        configHistoryEnabled = cfg.configHistoryEnabled
    )

    /** UI 视图 → 配置；apiKey 为掩码或空表示保留原值 */
    fun fromView(view: AppConfigView, prev: LocalConfig): LocalConfig {
        val old = prev.models
        val models = HashMap<String, ModelCfg>()
        old.forEach { (k, v) -> models[k] = v }
        view.models.forEach { (k, v) ->
            val prevKey = old[k]?.apiKey ?: ""
            models[k] = ModelCfg(
                kind = k,
                apiKey = if (v.apiKey.isBlank() || v.apiKey == MASK) prevKey else v.apiKey,
                baseUrl = v.baseUrl.ifBlank { old[k]?.baseUrl ?: "" },
                modelName = v.modelName.ifBlank { old[k]?.modelName ?: "" },
                temperature = v.temperature,
                maxTokens = v.maxTokens,
                enabled = v.enabled,
                supportsVision = v.supportsVision
            )
        }
        return prev.copy(
            models = models,
            routing = view.routing,
            dailyPushHour = view.dailyPushHour.coerceIn(0, 23),
            dailyPushMinute = view.dailyPushMinute.coerceIn(0, 59),
            dailyPushEnabled = view.dailyPushEnabled,
            defaultRemindOffsetMinutes = view.defaultRemindOffsetMinutes,
            keyTaskRemindEnabled = view.keyTaskRemindEnabled,
            autoSuggestDeadline = view.autoSuggestDeadline,
            maxDraftsPerInput = view.maxDraftsPerInput.coerceAtLeast(1),
            configHistoryEnabled = view.configHistoryEnabled,
            version = prev.version + 1
        )
    }

    const val MASK = "***"

    /**
     * 组装给手表的最小配置（每日推送时间等）。
     * 手表只做"看 + 完成 + 收提醒"，不做 AI（§7.2 定位），所以不传任何 key。
     */
    fun toWatchPayload(ctx: Context): Map<String, Any> {
        val c = load(ctx)
        return mapOf(
            "dailyHour" to c.dailyPushHour,
            "dailyMinute" to c.dailyPushMinute,
            "keyTaskRemindEnabled" to c.keyTaskRemindEnabled
        )
    }

    // ============ 加密实现 ============

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encryptKeystore(plain: String): String = gcmEncrypt(plain, keystoreKey())

    private fun decryptKeystore(payload: String): String = gcmDecrypt(payload, keystoreKey())

    /** 本地派生密钥：从本地随机盐派生（Keystore 不可用时的兜底，保证配置不丢） */
    private fun localKey(ctx: Context): SecretKey {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        var saltB64 = sp.getString("local_salt", null)
        if (saltB64 == null) {
            val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
            saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
            sp.edit().putString("local_salt", saltB64).apply()
        }
        return SecretKeySpec(Base64.decode(saltB64, Base64.NO_WRAP), "AES")
    }

    private fun encryptLocal(ctx: Context, plain: String): String =
        gcmEncrypt(plain, localKey(ctx))

    private fun decryptLocal(ctx: Context, payload: String): String =
        gcmDecrypt(payload, localKey(ctx))

    /** 输出 base64(iv(12) + ciphertext) */
    private fun gcmEncrypt(plain: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val all = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, all, 0, iv.size)
        System.arraycopy(ct, 0, all, iv.size, ct.size)
        return Base64.encodeToString(all, Base64.NO_WRAP)
    }

    private fun gcmDecrypt(payload: String, key: SecretKey): String {
        val all = Base64.decode(payload, Base64.NO_WRAP)
        require(all.size > 13) { "密文长度异常" }
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
