package com.saym.phone

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.saym.phone.ai.AiClient
import com.saym.phone.cfg.LocalConfigStore
import com.saym.phone.data.TodoRepo
import com.saym.phone.model.InputType
import com.saym.phone.model.TodoCreateReq
import com.saym.phone.model.TodoDraft
import com.saym.phone.remind.KeyTaskReminderReceiver
import com.saym.phone.ui.UiKit
import com.saym.phone.watch.WatchPusher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 输入页（F1 多模态输入 / F2.5 预览可改）。
 *
 * 新架构：**AI 由手机自己直连大模型**（[AiClient]），不经过任何后端；
 * API Key 从本地加密配置里读，所以这里不发任何凭证到第三方以外的地方。
 *
 *  - F1.1 语音：用输入法自带麦克风，统走文字通道（决策 D6）
 *  - F1.2 文字：单条 + 批量粘贴
 *  - F1.3 图片：拍照 / 相册多选，上传前压缩到最长边（性能：单条 ≤5 秒）
 *  - F1.4 输入暂存：提炼前先落本地暂存，30 天内可重提
 *  - F2.6 模糊度标注：confidence 低于阈值标「⚠ 请确认」
 */
class InputActivity : AppCompatActivity() {
    private lateinit var editText: EditText
    private lateinit var draftContainer: LinearLayout
    private lateinit var sourceLabel: TextView
    private val draftState = mutableListOf<TodoDraft>()
    private val sourceInputRaw = StringBuilder()
    private var inputId: String? = null
    /** F1.4 从「输入暂存」页带进来的记录 id，表示这是在重提 */
    private var retryInputId: String? = null
    private var isImageSource = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) handleImages(uris)
    }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) handleBitmap(bmp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.input_title)
        retryInputId = intent.getStringExtra("retryInputId")

        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(UiKit.c(this@InputActivity, R.color.saym_bg))
        }
        val root = UiKit.vertical(this).apply {
            setPadding(UiKit.dp(this@InputActivity, 16), UiKit.dp(this@InputActivity, 16),
                UiKit.dp(this@InputActivity, 16), UiKit.dp(this@InputActivity, 24))
        }
        scroll.addView(root); setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "记一件事"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(UiKit.c(this@InputActivity, R.color.saym_text))
        })
        root.addView(UiKit.muted(this, "文字可以直接打字；语音用输入法上的麦克风；也可以拍课本或截图丢进来。"))

        // ---- 输入区 ----
        root.addView(UiKit.spacer(this, 12))
        val inputCard = UiKit.card(this)
        val inputInner = UiKit.vertical(this)
        editText = EditText(this).apply {
            hint = getString(R.string.input_hint)
            minLines = 4
            gravity = Gravity.TOP
            textSize = 16f
            background = null
            intent.getStringExtra("prefill")?.let { setText(it) }
        }
        inputInner.addView(editText)
        inputCard.addView(inputInner)
        root.addView(inputCard)

        // ---- 动作按钮 ----
        root.addView(UiKit.primaryButton(this, "AI 提炼") { doExtractText() })
        root.addView(UiKit.spacer(this, 8))
        root.addView(UiKit.buttonRow(
            this,
            UiKit.outlinedButton(this, "相册选图") { pickImage.launch("image/*") },
            UiKit.outlinedButton(this, "拍照") { cameraLauncher.launch(null) }
        ))
        root.addView(UiKit.spacer(this, 8))
        root.addView(UiKit.textButton(this, "只暂存（不提炼，稍后重提）") { saveToStaging() })

        // ---- 预览区 ----
        root.addView(UiKit.spacer(this, 8))
        sourceLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(UiKit.c(this@InputActivity, R.color.saym_text_muted))
            setPadding(0, UiKit.dp(this@InputActivity, 6), 0, UiKit.dp(this@InputActivity, 6))
        }
        root.addView(sourceLabel)
        draftContainer = UiKit.vertical(this)
        root.addView(draftContainer)

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    // ============ F1.2 文字 ============
    private fun doExtractText() {
        val txt = editText.text.toString().trim()
        if (txt.isEmpty()) { toast("请先输入文字"); return }
        sourceInputRaw.setLength(0); sourceInputRaw.append(txt)
        isImageSource = false
        lifecycleScope.launch {
            try {
                // F1.4：先落本地暂存，保证弱网/崩溃不丢输入
                if (retryInputId == null && inputId == null) {
                    inputId = TodoRepo.addInput(InputType.TEXT, txt)
                } else if (retryInputId != null) {
                    inputId = retryInputId
                }
                val outcome = AiClient.extract(this@InputActivity, "TEXT", text = txt)
                showDrafts(outcome.drafts, outcome.modelUsed, outcome.fallbackFrom)
            } catch (e: Exception) {
                toast("提炼失败：${e.message}")
            }
        }
    }

    // ============ F1.3 图片 ============
    private fun handleImages(uris: List<Uri>) {
        isImageSource = true
        lifecycleScope.launch {
            val all = mutableListOf<TodoDraft>()
            var usedModel: String? = null
            var fallback: String? = null
            for (u in uris) {
                try {
                    val b64 = withContext(Dispatchers.IO) { uriToBase64Compressed(u) }
                    sourceInputRaw.append("[图片 ${u.lastPathSegment?.take(12) ?: ""}]")
                    val outcome = AiClient.extract(this@InputActivity, "IMAGE", imageBase64 = b64, mimeType = "image/jpeg")
                    usedModel = outcome.modelUsed ?: usedModel
                    fallback = outcome.fallbackFrom ?: fallback
                    all.addAll(outcome.drafts)
                } catch (e: Exception) {
                    toast("图片处理失败：${e.message}")
                }
            }
            if (all.isNotEmpty() && inputId == null) {
                inputId = TodoRepo.addInput(InputType.IMAGE, sourceInputRaw.toString().take(500), uris.firstOrNull()?.toString())
            }
            showDrafts(all, usedModel, fallback)
        }
    }

    private fun handleBitmap(bmp: Bitmap) {
        isImageSource = true
        lifecycleScope.launch {
            try {
                val b64 = withContext(Dispatchers.IO) { bitmapToBase64Compressed(bmp) }
                sourceInputRaw.append("[拍照]")
                val outcome = AiClient.extract(this@InputActivity, "IMAGE", imageBase64 = b64, mimeType = "image/jpeg")
                if (inputId == null && outcome.drafts.isNotEmpty()) {
                    inputId = TodoRepo.addInput(InputType.IMAGE, "[拍照]")
                }
                showDrafts(outcome.drafts, outcome.modelUsed, outcome.fallbackFrom)
            } catch (e: Exception) {
                toast("图片提炼失败：${e.message}")
            }
        }
    }

    // ============ F1.4 只暂存 ============
    private fun saveToStaging() {
        val txt = editText.text.toString().trim()
        if (txt.isEmpty()) { toast("请先输入文字"); return }
        lifecycleScope.launch {
            TodoRepo.addInput(InputType.TEXT, txt)
            toast("已暂存，可到「输入暂存」里重提")
            finish()
        }
    }

    // ============ F2.5 预览可改 ============
    private fun showDrafts(drafts: List<TodoDraft>, model: String?, fallbackFrom: String?) {
        draftState.clear(); draftState.addAll(drafts)
        draftContainer.removeAllViews()
        sourceLabel.text = buildString {
            if (!model.isNullOrBlank()) append("模型：$model")
            if (!fallbackFrom.isNullOrBlank()) append("（首选模型不可用，已自动降级）")
            if (model != null || fallbackFrom != null) append("　")
            append("本次 ${drafts.size} 条，待确认 ${drafts.count { it.needsConfirm }} 条")
        }
        if (drafts.isEmpty()) {
            val card = UiKit.card(this)
            card.addView(UiKit.emptyState(this, "没提炼出待办\n可以换个说法，或补上时间再试"))
            draftContainer.addView(card)
            return
        }
        drafts.forEachIndexed { i, d ->
            val card = UiKit.card(this, paddingDp = 14)
            val inner = UiKit.vertical(this)

            inner.addView(TextView(this).apply {
                text = d.title
                textSize = 16f
                setTextColor(UiKit.c(this@InputActivity, R.color.saym_text))
                setOnClickListener { editDraft(i) }
            })
            val chips = UiKit.chipRow(this)
            d.deadline?.let { chips.addView(UiKit.chipPrimary(this, it)) }
            d.subject?.let { chips.addView(UiKit.chipPrimary(this, it)) }
            if (d.isKeyTask) chips.addView(UiKit.chipStar(this, "★ 关键"))
            if (d.needsConfirm) chips.addView(UiKit.chip(this, "⚠ 请确认", R.color.saym_primary_light, R.color.saym_warn))
            if (chips.childCount > 0) inner.addView(chips)
            d.notes?.takeIf { it.isNotBlank() }?.let { inner.addView(UiKit.muted(this, it.take(120))) }

            inner.addView(UiKit.spacer(this, 8))
            inner.addView(UiKit.buttonRow(
                this,
                UiKit.outlinedButton(this, "编辑") { editDraft(i) },
                UiKit.primaryButton(this, "入库") { submitDraft(i) }
            ))
            card.addView(inner)
            draftContainer.addView(card)
        }
        if (drafts.size > 1) {
            draftContainer.addView(UiKit.primaryButton(this, "全部入库（${drafts.size} 条）") { submitAll() })
            draftContainer.addView(UiKit.spacer(this, 8))
        }
    }

    private fun editDraft(i: Int) {
        val d = draftState[i]
        val input = EditText(this).apply {
            setText("${d.title}\n${d.deadline ?: ""}\n${d.subject ?: ""}\n${d.notes ?: ""}")
            minLines = 4
        }
        AlertDialog.Builder(this).setTitle("编辑第 ${i + 1} 条")
            .setMessage("按行填写：标题 / 截止(yyyy-MM-dd 或 ISO) / 学科 / 备注")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val lines = input.text.toString().lines()
                if (lines.isNotEmpty()) {
                    draftState[i] = d.copy(
                        title = lines.getOrNull(0)?.trim().orEmpty().ifEmpty { d.title },
                        deadline = lines.getOrNull(1)?.trim()?.ifEmpty { null },
                        subject = lines.getOrNull(2)?.trim()?.ifEmpty { null },
                        notes = lines.getOrNull(3)?.trim()?.ifEmpty { null }
                    )
                }
                showDrafts(draftState, null, null)
            }.setNegativeButton("取消", null).show()
    }

    private suspend fun saveOne(d: TodoDraft): String {
        val cfg = LocalConfigStore.load(this)
        val created = TodoRepo.create(
            TodoCreateReq(
                title = d.title, deadline = d.deadline, subject = d.subject, notes = d.notes,
                isKeyTask = d.isKeyTask,
                remindOffsetMinutes = cfg.defaultRemindOffsetMinutes,
                sourceInputType = if (isImageSource) InputType.IMAGE else InputType.TEXT,
                sourceInputRaw = sourceInputRaw.toString().take(2000)
            )
        )
        // F5.2 关键任务定点提醒（用本地时间解析，支持多种写法）
        if (d.isKeyTask) KeyTaskReminderReceiver.scheduleForTodo(this, created)
        return created.id
    }

    private fun submitDraft(i: Int) {
        val d = draftState[i]
        lifecycleScope.launch {
            try {
                val id = saveOne(d)
                inputId?.let { TodoRepo.markInputProcessed(it, listOf(id)) }
                WatchPusher.pushTodos(this@InputActivity)
                draftState.removeAt(i)
                toast("已入库：${d.title}")
                showDrafts(draftState, null, null)
            } catch (e: Exception) { toast("入库失败：${e.message}") }
        }
    }

    private fun submitAll() {
        if (draftState.isEmpty()) { toast("没有待入库的条目"); return }
        val pending = draftState.toList()
        lifecycleScope.launch {
            var ok = 0
            val ids = mutableListOf<String>()
            for (d in pending) {
                try { ids.add(saveOne(d)); ok++ } catch (_: Exception) {}
            }
            inputId?.let { if (ids.isNotEmpty()) TodoRepo.markInputProcessed(it, ids) }
            WatchPusher.pushTodos(this@InputActivity)
            toast("已入库 $ok 条")
            if (ok == pending.size) finish() else {
                draftState.clear(); showDrafts(draftState, null, null)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ============ 图片读取 + 压缩（性能：单条提炼 ≤5 秒） ============

    private fun maxEdge(): Int = LocalConfigStore.load(this).routing.imageMaxEdge

    private fun uriToBase64Compressed(uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val limit = maxEdge()
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight, limit)
        }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("无法读取图片")
        return bitmapToBase64Compressed(bmp)
    }

    private fun bitmapToBase64Compressed(bmp: Bitmap): String {
        val scaled = scaleDown(bmp, maxEdge())
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun calcSampleSize(w: Int, h: Int, limit: Int): Int {
        var sample = 1
        var maxDim = maxOf(w, h)
        while (maxDim / 2 >= limit) { maxDim /= 2; sample *= 2 }
        return sample.coerceAtLeast(1)
    }

    private fun scaleDown(bmp: Bitmap, limit: Int): Bitmap {
        val maxDim = maxOf(bmp.width, bmp.height)
        if (maxDim <= limit) return bmp
        val ratio = limit.toFloat() / maxDim
        return Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
    }
}
