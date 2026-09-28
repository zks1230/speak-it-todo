package com.saym.phone.watch

import android.content.Context
import com.saym.phone.Prefs
import com.saym.phone.data.TodoRepo
import com.saym.phone.model.TodoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把本地改动推给手表（新架构里的"蓝牙备用通道"）。
 *
 * 你的选择是 **WiFi 为主**（手表来拉），所以这里定位为补充：
 *  - 手机上刚改完一条，立刻经蓝牙推给手表，手表通知栏马上更新（不用等它下次来拉）；
 *  - 蓝牙不可用时**静默失败**，不打扰用户——手表下次连 WiFi 自己会拉到。
 *
 * 同时承担「配对」：第一次把配对令牌 + 每日推送时间经蓝牙发给手表。
 */
object WatchPusher {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** 节流：两次蓝牙尝试之间至少间隔这么久，避免切个页面就连一次蓝牙（费电也费时间） */
    private const val THROTTLE_MS = 45_000L
    @Volatile private var lastAttemptAt = 0L

    /**
     * 本机改动后调用（best-effort，失败不影响本地）。
     * @param force true = 忽略节流（用户手点的「立即推送」用这个）
     */
    suspend fun pushTodos(ctx: Context, force: Boolean = false, localIp: String? = null) {
        // 没配对过就没必要唤醒蓝牙（新架构里 WiFi 才是主通道）
        Prefs.watchMac(ctx) ?: return
        if (Prefs.watchToken(ctx).isNullOrBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAt < THROTTLE_MS) return
        lastAttemptAt = now

        val ip = localIp ?: WatchServer.localIpv4()
        val token = Prefs.watchToken(ctx) ?: return
        val items = withContext(Dispatchers.IO) { TodoRepo.allForSync() }
        val watchCfg = com.saym.phone.cfg.LocalConfigStore.load(ctx)
        val payload = buildJsonObject {
            put("type", JsonPrimitive("todos"))
            put("items", JsonPrimitive(json.encodeToString(items)))
            // 让手表知道去哪里拉取（WiFi 主通道），以及自己的配对令牌
            ip?.let { put("phoneHost", JsonPrimitive(it)) }
            put("phonePort", JsonPrimitive(WatchServer.PORT_PUBLIC))
            put("token", JsonPrimitive(token))
            put("dailyHour", JsonPrimitive(watchCfg.dailyPushHour))
            put("dailyMinute", JsonPrimitive(watchCfg.dailyPushMinute))
            put("phoneDeviceId", JsonPrimitive(Prefs.deviceId(ctx)))
        }
        pushRaw(ctx, payload.toString())
    }

    /** 配对：把令牌和连接信息一次性发给手表 */
    suspend fun pushPairing(ctx: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val token = Prefs.ensureWatchToken(ctx)
        val ip = WatchServer.localIpv4()
        val c = com.saym.phone.cfg.LocalConfigStore.load(ctx)
        val payload = buildJsonObject {
            put("type", JsonPrimitive("pair"))
            put("token", JsonPrimitive(token))
            ip?.let { put("phoneHost", JsonPrimitive(it)) }
            put("phonePort", JsonPrimitive(WatchServer.PORT_PUBLIC))
            put("dailyHour", JsonPrimitive(c.dailyPushHour))
            put("dailyMinute", JsonPrimitive(c.dailyPushMinute))
            put("phoneDeviceId", JsonPrimitive(Prefs.deviceId(ctx)))
        }
        var ok = false
        var msg = ""
        val done = java.util.concurrent.CountDownLatch(1)
        pushRawWithCallback(ctx, payload.toString()) { success, message ->
            ok = success; msg = message; done.countDown()
        }
        done.await(20, java.util.concurrent.TimeUnit.SECONDS)
        if (msg.isBlank()) msg = "未收到手表回执（手表端 App 可能没在运行）"
        ok to msg
    }

    private fun pushRaw(ctx: Context, payload: String) {
        try {
            com.saym.phone.ble.Dw99SyncManager(ctx).pushRaw(payload) { _, _ -> }
        } catch (_: Exception) {}
    }

    private fun pushRawWithCallback(ctx: Context, payload: String, cb: (Boolean, String) -> Unit) {
        try {
            com.saym.phone.ble.Dw99SyncManager(ctx).pushRaw(payload, cb)
        } catch (e: Exception) {
            cb(false, e.message ?: "蓝牙推送异常")
        }
    }
}
