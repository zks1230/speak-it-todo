package com.saym.phone.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.saym.phone.Prefs
import com.saym.phone.model.TodoItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * DW99 蓝牙同步（手机作 central 写数据到手表）。
 *
 * ⚠️ 协议（这是初版最大的 bug：两端不一致，手表永远收不到数据）
 *  - 手机端原来直接分片写原始 JSON，**从不发送换行**；
 *  - 手表端 BlePeripheralService 却按 '\n' 逐行解析 → 永远解析不出任何一条。
 *
 * 现在统一为**长度前缀 + 换行分帧**协议，两端实现严格一致：
 *
 *      SAYM1 <payloadByteLength>\n<payload JSON bytes>
 *
 *  - 手表按字节缓冲，先读首行头拿到长度，再等够 payload 才处理（不依赖写入分包边界）；
 *  - 手机分片写入，片间留间隔；手表收到完整包后通过 ACK 特征值（0000fef3）回执；
 *  - 手机收到 ACK 才算成功；超时未收到则如实报「已发出但未回执」，不谎报成功。
 */
class Dw99SyncManager(private val ctx: Context) {
    private val bleSvcUuid = UUID.fromString("0000fef1-0000-1000-8000-00805f9b34fb")
    private val bleCharUuid = UUID.fromString("0000fef2-0000-1000-8000-00805f9b34fb")
    private val bleAckUuid = UUID.fromString("0000fef3-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val handler = Handler(Looper.getMainLooper())

    /** 单片写入大小：不协商 MTU 时有效载荷 20 字节，保守取 18 */
    private val chunkSize = 18
    private val chunkDelayMs = 25L

    private fun adapter() =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * 推送一个**已经组装好的** JSON 字符串给手表（新架构：WiFi 为主、蓝牙为辅）。
     * 协议帧由本类统一加（SAYM1 <len>\n<payload>），调用方只管业务 JSON。
     */
    fun pushRaw(payloadJson: String, onDone: (Boolean, String) -> Unit) {
        val mac = Prefs.watchMac(ctx)
        if (mac.isNullOrBlank()) { onDone(false, "还没绑手表：请到 设置 → 手表配对 里绑定 DW99"); return }
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) { onDone(false, "蓝牙未开启"); return }
        if (!hasConnectPermission()) { onDone(false, "缺少 BLUETOOTH_CONNECT 权限（到系统设置里给「说一嘴」授权）"); return }
        val device: BluetoothDevice? = try { adapter.getRemoteDevice(mac) } catch (e: Exception) { null }
        if (device == null) { onDone(false, "找不到已配对手表（MAC 是否正确？）"); return }
        connectAndWrite(device, payloadJson.toByteArray(Charsets.UTF_8), onDone)
    }

    /**
     * 一个同步任务 = 一个 GattSession，状态全部收在会话对象里，
     * 避免回调里出现悬空/竞争的可变状态。
     */
    private inner class GattSession(
        private val payload: ByteArray,
        private val onDone: (Boolean, String) -> Unit
    ) {
        private var gatt: BluetoothGatt? = null
        private var finished = false
        private var ackReceived = false
        private var index = 0
        private lateinit var chunks: List<ByteArray>

        fun start(device: BluetoothDevice) {
            // 长度前缀 + 换行分帧（与手表端 BlePeripheralService 完全一致）
            val frame = ("SAYM1 " + payload.size + "\n").toByteArray(Charsets.UTF_8) + payload
            chunks = frame.toList().chunked(chunkSize).map { it.toByteArray() }
            gatt = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                else device.connectGatt(ctx, false, cb)
            } catch (e: SecurityException) { null }
            if (gatt == null) finish(false, "GATT 连接建立失败（权限或系统限制）")
            handler.postDelayed({ finish(false, "同步超时（15 秒），请确认手表端 App 已启动且未被系统杀后台") }, 15000)
        }

        fun finish(ok: Boolean, msg: String) {
            if (finished) return
            finished = true
            try { gatt?.close() } catch (_: Exception) {}
            onDone(ok, msg)
        }

        private val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    handler.post { try { g.discoverServices() } catch (_: Exception) {} }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!finished) finish(false, "连接已断开（status=$status）")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(bleSvcUuid) ?: run {
                    finish(false, "手表未注册同步服务（UUID 0000fef1），请确认手表端 App 已启动"); return
                }
                val ch = svc.getCharacteristic(bleCharUuid) ?: run {
                    finish(false, "手表未提供数据特征值（UUID 0000fef2）"); return
                }
                val ackCh = svc.getCharacteristic(bleAckUuid)
                if (ackCh != null) subscribeAck(g, ackCh)
                handler.post { writeNext(g, ch) }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == bleAckUuid) {
                    ackReceived = true
                    finish(true, "已同步到手表（${payload.size} 字节，已回执）")
                }
            }
        }

        private fun subscribeAck(g: BluetoothGatt, ackCh: BluetoothGattCharacteristic) {
            try {
                g.setCharacteristicNotification(ackCh, true)
                ackCh.getDescriptor(cccdUuid)?.let { d ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(d)
                    }
                }
            } catch (_: Exception) { /* 手表不支持回执时退化为「写成功即成功」 */ }
        }

        private fun writeNext(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (finished) return
            if (index >= chunks.size) {
                if (g.getService(bleSvcUuid)?.getCharacteristic(bleAckUuid) == null) {
                    finish(true, "已推送到手表（${payload.size} 字节，手表未启用回执）")
                } else {
                    handler.postDelayed({
                        if (!ackReceived) finish(false, "数据已发出但手表未回执，请确认手表端 App 是否在运行")
                    }, 3000)
                }
                return
            }
            val chunk = chunks[index]
            val ok = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = chunk
                    @Suppress("DEPRECATION")
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
            } catch (e: Exception) { false }
            if (!ok) { finish(false, "写特征失败（第 ${index + 1}/${chunks.size} 片）"); return }
            index++
            handler.postDelayed({ writeNext(g, ch) }, chunkDelayMs)
        }
    }

    private fun connectAndWrite(device: BluetoothDevice, payload: ByteArray, onDone: (Boolean, String) -> Unit) {
        GattSession(payload, onDone).start(device)
    }
}
