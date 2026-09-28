package com.saym.phone.watch

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.saym.phone.Prefs
import com.saym.phone.data.TodoRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * 局域网同步服务（手机侧，**手机是主库、手表来拉**）。
 *
 * 为什么不用 HTTP：
 *  - 手表端尽量不引依赖、也不要为了一个接口引 HTTP 库；
 *  - 两端都是我自己写的，用「一行 JSON 请求 / 一行 JSON 响应」的原生 TCP 协议最省事、最省电。
 *
 * 协议（v1）：
 *   请求：一行 JSON + '\n'
 *     {"token":"<令牌>","cmd":"PING"}
 *     {"token":"...","cmd":"PULL"}                     拉全量（含 tombstone）
 *     {"token":"...","cmd":"DONE","id":"<todoId>"}      手表标记完成
 *     {"token":"...","cmd":"UNDONE","id":"<todoId>"}    手表取消完成
 *     {"token":"...","cmd":"ADD","title":"..."}         手表快速加一条（可选能力）
 *   响应：一行 JSON + '\n'
 *     {"ok":true,"serverTime":"...","todos":[{"id":..,"title":..,"isCompleted":..,
 *       "deadline":..,"subject":..,"isKeyTask":..,"remindAt":..,"updatedAt":..,"deleted":false}]}
 *     {"ok":false,"error":"..."}
 *
 * 安全：令牌在「配对」时由手机生成，经蓝牙一次性下发给手表；令牌不对直接拒绝。
 *       个人自用场景足够——同 WiFi 下别人拿到令牌才能读你的待办。
 */
object WatchServer {
    private const val TAG = "SaymWatchServer"
    private const val PORT = 38313

    /**
     * UDP 发现端口。手表广播 `SAYM_DISCOVER` 到这个端口，手机回一个 JSON 告诉它：
     * 手机 IP / TCP 端口 / 配对令牌 / 待办条数。
     *
     * 为什么不用 mDNS（虽然也保留了）：精简 ROM 的 mDNS 经常不工作，
     * 而 UDP 广播只是几十行代码，且出问题时日志能直接看出谁没回。
     */
    const val DISCOVERY_PORT = 38314
    const val DISCOVERY_PROBE = "SAYM_DISCOVER"
    const val DISCOVERY_REPLY = "SAYM_HERE"

    /** 对外可见（前台服务的常驻通知里要显示给用户看） */
    const val PORT_PUBLIC = PORT
    private const val SERVICE_TYPE = "_saym._tcp."
    private const val SERVICE_NAME_PREFIX = "saym-phone-"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: java.net.DatagramSocket? = null
    private var nsdManager: NsdManager? = null
    private var registeredName: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var running: Boolean = false
        private set

    /** 最近一次被手表发现/拉取的时间（设置页展示，便于确认"到底通没通"） */
    @Volatile var lastContactAt: Long = 0L
        private set

    /** 启动服务（幂等） */
    fun start(ctx: Context) {
        if (running) return
        running = true
        // 令牌在启动时固化：中途重新配对需要重启服务才对新连接生效
        val token: String = Prefs.watchToken(ctx) ?: ""
        scope.launch {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                Log.i(TAG, "局域网同步服务已启动，TCP 端口 $PORT")
                while (!ss.isClosed) {
                    val socket = try { ss.accept() } catch (e: Exception) { break }
                    lastContactAt = System.currentTimeMillis()
                    scope.launch { handle(ctx, socket, token) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TCP 服务启动失败（端口被占用？）：${e.message}")
                running = false
            }
        }
        startDiscovery(ctx)
        registerNsd(ctx)
    }

    /** UDP 发现应答：手表广播一句，手机回自己的位置 */
    private fun startDiscovery(ctx: Context) {
        scope.launch {
            try {
                val ds = java.net.DatagramSocket(DISCOVERY_PORT)
                ds.broadcast = true
                discoverySocket = ds
                Log.i(TAG, "UDP 发现服务已启动，端口 $DISCOVERY_PORT")
                val buf = ByteArray(256)
                while (!ds.isClosed) {
                    val packet = java.net.DatagramPacket(buf, buf.size)
                    try { ds.receive(packet) } catch (e: Exception) { break }
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (msg != DISCOVERY_PROBE) continue
                    lastContactAt = System.currentTimeMillis()
                    val ip = localIpv4() ?: continue
                    val reply = buildJsonObject {
                        put("magic", JsonPrimitive(DISCOVERY_REPLY))
                        put("host", JsonPrimitive(ip))
                        put("port", JsonPrimitive(PORT))
                        // 自用场景：令牌随发现应答下发，手表无需手抄
                        put("token", JsonPrimitive(Prefs.watchToken(ctx) ?: ""))
                        put("name", JsonPrimitive(Prefs.deviceName(ctx)))
                        put("pending", JsonPrimitive(TodoRepo.countPending()))
                    }.toString().toByteArray(Charsets.UTF_8)
                    try {
                        ds.send(java.net.DatagramPacket(reply, reply.size, packet.address, packet.port))
                        Log.i(TAG, "已响应发现请求，回复给 ${packet.address.hostAddress}")
                    } catch (e: Exception) {
                        Log.w(TAG, "响应发现请求失败：${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP 发现服务启动失败：${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { discoverySocket?.close() } catch (_: Exception) {}
        discoverySocket = null
        unregisterNsd()
    }

    /** 本机在局域网里的 IPv4（让用户在手表上手填备用地址时能看到） */
    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) { null }

    // ============ mDNS 注册：让手表自动发现手机 ============

    private fun registerNsd(ctx: Context) {
        try {
            val mgr = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = mgr
            val name = SERVICE_NAME_PREFIX + Prefs.deviceId(ctx).takeLast(6)
            registeredName = name
            val info = NsdServiceInfo().apply {
                serviceName = name
                serviceType = SERVICE_TYPE
                port = PORT
            }
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    registeredName = info.serviceName
                    Log.i(TAG, "已广播服务 ${info.serviceName}（$SERVICE_TYPE）")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS 注册失败 code=$errorCode（手表可改用手填 IP）")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            })
        } catch (e: Exception) {
            Log.w(TAG, "mDNS 注册异常：${e.message}")
        }
    }

    private fun unregisterNsd() {
        try { nsdManager?.unregisterService(object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }) } catch (_: Exception) {}
    }

    // ============ 请求处理 ============

    private suspend fun handle(ctx: Context, socket: Socket, token: String) {
        try {
            socket.soTimeout = 15_000
            val reader: BufferedReader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return respond(writer, err("空请求"))
            val req = try { json.parseToJsonElement(line).jsonObject } catch (e: Exception) {
                return respond(writer, err("请求不是合法 JSON"))
            }
            val got = req["token"]?.jsonPrimitive?.contentOrNull ?: ""
            if (token.isBlank()) {
                return respond(writer, err("手机还没生成配对令牌，请先在手机「设置 → 手表配对」里生成"))
            }
            if (got != token) {
                // 不要回显期望令牌
                return respond(writer, err("配对令牌不对，请在手机设置里重新配对"))
            }
            when (val cmd = req["cmd"]?.jsonPrimitive?.contentOrNull ?: "") {
                "PING" -> respond(writer, buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("serverTime", JsonPrimitive(com.saym.phone.Prefs.deviceId(ctx)))
                    put("pending", JsonPrimitive(TodoRepo.countPending()))
                })
                "PULL" -> {
                    val todos = TodoRepo.allForSync()
                    val arr = kotlinx.serialization.json.JsonArray(todos.map { t ->
                        buildJsonObject {
                            put("id", JsonPrimitive(t.id))
                            put("title", JsonPrimitive(t.title))
                            t.deadline?.let { put("deadline", JsonPrimitive(it)) }
                            t.subject?.let { put("subject", JsonPrimitive(it)) }
                            t.isKeyTask.let { put("isKeyTask", JsonPrimitive(it)) }
                            put("isCompleted", JsonPrimitive(t.isCompleted))
                            t.remindAt?.let { put("remindAt", JsonPrimitive(it)) }
                            put("remindOffsetMinutes", JsonPrimitive(t.remindOffsetMinutes))
                            put("updatedAt", JsonPrimitive(t.updatedAt))
                            put("deleted", JsonPrimitive(t.deleted))
                        }
                    })
                    respond(writer, buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("serverTime", JsonPrimitive(java.time.Instant.now().toString()))
                        put("todos", arr)
                    })
                }
                "DONE", "UNDONE" -> {
                    val id = req["id"]?.jsonPrimitive?.contentOrNull
                    if (id.isNullOrBlank()) return respond(writer, err("缺少 id"))
                    val done = cmd == "DONE"
                    TodoRepo.completeFromWatch(id, done)
                    respond(writer, buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("pending", JsonPrimitive(TodoRepo.countPending()))
                    })
                }
                "ADD" -> {
                    val title = req["title"]?.jsonPrimitive?.contentOrNull
                    if (title.isNullOrBlank()) return respond(writer, err("缺少 title"))
                    val item = TodoRepo.create(
                        com.saym.phone.model.TodoCreateReq(
                            title = title,
                            deadline = req["deadline"]?.jsonPrimitive?.contentOrNull,
                            sourceInputType = com.saym.phone.model.InputType.TEXT,
                            sourceInputRaw = "（来自手表）"
                        ),
                        originDevice = "watch"
                    )
                    respond(writer, buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("id", JsonPrimitive(item.id))
                    })
                }
                else -> respond(writer, err("未知指令：$cmd"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "处理请求异常：${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun respond(writer: BufferedWriter, obj: JsonObject) {
        try {
            writer.write(json.encodeToString(JsonObject.serializer(), obj))
            writer.write("\n")
            writer.flush()
        } catch (_: Exception) {}
    }

    private fun err(msg: String): JsonObject = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error", JsonPrimitive(msg))
    }
}
