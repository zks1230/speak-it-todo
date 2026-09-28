package com.saym.phone

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.saym.phone.ai.AiClient
import com.saym.phone.cfg.LocalConfigStore
import com.saym.phone.model.AppConfigView
import com.saym.phone.model.ModelCfgView
import com.saym.phone.model.RoutingView
import com.saym.phone.remind.DailyPushScheduler
import com.saym.phone.ui.UiKit
import com.saym.phone.watch.WatchPusher
import com.saym.phone.watch.WatchServer
import com.saym.phone.watch.WatchSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 / 配置中心（F6，全部本地读写，不连后端）。
 *
 *  - 「解锁编辑」开关替代原来的主密码（自用场景只需防误改）
 *  - API Key 永不回显明文，只显示「已配置 / 未配置」
 *  - 「测试连接」直接拿填的 key 调一次模型，立刻知道能不能用
 *  - 顶部显示**配置存储的真实状态**（加密方式 / 读取异常），出问题不用猜
 *  - 手表配对：生成令牌 + 经蓝牙下发（F4.2）
 */
class ConfigActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var unlockSwitch: com.google.android.material.switchmaterial.SwitchMaterial

    private var view: AppConfigView = AppConfigView()
    private val modelEdits = mutableMapOf<String, ModelEditors>()

    private lateinit var textModelEdit: EditText
    private lateinit var imageModelEdit: EditText
    private lateinit var routingChk: CheckBox
    private lateinit var confThresholdEdit: EditText
    private lateinit var imageEdgeEdit: EditText

    private lateinit var pushEnabledChk: CheckBox
    private lateinit var pushHourEdit: EditText
    private lateinit var pushMinuteEdit: EditText
    private lateinit var offsetEdit: EditText
    private lateinit var keyRemindChk: CheckBox

    private lateinit var autoDeadlineChk: CheckBox
    private lateinit var maxDraftsEdit: EditText

    private var tab = 0

    private class ModelEditors(
        val key: EditText, val url: EditText, val name: EditText,
        val temp: EditText, val maxTokens: EditText,
        val enabled: CheckBox, val vision: CheckBox
    )

    private val tabs = listOf("模型", "分流", "提醒", "手表", "参数")
    private val modelKinds = listOf(
        "DEEPSEEK" to "DeepSeek · 文字+图片都用它",
        "DOUBAO_VISION" to "豆包视觉版 · 可选备用",
        "QWEN_VL" to "通义 Qwen-VL-Max · 可选备用"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.c(this@ConfigActivity, R.color.saym_bg)) }
        val root = UiKit.vertical(this).apply {
            setPadding(UiKit.dp(this@ConfigActivity, 16), UiKit.dp(this@ConfigActivity, 16),
                UiKit.dp(this@ConfigActivity, 16), UiKit.dp(this@ConfigActivity, 24))
        }
        scroll.addView(root); setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "设置"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(UiKit.c(this@ConfigActivity, R.color.saym_text))
        })

        // ---- 配置存储状态（诊断用，省得靠猜）----
        root.addView(UiKit.spacer(this, 12))
        val statusCardView = UiKit.card(this)
        statusCard = UiKit.vertical(this)
        statusCardView.addView(statusCard)
        root.addView(statusCardView)

        // ---- 解锁开关 ----
        root.addView(UiKit.spacer(this, 4))
        val lockCard = UiKit.card(this)
        val lockInner = UiKit.vertical(this)
        unlockSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            text = "解锁编辑（防止误改）"
            textSize = 15f
            isChecked = Prefs.configUnlocked(this@ConfigActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setConfigUnlocked(this@ConfigActivity, checked)
                renderTab()
            }
        }
        lockInner.addView(unlockSwitch)
        lockInner.addView(UiKit.muted(this, "锁着的时候只能查看。API Key 只显示「已配置 / 未配置」，不回显明文。"))
        lockCard.addView(lockInner)
        root.addView(lockCard)

        // ---- Tab ----
        root.addView(UiKit.spacer(this, 4))
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.forEachIndexed { i, n ->
            tabRow.addView(Button(this).apply {
                text = n
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { tab = i; renderTab() }
            })
        }
        root.addView(tabRow)

        content = UiKit.vertical(this)
        root.addView(content)

        // ---- 底部操作 ----
        root.addView(UiKit.spacer(this, 12))
        root.addView(UiKit.primaryButton(this, "保存设置") { save() })
        root.addView(UiKit.spacer(this, 8))
        root.addView(UiKit.outlinedButton(this, "重置本地配置（会清掉已填的 API Key）") { confirmReset() })

        reload()
    }

    private fun reload() {
        view = LocalConfigStore.toView(LocalConfigStore.load(this))
        renderStatus()
        renderTab()
    }

    private fun isUnlocked() = Prefs.configUnlocked(this)

    private fun renderStatus() {
        statusCard.removeAllViews()
        statusCard.addView(UiKit.sectionLabel(this, "配置存储状态").apply { setPadding(0, 0, 0, 6) })
        val cfg = LocalConfigStore.load(this)
        val deepseekKey = cfg.models["DEEPSEEK"]?.apiKey.orEmpty()
        val modelName = cfg.models["DEEPSEEK"]?.modelName ?: "-"
        val rows = listOf(
            "存储方式" to LocalConfigStore.encryptionMode,
            "DeepSeek Key" to if (deepseekKey.isBlank()) "⚠ 未配置（必须填才能用 AI）" else "已配置（${deepseekKey.length} 位）",
            "当前模型名" to modelName,
            "图片支持" to if (cfg.models["DEEPSEEK"]?.supportsVision == true) "已开启" else "未开启（图片会失败）",
            "局域网服务" to (if (WatchServer.running) "运行中" else "未运行") +
                "　地址 ${WatchServer.localIpv4() ?: "未连 WiFi"}:" + WatchServer.PORT_PUBLIC
        )
        rows.forEach { (k, v) ->
            statusCard.addView(TextView(this).apply {
                text = "$k：$v"
                textSize = 13f
                setTextColor(UiKit.c(this@ConfigActivity, R.color.saym_text))
                setPadding(0, UiKit.dp(this@ConfigActivity, 2), 0, UiKit.dp(this@ConfigActivity, 2))
            })
        }
        LocalConfigStore.lastError?.let { err ->
            statusCard.addView(TextView(this).apply {
                text = "⚠ 上次读取异常：$err"
                textSize = 12f
                setTextColor(UiKit.c(this@ConfigActivity, R.color.saym_danger))
            })
        }
        val keyWarn = deepseekKey.isBlank()
        if (keyWarn) {
            statusCard.addView(UiKit.muted(this, "还没填 Key：去下面「模型」页填 DeepSeek API Key，然后点「测试连接」验证。"))
        }
        unlockSwitch.isChecked = isUnlocked()
    }

    // ============ Tab ============

    private fun renderTab() {
        content.removeAllViews()
        modelEdits.clear()
        when (tab) {
            0 -> renderModels()
            1 -> renderRouting()
            2 -> renderRemind()
            3 -> renderWatch()
            else -> renderParams()
        }
    }

    private fun fieldCard(label: String, edit: EditText, hint: String? = null) {
        val card = UiKit.card(this, paddingDp = 12)
        val inner = UiKit.vertical(this)
        inner.addView(TextView(this).apply {
            text = label; textSize = 12f
            setTextColor(UiKit.c(this@ConfigActivity, R.color.saym_text_muted))
        })
        inner.addView(edit)
        hint?.let { inner.addView(UiKit.muted(this, it)) }
        card.addView(inner)
        content.addView(card)
    }

    private fun newEdit(hintText: String, value: String = "", password: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintText
            setText(value)
            textSize = 15f
            isEnabled = isUnlocked()
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

    /** F6.2 AI 模型 */
    private fun renderModels() {
        val preset = UiKit.card(this)
        val presetInner = UiKit.vertical(this)
        presetInner.addView(UiKit.sectionLabel(this, "快速开始").apply { setPadding(0, 0, 0, 6) })
        presetInner.addView(UiKit.muted(this,
            "DeepSeek 现在用 deepseek-flash（V4.1-Flash）——它同时支持文字和图片，" +
                "所以只要填一个 API Key 就够了。旧的 deepseek-chat 已停用。"))
        presetInner.addView(UiKit.spacer(this, 8))
        presetInner.addView(UiKit.primaryButton(this, "① 一键设置成 deepseek-flash") {
            if (!isUnlocked()) { UiKit.toast(this, "请先打开上面的「解锁编辑」"); return@primaryButton }
            val prev = LocalConfigStore.load(this)
            val d = prev.models["DEEPSEEK"] ?: run { UiKit.toast(this, "默认配置缺少 DeepSeek 条目"); return@primaryButton }
            val fixed = prev.copy(
                models = prev.models + ("DEEPSEEK" to d.copy(
                    baseUrl = "https://api.deepseek.com/v1",
                    modelName = "deepseek-flash",
                    enabled = true,
                    supportsVision = true
                )),
                routing = prev.routing.copy(textModel = "DEEPSEEK", imageModel = "DEEPSEEK", enableImageRouting = false)
            )
            LocalConfigStore.save(this, fixed)
            UiKit.toast(this, "已设置好模型名与图片支持，接着填 API Key 就行")
            reload()
        })
        preset.addView(presetInner)
        content.addView(preset)

        modelKinds.forEach { (kind, label) ->
            content.addView(UiKit.sectionLabel(this, label))
            val mc = view.models[kind]
            val card = UiKit.card(this, paddingDp = 12)
            val inner = UiKit.vertical(this)

            val eKey = newEdit(if (mc?.apiKey?.isNotBlank() == true) "已配置（留空保持不变）" else "API Key（粘贴在这里）", password = true)
            val eUrl = newEdit("Base URL", mc?.baseUrl.orEmpty())
            val eName = newEdit("模型名", mc?.modelName.orEmpty())
            val eTemp = newEdit("温度 0~2", (mc?.temperature ?: 0.3f).toString())
            val eMax = newEdit("max_tokens", (mc?.maxTokens ?: 2048).toString())
            val chkEnabled = CheckBox(this).apply { text = "启用该模型"; isChecked = mc?.enabled ?: true; isEnabled = isUnlocked() }
            val chkVision = CheckBox(this).apply {
                text = "该模型支持图片输入（视觉）"
                isChecked = mc?.supportsVision ?: (kind != "DEEPSEEK")
                isEnabled = isUnlocked()
            }

            inner.addView(eKey); inner.addView(eUrl); inner.addView(eName)
            inner.addView(eTemp); inner.addView(eMax)
            inner.addView(chkEnabled); inner.addView(chkVision)
            inner.addView(UiKit.spacer(this, 6))
            inner.addView(UiKit.outlinedButton(this, "② 测试连接（验证 Key 能不能用）") {
                testConnection(kind, eKey, eUrl, eName)
            })
            card.addView(inner)
            content.addView(card)
            modelEdits[kind] = ModelEditors(eKey, eUrl, eName, eTemp, eMax, chkEnabled, chkVision)
        }
    }

    /** F6.3 分流规则 */
    private fun renderRouting() {
        content.addView(UiKit.sectionLabel(this, "图片走哪个模型"))
        val card = UiKit.card(this, paddingDp = 12)
        val inner = UiKit.vertical(this)
        inner.addView(UiKit.muted(this,
            "默认关闭分流：文字和图片都走 DeepSeek 一个模型。\n" +
                "只有在 DeepSeek 识图效果不理想时，才打开分流并把图片交给豆包 / 通义。\n" +
                "首选模型没填 key / 停用 / 不支持图片时，会自动换下一个候选模型，不会直接失败。"))
        textModelEdit = newEdit("文本模型（DEEPSEEK）", view.routing.textModel)
        imageModelEdit = newEdit("图片模型（DEEPSEEK / DOUBAO_VISION / QWEN_VL）", view.routing.imageModel)
        routingChk = CheckBox(this).apply {
            text = "启用图片分流"; isChecked = view.routing.enableImageRouting; isEnabled = isUnlocked()
        }
        confThresholdEdit = newEdit("置信度阈值 0~1", view.routing.imageConfidenceThreshold.toString())
        imageEdgeEdit = newEdit("图片压缩最长边（像素）", view.routing.imageMaxEdge.toString())
        inner.addView(textModelEdit); inner.addView(imageModelEdit)
        inner.addView(routingChk); inner.addView(confThresholdEdit); inner.addView(imageEdgeEdit)
        inner.addView(UiKit.muted(this, "低于阈值的草稿会在预览里标「⚠ 请确认」。DeepSeek 官方会把图片缩到约 1300×1300，所以 1600 就够。"))
        card.addView(inner)
        content.addView(card)
    }

    /** F5.1 / F5.3 / F5.5 提醒 */
    private fun renderRemind() {
        content.addView(UiKit.sectionLabel(this, "提醒"))
        val card = UiKit.card(this, paddingDp = 12)
        val inner = UiKit.vertical(this)
        pushEnabledChk = CheckBox(this).apply { text = "启用每日清单推送"; isChecked = view.dailyPushEnabled; isEnabled = isUnlocked() }
        pushHourEdit = newEdit("推送小时 0-23", view.dailyPushHour.toString())
        pushMinuteEdit = newEdit("推送分钟 0-59", view.dailyPushMinute.toString())
        offsetEdit = newEdit("默认提前量（分钟）", view.defaultRemindOffsetMinutes.toString())
        keyRemindChk = CheckBox(this).apply { text = "启用关键任务定点提醒"; isChecked = view.keyTaskRemindEnabled; isEnabled = isUnlocked() }
        inner.addView(pushEnabledChk); inner.addView(pushHourEdit); inner.addView(pushMinuteEdit)
        inner.addView(offsetEdit); inner.addView(keyRemindChk)
        inner.addView(UiKit.muted(this, "保存后本机闹钟立即按新时间重排。推送内容来自本地库，断网也照常提醒。"))
        card.addView(inner)
        content.addView(card)
    }

    /** F4.2 手表配对 */
    private fun renderWatch() {
        val ip = WatchServer.localIpv4()
        val mac = Prefs.watchMac(this)
        val token = Prefs.watchToken(this)
        val last = Prefs.watchLastSyncAt(this)

        content.addView(UiKit.sectionLabel(this, "手表同步（国庆后开发手表端 App）"))
        val card = UiKit.card(this, paddingDp = 12)
        val inner = UiKit.vertical(this)
        inner.addView(UiKit.muted(this,
            "通道设计：家里同一个 WiFi 时**由手表主动来手机拉数据**（主通道）；蓝牙推送是备用。\n" +
                "手机这一侧已经全部做好了，手表端 App 待开发。"))
        inner.addView(UiKit.spacer(this, 8))
        inner.addView(UiKit.body(this, "本机局域网地址：${ip ?: "（未连 WiFi）"}"))
        inner.addView(UiKit.body(this, "监听端口：${WatchServer.PORT_PUBLIC}"))
        inner.addView(UiKit.body(this, "服务状态：${if (WatchServer.running) "运行中" else "未运行"}"))
        inner.addView(UiKit.body(this, "已绑定手表 MAC：${mac ?: "（未绑定）"}"))
        inner.addView(UiKit.body(this, "配对令牌：${if (token.isNullOrBlank()) "（未生成）" else "已生成（${token.length} 位）"}"))
        inner.addView(UiKit.body(this, "手表上次同步：${last?.take(19) ?: "（还没有同步过）"}"))
        inner.addView(UiKit.spacer(this, 10))
        inner.addView(UiKit.outlinedButton(this, "重启局域网服务") {
            WatchSyncService.stop(this); WatchSyncService.start(this)
            UiKit.toast(this, "已重启")
            renderTab(); renderStatus()
        })
        inner.addView(UiKit.spacer(this, 6))
        inner.addView(UiKit.outlinedButton(this, "① 生成配对令牌") {
            val t = Prefs.ensureWatchToken(this)
            UiKit.toast(this, "令牌已生成（${t.length} 位）")
            renderTab(); renderStatus()
        })
        inner.addView(UiKit.spacer(this, 6))
        inner.addView(UiKit.outlinedButton(this, "② 选择已配对的 DW99（蓝牙）") { scanWatches() })
        inner.addView(UiKit.spacer(this, 6))
        inner.addView(UiKit.outlinedButton(this, "③ 把配对信息经蓝牙发给手表") {
            if (Prefs.watchMac(this).isNullOrBlank()) { UiKit.toast(this, "请先绑定手表 MAC"); return@outlinedButton }
            UiKit.toast(this, "正在通过蓝牙发送…")
            lifecycleScope.launch {
                val (ok, msg) = WatchPusher.pushPairing(this@ConfigActivity)
                UiKit.toast(this@ConfigActivity, if (ok) "配对成功：$msg" else "配对失败：$msg")
                renderTab()
            }
        })
        inner.addView(UiKit.spacer(this, 6))
        inner.addView(UiKit.outlinedButton(this, "重新配对（作废旧令牌）") {
            Prefs.resetWatchToken(this)
            UiKit.toast(this, "已作废旧令牌，手表需要重新配对")
            renderTab(); renderStatus()
        })
        card.addView(inner)
        content.addView(card)
    }

    /** F6.5 / F2.4 参数 */
    private fun renderParams() {
        content.addView(UiKit.sectionLabel(this, "AI 行为"))
        val card = UiKit.card(this, paddingDp = 12)
        val inner = UiKit.vertical(this)
        autoDeadlineChk = CheckBox(this).apply {
            text = "没有截止时间时由 AI 建议时间（默认关闭）"
            isChecked = view.autoSuggestDeadline; isEnabled = isUnlocked()
        }
        maxDraftsEdit = newEdit("单次提炼最多条数", view.maxDraftsPerInput.toString())
        inner.addView(autoDeadlineChk); inner.addView(maxDraftsEdit)
        inner.addView(UiKit.muted(this, "温度 / max_tokens 在「模型」页里逐个模型设置。"))
        card.addView(inner)
        content.addView(card)
    }

    // ============ 保存 / 测试 / 重置 ============

    private fun save() {
        if (!isUnlocked()) { UiKit.toast(this, "请先打开「解锁编辑」"); return }
        val prev = LocalConfigStore.load(this)
        val models = view.models.toMutableMap()
        modelEdits.forEach { (kind, e) ->
            val old = prev.models[kind]
            models[kind] = ModelCfgView(
                kind = kind,
                apiKey = e.key.text.toString().ifBlank { old?.apiKey ?: "" },
                baseUrl = e.url.text.toString().ifBlank { old?.baseUrl ?: "" },
                modelName = e.name.text.toString().ifBlank { old?.modelName ?: "" },
                temperature = e.temp.text.toString().toFloatOrNull() ?: old?.temperature ?: 0.3f,
                maxTokens = e.maxTokens.text.toString().toIntOrNull() ?: old?.maxTokens ?: 2048,
                enabled = e.enabled.isChecked,
                supportsVision = e.vision.isChecked
            )
        }
        val routing = RoutingView(
            textModel = textModelEdit.text.toString().ifBlank { "DEEPSEEK" },
            imageModel = imageModelEdit.text.toString().ifBlank { "DEEPSEEK" },
            enableImageRouting = routingChk.isChecked,
            imageConfidenceThreshold = confThresholdEdit.text.toString().toFloatOrNull() ?: 0.7f,
            imageMaxEdge = imageEdgeEdit.text.toString().toIntOrNull() ?: 1600
        )
        val updated = view.copy(
            models = models,
            routing = routing,
            dailyPushHour = pushHourEdit.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: view.dailyPushHour,
            dailyPushMinute = pushMinuteEdit.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: view.dailyPushMinute,
            dailyPushEnabled = pushEnabledChk.isChecked,
            defaultRemindOffsetMinutes = offsetEdit.text.toString().toIntOrNull() ?: view.defaultRemindOffsetMinutes,
            keyTaskRemindEnabled = keyRemindChk.isChecked,
            autoSuggestDeadline = autoDeadlineChk.isChecked,
            maxDraftsPerInput = maxDraftsEdit.text.toString().toIntOrNull() ?: view.maxDraftsPerInput
        )
        val saved = LocalConfigStore.fromView(updated, prev)
        LocalConfigStore.save(this, saved)
        view = LocalConfigStore.toView(saved)
        if (saved.dailyPushEnabled) DailyPushScheduler.scheduleAt(this, saved.dailyPushHour, saved.dailyPushMinute)
        else DailyPushScheduler.cancel(this)
        UiKit.toast(this, "已保存")
        reload()
    }

    /** 直接拿填的 key 调一次模型，立刻知道能不能用 */
    private fun testConnection(kind: String, eKey: EditText, eUrl: EditText, eName: EditText) {
        val keyIn = eKey.text.toString().ifBlank { null }
        if (keyIn == null && view.models[kind]?.apiKey.isNullOrBlank()) {
            AlertDialog.Builder(this).setTitle("还没填 API Key")
                .setMessage("请把 DeepSeek 平台复制来的 API Key 粘贴到上面的输入框，再点测试连接。")
                .setPositiveButton("知道了", null).show()
            return
        }
        // 把当前输入临时存起来（测试要用），不改变其它字段
        val prev = LocalConfigStore.load(this)
        val old = prev.models[kind]
        val tmp = prev.copy(models = prev.models + (kind to com.saym.phone.cfg.ModelCfg(
            kind = kind,
            apiKey = keyIn ?: old?.apiKey ?: "",
            baseUrl = eUrl.text.toString().ifBlank { old?.baseUrl ?: "" },
            modelName = eName.text.toString().ifBlank { old?.modelName ?: "" },
            temperature = old?.temperature ?: 0.3f,
            maxTokens = old?.maxTokens ?: 2048,
            enabled = true,
            supportsVision = old?.supportsVision ?: (kind != "DEEPSEEK")
        )))
        LocalConfigStore.save(this, tmp)
        UiKit.toast(this, "正在测试，可能要几秒…")
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { AiClient.testConnection(this@ConfigActivity, kind) }
                AlertDialog.Builder(this@ConfigActivity).setTitle("✅ 测试成功").setMessage(r)
                    .setPositiveButton("好", null).show()
            } catch (e: Exception) {
                AlertDialog.Builder(this@ConfigActivity).setTitle("❌ 测试失败")
                    .setMessage(e.message ?: "未知错误").setPositiveButton("好", null).show()
            }
            reload()
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("确认重置本地配置？")
            .setMessage("会清空所有本地设置（包括已填的 API Key 和所有模型配置），待办数据不受影响。")
            .setPositiveButton("确认重置") { _, _ ->
                LocalConfigStore.clear(this)
                UiKit.toast(this, "已重置")
                reload()
            }
            .setNegativeButton("取消", null).show()
    }

    // ============ 蓝牙绑定 ============

    private fun scanWatches() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT), 2
            )
        }
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = mgr?.adapter
        if (adapter == null || !adapter.isEnabled) { UiKit.toast(this, "蓝牙未开启"); return }
        try {
            val bonded = adapter.bondedDevices?.toList().orEmpty()
            if (bonded.isEmpty()) { UiKit.toast(this, "没有已配对的蓝牙设备，请先在系统蓝牙里配对 DW99"); return }
            val labels = bonded.map { "${it.name ?: "(未知)"}　${it.address}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("选择 DW99")
                .setItems(labels) { _, which ->
                    val d = bonded[which]
                    Prefs.setWatchMac(this, d.address)
                    UiKit.toast(this, "已绑定 ${d.name ?: d.address}")
                    renderTab()
                }.setNegativeButton("取消", null).show()
        } catch (e: SecurityException) {
            UiKit.toast(this, "缺少蓝牙权限：${e.message}")
        }
    }
}
