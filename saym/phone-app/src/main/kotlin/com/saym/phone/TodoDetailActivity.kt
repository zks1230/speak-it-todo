package com.saym.phone

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.saym.phone.data.TodoRepo
import com.saym.phone.model.TodoUpdateReq
import com.saym.phone.remind.KeyTaskReminderReceiver
import com.saym.phone.watch.WatchPusher
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * F3.3 待办详情/编辑 + 详情页要求的「标题 / 时间 / 备注 / 来源原始输入（可回看）」。
 *
 * 新架构：读写全走本地库 [TodoRepo]，保存立即生效（不需网络）；
 * 关键任务的定点提醒保存后立刻重排（F5.2）。
 */
class TodoDetailActivity : AppCompatActivity() {
    private var todoId: String = ""
    private lateinit var tTitle: EditText
    private lateinit var tDeadline: EditText
    private lateinit var tSubject: EditText
    private lateinit var tNotes: EditText
    private lateinit var tKey: CheckBox
    private lateinit var tRemind: EditText
    private lateinit var tOffset: EditText
    private lateinit var sourceView: TextView
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        todoId = intent.getStringExtra("id") ?: run { finish(); return }
        title = "编辑待办"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        statusView = TextView(this).apply { textSize = 11f; setPadding(0, 0, 0, 12) }
        root.addView(statusView)

        tTitle = EditText(this).apply { hint = "标题" }; root.addView(tTitle)
        tDeadline = EditText(this).apply { hint = "截止（yyyy-MM-dd 或 ISO 时间，留空=无）" }
        root.addView(tDeadline)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@TodoDetailActivity).apply {
                text = "选日期"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { pickDate() }
            })
            addView(Button(this@TodoDetailActivity).apply {
                text = "清空截止"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { tDeadline.setText("") }
            })
        })
        tSubject = EditText(this).apply { hint = "学科/标签" }; root.addView(tSubject)
        tNotes = EditText(this).apply { hint = "备注"; minLines = 2 }; root.addView(tNotes)
        tKey = CheckBox(this).apply { text = "关键任务（开启后可设定点提醒）" }; root.addView(tKey)
        tRemind = EditText(this).apply { hint = "定点提醒时间（如 2026-09-27T19:30:00，留空=无）" }
        root.addView(tRemind)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@TodoDetailActivity).apply {
                text = "选提醒时间"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { pickRemind() }
            })
            addView(Button(this@TodoDetailActivity).apply {
                text = "清空提醒"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { tRemind.setText("") }
            })
        })
        // 一键按截止时间设提醒（省去手填 ISO 时间）
        root.addView(Button(this).apply {
            text = "按截止时间自动设提醒（前一日 20:00）"
            setOnClickListener { suggestRemindFromDeadline() }
        })
        tOffset = EditText(this).apply { hint = "提前量（分钟，F5.3）" }; root.addView(tOffset)
        sourceView = TextView(this).apply { textSize = 12f; setPadding(0, 12, 0, 12) }
        root.addView(sourceView)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "保存"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { save() }
        })
        row.addView(Button(this).apply {
            text = "删除"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { delete() }
        })
        root.addView(row)
        setContentView(root)
        loadDetail()
    }

    private fun loadDetail() {
        lifecycleScope.launch {
            val item = TodoRepo.byId(todoId)
            if (item == null) { toast("找不到该待办"); finish(); return@launch }
            statusView.text = "ID: ${item.id}"
            tTitle.setText(item.title)
            tDeadline.setText(item.deadline.orEmpty())
            tSubject.setText(item.subject.orEmpty())
            tNotes.setText(item.notes.orEmpty())
            tKey.isChecked = item.isKeyTask
            tRemind.setText(item.remindAt.orEmpty())
            tOffset.setText(item.remindOffsetMinutes.toString())
            sourceView.text = buildString {
                append("来源：${if (item.sourceInputType.name == "IMAGE") "图片" else "文字"}")
                item.createdAt?.let { append("　创建：${it.take(19)}") }
                append("\n原始输入：${item.sourceInputRaw?.take(300) ?: "（无）"}")
                append("\n数据来源设备：${item.deviceId ?: "phone"}")
            }
        }
    }

    private fun pickDate() {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            tDeadline.setText(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d))
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickRemind() {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, hh, mm ->
                tRemind.setText(String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d:00", y, m + 1, d, hh, mm))
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    /** 用截止时间推一个合理的提醒时刻：截止日前一晚 20:00 */
    private fun suggestRemindFromDeadline() {
        val raw = tDeadline.text.toString().trim()
        if (raw.length < 10) { toast("请先填截止日期（yyyy-MM-dd）"); return }
        val date = runCatching { java.time.LocalDate.parse(raw.take(10)) }.getOrNull()
            ?: run { toast("截止日期格式不对"); return }
        tRemind.setText(date.minusDays(1).atTime(20, 0).toString())
        tKey.isChecked = true
        toast("已设为 ${date.minusDays(1)} 20:00，记得保存")
    }

    private fun save() {
        val deadline = tDeadline.text.toString().trim()
        val remind = tRemind.text.toString().trim()
        val offset = tOffset.text.toString().trim().toIntOrNull() ?: 0
        lifecycleScope.launch {
            try {
                val updated = TodoRepo.update(todoId, TodoUpdateReq(
                    title = tTitle.text.toString().trim().ifBlank { null },
                    deadline = deadline.ifBlank { null },
                    subject = tSubject.text.toString().trim(),
                    notes = tNotes.text.toString().trim(),
                    isKeyTask = tKey.isChecked,
                    remindAt = remind.ifBlank { null },
                    remindOffsetMinutes = offset,
                    clearDeadline = deadline.isBlank(),
                    clearRemindAt = remind.isBlank()
                )) ?: run { toast("保存失败：条目不存在"); return@launch }

                // F5.2 定点提醒：先撤旧的，再按新值排
                KeyTaskReminderReceiver.cancel(this@TodoDetailActivity, todoId.hashCode())
                if (updated.isKeyTask && !updated.isCompleted && updated.remindAt != null) {
                    KeyTaskReminderReceiver.scheduleForTodo(this@TodoDetailActivity, updated)
                }
                WatchPusher.pushTodos(this@TodoDetailActivity)
                toast("已保存")
                finish()
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
            }
        }
    }

    private fun delete() {
        lifecycleScope.launch {
            try {
                TodoRepo.delete(todoId)
                KeyTaskReminderReceiver.cancel(this@TodoDetailActivity, todoId.hashCode())
                WatchPusher.pushTodos(this@TodoDetailActivity)
                toast("已删除")
                finish()
            } catch (e: Exception) {
                toast("删除失败：${e.message}")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
