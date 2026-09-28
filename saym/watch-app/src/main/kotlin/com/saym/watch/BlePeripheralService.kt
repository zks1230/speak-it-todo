package com.saym.watch

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 蓝牙兜底通道（手机 → 手表）。
 *
 * 主通道是 **WiFi + UDP 发现 + TCP 拉取**（见 [PhoneSync]）。本服务负责两件事：
 *  1. 接收手机经蓝牙下发的**配对信息**（手机 IP / 端口 / 令牌 / 每日提醒时间）——
 *     这样用户不用手抄任何东西，接着走 WiFi 同步即可；
 *  2. WiFi 不可用时，手机直接推待办列表过来，手表照样能看。
 *
 * 协议（**必须与手机端 Dw99SyncManager 严格一致**，否则会重演
 * "两端协议不一致导致同步 100% 不通"的旧坑）：
 *
 *      SAYM1 <payloadByteLength>\n<payload JSON bytes>
 *
 *  - 按**字节**缓冲解析，与 BLE 分包边界无关；
 *  - 处理完保留剩余字节，支持一次连接连发多条消息；
 *  - 收到完整包后经 ACK 特征值（0000fef3，notify）回执，手机收到回执才算成功。
 */
class BlePeripheralService : Service() {

    private val SVC_UUID = UUID.fromString("0000fef1-0000-1000-8000-00805f9b34fb")
    private val CHR_UUID = UUID.fromString("0000fef2-0000-1000-8000-00805f9b34fb")
    private val ACK_UUID = UUID.fromString("0000fef3-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gattServer: BluetoothGattServer? = null
    private var ackChar: BluetoothGattCharacteristic? = null
    private var connectedDevice: android.bluetooth.BluetoothDevice? = null

    private val buffer = java.io.ByteArrayOutputStream()
    /** 已解析出头部、等待 payload 的长度；-1 表示还没读到头部 */
    private var expectedPayload = -1

    override fun onCreate() {
        super.onCreate()
        DLog.init(this)
        DLog.i("蓝牙兜底服务启动")
        startForegroundSafely()
        startGatt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { gattServer?.close() } catch (_: Exception) {}
        DLog.w("蓝牙兜底服务销毁")
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        WatchNotify.ensureChannels(this)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, WatchNotify.CH_TASKS)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = builder
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("说一嘴 · 蓝牙接收中")
            .setContentText("等待手机下发配对信息")
            .setOngoing(true)
            .build()
        try {
            startForeground(2, n)
        } catch (e: Exception) {
            DLog.w("蓝牙服务前台化被拒：${e.message}")
        }
    }

    private fun startGatt() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null) { DLog.w("没有蓝牙适配器"); return }
        if (!adapter.isEnabled) { DLog.w("蓝牙未开启（等用户开启后重启本服务）"); return }
        try {
            gattServer = bm.openGattServer(this, object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(
                    device: android.bluetooth.BluetoothDevice?, status: Int, newState: Int
                ) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connectedDevice = device
                        DLog.i("手机已连接蓝牙：${device?.address}")
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        connectedDevice = null
                        buffer.reset(); expectedPayload = -1
                        DLog.i("手机蓝牙已断开")
                    }
                }

                override fun onCharacteristicWriteRequest(
                    device: android.bluetooth.BluetoothDevice?,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (value != null && characteristic.uuid == CHR_UUID) {
                        buffer.write(value)
                        drainBuffer()
                    }
                    if (responseNeeded) {
                        try {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                        } catch (_: Exception) {}
                    }
                }

                override fun onDescriptorWriteRequest(
                    device: android.bluetooth.BluetoothDevice?,
                    requestId: Int,
                    descriptor: BluetoothGattDescriptor,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (responseNeeded) {
                        try {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                        } catch (_: Exception) {}
                    }
                }
            })

            val svc = BluetoothGattService(SVC_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            svc.addCharacteristic(
                BluetoothGattCharacteristic(
                    CHR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )
            )
            ackChar = BluetoothGattCharacteristic(
                ACK_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }
            svc.addCharacteristic(ackChar)
            gattServer?.addService(svc)
            startAdvertise()
            DLog.i("GATT 服务已注册并开始广播")
        } catch (e: Exception) {
            DLog.e("蓝牙服务启动失败", e)
        }
    }

    private fun startAdvertise() {
        try {
            val adv: BluetoothLeAdvertiser? =
                (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeAdvertiser
            if (adv == null) { DLog.w("该设备不支持 BLE 广播"); return }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(SVC_UUID))
                .build()
            adv.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    DLog.i("BLE 广播已开始")
                }
                override fun onStartFailure(errorCode: Int) {
                    DLog.w("BLE 广播失败 code=$errorCode")
                }
            })
        } catch (e: Exception) {
            DLog.w("BLE 广播异常：${e.message}")
        }
    }

    /** 字节级分帧解析：`SAYM1 <len>\n<payload>` */
    private fun drainBuffer() {
        var guard = 0
        while (guard++ < 64) {
            val bytes = buffer.toByteArray()
            if (expectedPayload < 0) {
                val nl = bytes.indexOf('\n'.code.toByte())
                if (nl < 0) {
                    if (bytes.size > 64) { buffer.reset(); DLog.w("头部异常，已清空缓冲") }
                    return
                }
                val header = String(bytes, 0, nl, Charsets.US_ASCII).trim()
                val len = header.removePrefix("SAYM1").trim().toIntOrNull()
                val rest = bytes.copyOfRange(nl + 1, bytes.size)
                buffer.reset(); buffer.write(rest)
                if (len == null || len <= 0 || len > 512 * 1024) {
                    // 兼容旧格式：整行就是一个 JSON
                    DLog.w("未识别长度头（$header），按旧格式尝试解析")
                    try { handlePayload(JSONObject(header)) } catch (_: Exception) {}
                    continue
                }
                expectedPayload = len
                continue
            }
            val cur = buffer.toByteArray()
            if (cur.size < expectedPayload) return
            val payload = cur.copyOfRange(0, expectedPayload)
            val leftover = cur.copyOfRange(expectedPayload, cur.size)
            buffer.reset(); buffer.write(leftover)
            expectedPayload = -1
            try {
                handlePayload(JSONObject(String(payload, Charsets.UTF_8)))
                sendAck("OK")
            } catch (e: Exception) {
                DLog.e("蓝牙包解析失败", e)
                sendAck("ERR")
            }
        }
    }

    private fun sendAck(msg: String) {
        val ch = ackChar ?: return
        val dev = connectedDevice ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(dev, ch, false, msg.toByteArray(Charsets.UTF_8))
            } else {
                @Suppress("DEPRECATION")
                ch.value = msg.toByteArray(Charsets.UTF_8)
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(dev, ch, false)
            }
            DLog.i("已回执：$msg")
        } catch (e: Exception) {
            DLog.w("回执失败：${e.message}")
        }
    }

    private fun handlePayload(o: JSONObject) {
        when (o.optString("type", "")) {
            // 手机端「设置 → 手表 → ③ 把配对信息经蓝牙发给手表」
            "pair", "config" -> {
                val host = o.optString("phoneHost", "").ifBlank { null }
                val port = o.optInt("phonePort", 38313)
                val token = o.optString("token", "").ifBlank { null }
                WatchStore.savePairing(this, host, port, token, o.optString("phoneDeviceId", "手机"))
                applyDaily(o)
                DLog.i("已收到配对信息：$host:$port" + if (token != null) "（含令牌）" else "（无令牌）")
                WatchNotify.vibrate(this, longArrayOf(0, 120, 100, 120))
                WatchNotify.notify(this, "配对成功", "已记住手机位置，点开 App 同步", 3, WatchNotify.CH_TASKS)
                // 立刻走 WiFi 拉一次，顺便验证链路
                Thread {
                    val r = PhoneSync.sync(this, forceDiscover = false)
                    DLog.i("配对后首次同步：${r.message}")
                    WatchService.refresh(this)
                }.start()
            }
            "todos" -> {
                val itemsRaw = o.optString("items", "[]")
                val arr = runCatching { JSONArray(itemsRaw) }.getOrNull()
                if (arr != null) {
                    val items = (0 until arr.length()).mapNotNull {
                        runCatching { Todo.fromJson(arr.getJSONObject(it)) }.getOrNull()
                    }
                    WatchStore.mergeFromServer(this, items)
                    DLog.i("蓝牙收到 ${items.size} 条待办")
                }
                o.optString("phoneHost", "").ifBlank { null }?.let { h ->
                    WatchStore.savePairing(
                        this, h, o.optInt("phonePort", 38313),
                        o.optString("token", "").ifBlank { null }, o.optString("phoneDeviceId", "手机")
                    )
                }
                applyDaily(o)
                WatchStore.setLastSyncAt(this, System.currentTimeMillis())
                WatchStore.setLastSyncResult(this, "已通过蓝牙收到 ${WatchStore.pendingTodos(this).size} 条")
                WatchNotify.vibrate(this, longArrayOf(0, 200, 100, 200))
                WatchService.refresh(this)
                Alarms.rescheduleAllFromCache(this)
            }
            "remind" -> {
                val title = o.optString("title", "关键任务")
                val id = o.optString("todoId", "")
                val at = o.optLong("at", System.currentTimeMillis() + 60_000L)
                Alarms.scheduleKeyTask(this, id, title, at)
                DLog.i("蓝牙下发定点提醒：$title")
            }
            "ping" -> DLog.i("收到 ping")
        }
    }

    private fun applyDaily(o: JSONObject) {
        val h = o.optInt("dailyHour", -1)
        val m = o.optInt("dailyMinute", -1)
        if (h in 0..23 && m in 0..59) {
            WatchStore.setDailyTime(this, h, m)
            Alarms.scheduleDaily(this, h, m)
            DLog.i("每日提醒时间已更新为 %02d:%02d".format(h, m))
        }
    }
}
