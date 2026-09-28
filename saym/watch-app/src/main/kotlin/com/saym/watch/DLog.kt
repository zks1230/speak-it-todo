package com.saym.watch

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志（**没有 adb 的情况下唯一能拿到内部情况的手段**）。
 *
 * 三重保障，任何一条能用就够：
 *  1. 内存环形缓冲（最后 200 条）→ 在 App 的「诊断」页大字显示，用户直接拍照；
 *  2. 写入 App 私有目录 `files/saym-log.txt`（不需要任何权限），重启后还能翻上次的；
 *  3. 同时投到 logcat（万一以后能用 adb 看到）。
 *
 * 关键原则：**日志里绝不出现 API Key**（手表端本来也没有 key，但时间戳/令牌要脱敏）。
 */
object DLog {
    private const val MAX_MEMORY = 200
    private const val FILE_NAME = "saym-log.txt"
    private const val MAX_FILE_BYTES = 256 * 1024

    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    @Volatile private var file: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        file = try {
            File(ctx.filesDir, FILE_NAME).also { f ->
                if (f.exists() && f.length() > MAX_FILE_BYTES) f.delete()
                append("=== 会话开始 · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ===")
            }
        } catch (e: Exception) { null }
    }

    fun i(msg: String) = append("• $msg")

    fun w(msg: String) = append("⚠ $msg")

    fun e(msg: String, t: Throwable? = null) {
        append("✖ $msg${t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""}")
    }

    @Synchronized
    private fun append(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        buffer.addLast(line)
        while (buffer.size > MAX_MEMORY) buffer.removeFirst()
        try { android.util.Log.i("SaymWatch", msg) } catch (_: Exception) {}
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    /** 最近的日志（供屏上显示） */
    @Synchronized
    fun recent(limit: Int = 120): List<String> = buffer.toList().takeLast(limit)

    @Synchronized
    fun clear() {
        buffer.clear()
        try { file?.writeText("") } catch (_: Exception) {}
    }

    /** 日志文件路径（设置页展示，用户可用文件管理器拷走） */
    fun filePath(): String? = file?.absolutePath

    /** 把日志复制到外部存储，方便用 LocalSend/文件管理器取走（失败就只靠拍照） */
    fun exportToExternal(ctx: Context): String? = try {
        val dir = ctx.getExternalFilesDir(null)
            ?: File(Environment.getExternalStorageDirectory(), "Android/data/${ctx.packageName}/files")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "saym-log-export.txt")
        out.writeText(recent(MAX_MEMORY).joinToString("\n"))
        out.absolutePath
    } catch (e: Exception) { null }
}
