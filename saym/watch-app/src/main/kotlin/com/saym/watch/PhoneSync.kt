package com.saym.watch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 与手机的同步客户端（**手表主动连手机**，与手机端 WatchServer 的协议对齐）。
 *
 * 两个通道：
 *  1. **UDP 广播发现**（38314）：手表广播 `SAYM_DISCOVER`，手机回
 *     `{"magic":"SAYM_HERE","host":..,"port":..,"token":..}` —— 不依赖 mDNS，精简 ROM 也能用；
 *  2. **TCP 拉取**（38313）：一行 JSON 请求 / 一行 JSON 响应。
 *     `{"token":"..","cmd":"PULL"}` / `{"token":"..","cmd":"DONE","id":".."}` / `PING`
 *
 * 所有方法都是阻塞的，调用方必须放在子线程（本项目用 Thread，不引 coroutines 以省体积）。
 * 每一步都会写诊断日志，出问题时在手表「诊断」页能直接看到卡在哪。
 */
object PhoneSync {

    private const val CONNECT_TIMEOUT = 6000
    private const val READ_TIMEOUT = 12000
    private const val DISCOVER_TIMEOUT = 4000

    // ---- 与手机端 WatchServer 严格对齐的协议常量 ----
    /** 手机 UDP 发现端口 */
    const val PORT_DISCOVERY = 38314
    /** 手表广播的探测串 */
    const val DISCOVER_PROBE = "SAYM_DISCOVER"
    /** 手机回应里的 magic 字段，用于确认"回我的确实是说一嘴" */
    const val DISCOVER_REPLY_MAGIC = "SAYM_HERE"

    /** 同步结果，供 UI 展示 */
    data class Result(val ok: Boolean, val message: String, val count: Int = 0, val pending: Int = 0)

    // ============ 发现手机 ============

    /**
     * UDP 广播找手机。
     * @return 手机的 (host, port, token)，找不到返回 null
     */
    fun discoverPhone(ctx: Context): Triple<String, Int, String>? {
        DLog.i("开始 UDP 广播找手机（端口 $PORT_DISCOVERY）…")
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = DISCOVER_TIMEOUT
            }
            val probe = DISCOVER_PROBE.toByteArray(Charsets.UTF_8)
            // 255.255.255.255：同一 WiFi 与手机热点两种场景都能收到
            val packet = DatagramPacket(
                probe, probe.size, InetAddress.getByName("255.255.255.255"), PORT_DISCOVERY
            )
            socket.send(packet)
            DLog.i("广播已发出，等待手机回应…")

            val buf = ByteArray(1024)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            val text = String(reply.data, 0, reply.length, Charsets.UTF_8)
            val o = JSONObject(text)
            if (o.optString("magic") != DISCOVER_REPLY_MAGIC) {
                DLog.w("收到非本应用的广播回应，忽略：${text.take(60)}")
                return null
            }
            val host = o.optString("host")
            val port = o.optInt("port", 38313)
            val token = o.optString("token")
            val name = o.optString("name", "手机")
            if (host.isBlank()) { DLog.w("手机回应里没有 IP"); return null }
            DLog.i("发现手机：$host:$port（$name）")
            WatchStore.savePairing(ctx, host, port, token, name)
            Triple(host, port, token)
        } catch (e: java.net.SocketTimeoutException) {
            DLog.w("没等到手机回应（超时 ${DISCOVER_TIMEOUT}ms）—— 手机端 App 没开？不在同一 WiFi？")
            null
        } catch (e: Exception) {
            DLog.e("UDP 发现失败", e)
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // ============ 拉取 ============

    /**
     * 完整同步：发现（必要时）→ 拉全量 → 合并到本地 → 回写离线操作。
     * @param forceDiscover true = 忽略缓存的手机地址，重新广播发现
     */
    fun sync(ctx: Context, forceDiscover: Boolean = false): Result {
        DLog.i("=== 开始同步（forceDiscover=$forceDiscover）===")

        var host = if (forceDiscover) null else WatchStore.phoneHost(ctx)
        var port = WatchStore.phonePort(ctx)
        var token = WatchStore.token(ctx)

        // 没配对过，或强制发现 → 广播
        if (host.isNullOrBlank() || token.isNullOrBlank() || forceDiscover) {
            val found = discoverPhone(ctx) ?: return Result(
                false,
                "没找到手机。请确认：① 手机端「说一嘴」开着 ② 两个设备连同一个 WiFi（或手表连手机热点）"
            )
            host = found.first; port = found.second; token = found.third
        }

        // 1) 拉全量
        val pull = request(ctx, host, port, token, """{"cmd":"PULL"}""")
            ?: return fallbackToDiscovery(ctx, "TCP 连接手机失败（$host:$port）")

        if (!pull.optBoolean("ok", false)) {
            val err = pull.optString("error", "未知错误")
            DLog.w("手机拒绝请求：$err")
            // 令牌可能过期 → 重新发现一次再试
            if (err.contains("令牌")) {
                DLog.i("尝试重新配对…")
                val found = discoverPhone(ctx) ?: return Result(false, "重新配对失败：$err")
                val pull2 = request(ctx, found.first, found.second, found.third, """{"cmd":"PULL"}""")
                    ?: return Result(false, "重新配对后仍连不上手机")
                if (!pull2.optBoolean("ok", false)) return Result(false, "手机仍拒绝：" + pull2.optString("error"))
                return applyPull(ctx, pull2, found.first, found.second, found.third)
            }
            return Result(false, err)
        }
        return applyPull(ctx, pull, host, port, token)
    }

    private fun fallbackToDiscovery(ctx: Context, reason: String): Result {
        DLog.w("$reason —— 缓存地址可能过期（手机 IP 变了），重新广播找一次")
        val found = discoverPhone(ctx) ?: return Result(false, reason)
        val retry = request(ctx, found.first, found.second, found.third, """{"cmd":"PULL"}""")
            ?: return Result(false, reason)
        if (!retry.optBoolean("ok", false)) return Result(false, retry.optString("error", reason))
        return applyPull(ctx, retry, found.first, found.second, found.third)
    }

    private fun applyPull(
        ctx: Context,
        resp: JSONObject,
        host: String,
        port: Int,
        token: String
    ): Result {
        // 2) 解析并合并待办
        val arr = resp.optJSONArray("todos") ?: JSONArray()
        val incoming = (0 until arr.length()).mapNotNull {
            runCatching { Todo.fromJson(arr.getJSONObject(it)) }.getOrNull()
        }
        DLog.i("收到 ${incoming.size} 条待办")
        WatchStore.mergeFromServer(ctx, incoming)
        WatchStore.savePairing(ctx, host, port, token, WatchStore.phoneName(ctx))

        // 3) 回写离线期间的操作
        val pending = WatchStore.pendingOps(ctx)
        var synced = 0
        val doneIds = mutableListOf<String>()
        pending.forEach { (id, done) ->
            val cmd = if (done) "DONE" else "UNDONE"
            val r = request(ctx, host, port, token, """{"cmd":"$cmd","id":"$id"}""")
            if (r != null && r.optBoolean("ok", false)) {
                doneIds.add(id); synced++
            } else {
                DLog.w("回写失败：$cmd $id")
            }
        }
        WatchStore.clearOps(ctx, doneIds)
        if (pending.isNotEmpty()) DLog.i("离线操作已补交 $synced/${pending.size} 条")

        val now = System.currentTimeMillis()
        WatchStore.setLastSyncAt(ctx, now)
        val msg = "同步成功：${WatchStore.pendingTodos(ctx).size} 条待办" +
            if (pending.isNotEmpty()) "（补交 $synced 条）" else ""
        WatchStore.setLastSyncResult(ctx, msg)
        DLog.i("=== 同步完成：$msg ===")
        return Result(true, msg, WatchStore.pendingTodos(ctx).size, WatchStore.pendingOps(ctx).size)
    }

    // ============ 单个操作（完成/取消完成） ============

    /**
     * 标记完成。**先改本地再上报**：断网时也要有反馈，并把操作排进离线队列。
     */
    fun setCompleted(ctx: Context, id: String, done: Boolean): Result {
        WatchStore.markCompletedLocally(ctx, id, done)
        val host = WatchStore.phoneHost(ctx)
        val token = WatchStore.token(ctx)
        if (host.isNullOrBlank() || token.isNullOrBlank()) {
            WatchStore.enqueueOp(ctx, id, done)
            return Result(true, "已本地${if (done) "完成" else "恢复"}（未配对，等联网后补交）", 0, 1)
        }
        val cmd = if (done) "DONE" else "UNDONE"
        val r = request(ctx, host, WatchStore.phonePort(ctx), token, """{"cmd":"$cmd","id":"$id"}""")
        return if (r != null && r.optBoolean("ok", false)) {
            DLog.i("已同步到手机：$cmd $id")
            Result(true, if (done) "已完成" else "已恢复", 0, 0)
        } else {
            WatchStore.enqueueOp(ctx, id, done)
            Result(true, "已本地${if (done) "完成" else "恢复"}，连上手机后自动补交", 0, 1)
        }
    }

    // ============ 底层：一行 JSON 请求 / 一行 JSON 响应 ============

    private fun request(ctx: Context, host: String, port: Int, token: String?, bodyJson: String): JSONObject? {
        var socket: Socket? = null
        return try {
            val reqObj = JSONObject(bodyJson)
            reqObj.put("token", token ?: "")
            socket = Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT); soTimeout = READ_TIMEOUT }
            val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            writer.write(reqObj.toString()); writer.write("\n"); writer.flush()
            val reader: BufferedReader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val line = reader.readLine()
            if (line.isNullOrBlank()) { DLog.w("手机返回空响应"); null }
            else JSONObject(line)
        } catch (e: Exception) {
            DLog.w("TCP 请求失败（$host:$port）：${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
