package com.saym.phone

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.saym.phone.data.TodoRepo
import com.saym.phone.db.InputLocal
import kotlinx.coroutines.launch

/**
 * F1.4 输入暂存：未处理输入保留 30 天、可重提。
 *
 * 新架构：暂存完全存在手机本地（`inputs_local`），不依赖网络，
 * 断网也能看、也能重提（只有重提那一步的 AI 提炼需要联网）。
 */
class InputHistoryActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "输入暂存"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 12f; setPadding(0, 0, 0, 8) }
        root.addView(status)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(Button(this).apply {
            text = "刷新"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { load() }
        })
        bar.addView(Button(this).apply {
            text = "清理 30 天前"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener {
                lifecycleScope.launch { TodoRepo.purgeOldInputs(30); load() }
            }
        })
        root.addView(bar)
        val sv = ScrollView(this)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(container)
        root.addView(sv, LinearLayout.LayoutParams(-1, -1, 1f))
        setContentView(root)
        load()
    }

    private fun load() {
        lifecycleScope.launch { render(TodoRepo.listInputs()) }
    }

    private fun render(list: List<InputLocal>) {
        container.removeAllViews()
        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "（没有暂存记录）"; gravity = Gravity.CENTER; setPadding(0, 24, 0, 0)
            })
            status.text = "共 0 条（保留 30 天）"
            return
        }
        list.forEach { rec ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(4, 12, 4, 12) }
            box.addView(TextView(this).apply {
                text = (if (rec.type == "IMAGE") "🖼 " else "📝 ") +
                    (rec.text ?: rec.imageRef ?: "(空)").take(80)
                textSize = 14f
            })
            val produced = rec.extractedTodoIds?.split(",")?.count { it.isNotBlank() } ?: 0
            box.addView(TextView(this).apply {
                text = "${rec.createdAt.take(19)}　" +
                    if (rec.processed) "已处理（产出 $produced 条）" else "未处理"
                textSize = 11f
            })
            if (!rec.processed && !rec.text.isNullOrBlank()) {
                box.addView(Button(this).apply {
                    text = "重提"
                    setOnClickListener {
                        startActivity(Intent(this@InputHistoryActivity, InputActivity::class.java).apply {
                            putExtra("prefill", rec.text)
                            putExtra("retryInputId", rec.id)
                        })
                    }
                })
            }
            container.addView(box)
            container.addView(android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, 1).apply { setBackgroundColor(0xFFEEEEEE.toInt()) }
            })
        }
        status.text = "共 ${list.size} 条（保留 30 天，过期自动清理）"
    }
}
