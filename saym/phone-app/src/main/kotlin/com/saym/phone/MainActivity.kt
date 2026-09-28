package com.saym.phone

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.saym.phone.data.TodoRepo
import com.saym.phone.model.TodoItem
import com.saym.phone.remind.DailyPushReceiver
import com.saym.phone.remind.KeyTaskReminderReceiver
import com.saym.phone.ui.UiKit
import com.saym.phone.watch.WatchPusher
import com.saym.phone.watch.WatchSyncService
import kotlinx.coroutines.launch

/**
 * 首页（需求 §5.1）。
 *
 * 数据全部来自本地库 [TodoRepo]，打开即有内容，不等网络。
 *  - F3.1 今日 / 本周 / 逾期 / 全部
 *  - F3.2 单条完成 + 多选批量完成
 *  - F3.3 点条目进编辑
 *  - F3.8 完成率看板
 *
 * 视觉：Material 卡片 + FilterChip，替代之前的默认灰按钮。
 */
class MainActivity : AppCompatActivity() {
    /** 分组顺序：今日 / 明日 / 本周 / 逾期 / 全部（用枚举，不再依赖序号） */
    private val filters = com.saym.phone.data.TodoFilter.entries.toList()
    private var currentFilter = com.saym.phone.data.TodoFilter.TODAY

    private lateinit var root: LinearLayout
    private lateinit var statsRow: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var selectionCard: com.google.android.material.card.MaterialCardView
    private lateinit var selectionLabel: TextView
    private lateinit var batchRow: LinearLayout
    private lateinit var chipRow: LinearLayout

    private var items: List<TodoItem> = emptyList()
    private val selected = linkedSetOf<String>()
    private var selectMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        // 局域网同步服务在"界面已存在"时启动（Android 12+ 禁止在 Application 里启动前台服务）
        WatchSyncService.start(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        lifecycleScope.launch {
            try { DailyPushReceiver.pullAndNotify(this@MainActivity) } catch (_: Exception) {}
        }
    }

    // ============ 界面搭建 ============

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.c(this@MainActivity, R.color.saym_bg)) }
        root = UiKit.vertical(this).apply { setPadding(UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 24)) }
        scroll.addView(root)
        setContentView(scroll)

        // ---- 顶部：标题 + 设置/输入暂存入口 ----
        buildHeaderAndActions()
        root.addView(UiKit.muted(this, "随手丢进来，AI 帮你理成待办"))

        // ---- 主要动作 ----
        root.addView(UiKit.spacer(this, 12))
        root.addView(UiKit.primaryButton(this, "＋  记一件事（语音 / 文字 / 图片）") {
            startActivity(Intent(this, InputActivity::class.java))
        })

        // ---- 看板 ----
        root.addView(UiKit.spacer(this, 12))
        val statsCard = UiKit.card(this)
        val statsInner = UiKit.vertical(this)
        statsInner.addView(UiKit.sectionLabel(this, "完成率看板").apply { setPadding(0, 0, 0, 8) })
        statsRow = UiKit.vertical(this)
        statsInner.addView(statsRow)
        statsCard.addView(statsInner)
        root.addView(statsCard)

        // ---- 分组 ----
        chipRow = UiKit.vertical(this)
        root.addView(chipRow)

        // ---- 多选操作条（默认隐藏）----
        selectionCard = UiKit.card(this)
        selectionLabel = UiKit.body(this, "已选 0 条")
        val selInner = UiKit.vertical(this)
        selInner.addView(selectionLabel)
        selInner.addView(UiKit.spacer(this, 8))
        batchRow = UiKit.vertical(this)
        selInner.addView(batchRow)
        selectionCard.addView(selInner)
        selectionCard.visibility = View.GONE
        root.addView(selectionCard)

        // ---- 列表 ----
        listContainer = UiKit.vertical(this)
        root.addView(listContainer)
    }

    /** 顶部：标题 + 设置/多选入口（单独一行，避免和分组标签挤在一起被挤出屏幕） */
    private fun buildHeaderAndActions() {
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = "说一嘴"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(UiKit.c(this@MainActivity, R.color.saym_text))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(UiKit.textButton(this, "输入暂存") {
            startActivity(Intent(this, InputHistoryActivity::class.java))
        })
        headerRow.addView(UiKit.textButton(this, "设置") {
            startActivity(Intent(this, ConfigActivity::class.java))
        })
        root.addView(headerRow)
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        // 第一行：分组标签（今日 / 明日 / 本周 / 逾期 / 全部）
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filters.forEach { f ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = f.label
                isCheckable = true
                isChecked = f == currentFilter
                chipCornerRadius = UiKit.dp(this@MainActivity, 10).toFloat()
                setOnClickListener { currentFilter = f; refresh() }
            }
            row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = UiKit.dp(this@MainActivity, 6)
            })
        }
        chipRow.addView(row)
        // 第二行：多选入口（单独一行，避免被挤掉）
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(UiKit.textButton(this, if (selectMode) "退出多选" else "多选 / 批量完成") { toggleSelectMode() })
        chipRow.addView(row2)
    }

    // ============ 数据 ============

    private fun refresh() {
        lifecycleScope.launch {
            items = try { TodoRepo.list(currentFilter) } catch (e: Exception) {
                UiKit.toast(this@MainActivity, "读取本地数据失败：${e.message}"); emptyList()
            }
            val stats = try { TodoRepo.stats() } catch (e: Exception) { null }
            render(stats)
            try { WatchPusher.pushTodos(this@MainActivity) } catch (_: Exception) {}
        }
    }

    private fun render(stats: TodoRepo.Stats?) {
        buildChips()
        renderStats(stats)
        renderSelectionBar()

        listContainer.removeAllViews()
        if (items.isEmpty()) {
            val empty = UiKit.card(this)
            empty.addView(UiKit.emptyState(this, "这里还没有待办\n点上面的按钮记一件事吧"))
            listContainer.addView(empty)
            return
        }
        items.forEach { t -> listContainer.addView(buildRow(t)) }
    }

    private fun renderStats(stats: TodoRepo.Stats?) {
        statsRow.removeAllViews()
        if (stats == null) {
            statsRow.addView(UiKit.muted(this, "看板读取失败"))
            return
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(label: String, value: String, colorRes: Int) {
            val cellView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            cellView.addView(TextView(this).apply {
                text = value
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(UiKit.c(this@MainActivity, colorRes))
                gravity = Gravity.CENTER
            })
            cellView.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(UiKit.c(this@MainActivity, R.color.saym_text_muted))
                gravity = Gravity.CENTER
            })
            row.addView(cellView)
        }
        cell("待完成", stats.totalPending.toString(), R.color.saym_primary)
        cell("本周完成", stats.weekCompleted.toString(), R.color.saym_ok)
        cell("本周逾期", stats.weekOverdue.toString(), R.color.saym_danger)
        cell("完成率", "${(stats.completionRate * 100).toInt()}%", R.color.saym_text)
        cell("连续天数", stats.streakDays.toString(), R.color.saym_star)
        statsRow.addView(row)
    }

    private fun renderSelectionBar() {
        selectionCard.visibility = if (selectMode) View.VISIBLE else View.GONE
        if (!selectMode) return
        selectionLabel.text = "已选 ${selected.size} 条"
        batchRow.removeAllViews()
        batchRow.addView(UiKit.buttonRow(
            this,
            UiKit.primaryButton(this, "批量完成") { batch(true) },
            UiKit.outlinedButton(this, "取消完成") { batch(false) },
            UiKit.outlinedButton(this, if (selected.size == items.size && items.isNotEmpty()) "取消全选" else "全选") {
                if (selected.size == items.size) selected.clear() else items.forEach { selected.add(it.id) }
                renderSelectionBar(); renderListOnly()
            }
        ))
    }

    private fun renderListOnly() {
        listContainer.removeAllViews()
        items.forEach { t -> listContainer.addView(buildRow(t)) }
    }

    /** 单条待办卡片 */
    private fun buildRow(t: TodoItem): View {
        val card = UiKit.card(this, paddingDp = 14)
        val inner = UiKit.vertical(this)

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (selectMode) {
            titleRow.addView(android.widget.CheckBox(this).apply {
                isChecked = selected.contains(t.id)
                setOnCheckedChangeListener { _, chk ->
                    if (chk) selected.add(t.id) else selected.remove(t.id)
                    selectionLabel.text = "已选 ${selected.size} 条"
                }
            })
        }
        titleRow.addView(TextView(this).apply {
            text = t.title
            textSize = 16f
            setTextColor(UiKit.c(this@MainActivity, if (t.isCompleted) R.color.saym_text_muted else R.color.saym_text))
            if (t.isCompleted) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { openDetail(t) }
        })
        inner.addView(titleRow)

        // 标签行
        val chips = UiKit.chipRow(this)
        if (t.isKeyTask) chips.addView(UiKit.chipStar(this, "★ 关键"))
        t.subject?.let { chips.addView(UiKit.chipPrimary(this, it)) }
        t.deadline?.let { d ->
            val overdue = !t.isCompleted && d.take(10) < today()
            chips.addView(
                if (overdue) UiKit.chip(this, "逾期 ${d.take(10)}", R.color.saym_primary_light, R.color.saym_danger)
                else UiKit.chipPrimary(this, d.take(10))
            )
        }
        if (t.remindAt != null) chips.addView(UiKit.chip(this, "⏰ 已设提醒", R.color.saym_primary_light, R.color.saym_warn))
        if (t.sourceInputType.name == "IMAGE") chips.addView(UiKit.chipPrimary(this, "来自图片"))
        if (t.isCompleted) chips.addView(UiKit.chipOk(this, "已完成"))
        if (chips.childCount > 0) inner.addView(chips)

        t.notes?.takeIf { it.isNotBlank() }?.let {
            inner.addView(UiKit.spacer(this, 6))
            inner.addView(UiKit.muted(this, it.take(120)))
        }

        inner.addView(UiKit.spacer(this, 10))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(UiKit.textButton(this, "编辑") { openDetail(t) })
        actions.addView(UiKit.spacer(this, 1).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        actions.addView(UiKit.outlinedButton(this, if (t.isCompleted) "撤销完成" else "完成") { toggleComplete(t) })
        inner.addView(actions)

        card.addView(inner)
        return card
    }

    // ============ 操作 ============

    private fun toggleComplete(t: TodoItem) {
        lifecycleScope.launch {
            try {
                val done = !t.isCompleted
                TodoRepo.setCompleted(t.id, done)
                if (done) KeyTaskReminderReceiver.cancel(this@MainActivity, t.id.hashCode())
                else if (t.isKeyTask && t.remindAt != null) {
                    KeyTaskReminderReceiver.scheduleForTodo(this@MainActivity, t.copy(isCompleted = false))
                }
                WatchPusher.pushTodos(this@MainActivity)
            } catch (e: Exception) {
                UiKit.toast(this@MainActivity, "操作失败：${e.message}")
            }
            refresh()
        }
    }

    /** F3.2 批量完成 */
    private fun batch(completed: Boolean) {
        if (selected.isEmpty()) { UiKit.toast(this, "请先勾选条目"); return }
        val ids = selected.toList()
        lifecycleScope.launch {
            try {
                val n = TodoRepo.setCompletedBatch(ids, completed)
                if (completed) ids.forEach { KeyTaskReminderReceiver.cancel(this@MainActivity, it.hashCode()) }
                UiKit.toast(this@MainActivity, "已处理 $n 条")
                WatchPusher.pushTodos(this@MainActivity)
                selected.clear()
                selectMode = false
            } catch (e: Exception) {
                UiKit.toast(this@MainActivity, "批量操作失败：${e.message}")
            }
            refresh()
        }
    }

    private fun toggleSelectMode() {
        selectMode = !selectMode
        selected.clear()
        refresh()
    }

    private fun openDetail(t: TodoItem) {
        startActivity(Intent(this, TodoDetailActivity::class.java).apply { putExtra("id", t.id) })
    }

    private fun today(): String =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
}
