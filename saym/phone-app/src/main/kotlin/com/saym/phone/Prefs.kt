package com.saym.phone

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 轻量本地偏好存储。
 *
 * 新架构下的职责变化：
 *  - **不再**保存"后端地址"（手机自己就是数据源，不再连任何后端）；
 *  - **不再**保存主密码（配置中心改为本地 + 只防误改，见 Prefs.configUnlocked）；
 *  - 新增：手表配对令牌、手表最后连通时间。
 */
object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("saym", Context.MODE_PRIVATE)

    /** 本机设备 ID（首次启动生成，只用于标识数据来源，不做账号体系，决策 D4） */
    fun deviceId(c: Context): String {
        sp(c).getString("device_id", null)?.let { return it }
        val id = "phone-" + UUID.randomUUID().toString().take(13)
        sp(c).edit().putString("device_id", id).apply()
        return id
    }

    fun deviceName(c: Context): String =
        sp(c).getString("device_name", null)
            ?: (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim()

    // ============ 手表配对（F4.2） ============

    /** 手表蓝牙地址（配对后保存） */
    fun watchMac(c: Context): String? = sp(c).getString("watch_mac", null)
    fun setWatchMac(c: Context, mac: String?) {
        sp(c).edit().putString("watch_mac", mac).apply()
    }

    /**
     * 局域网配对令牌：手机生成，手表必须带上它才能拉数据 / 回写完成状态。
     * 为空表示还没配对过；手表端也会存一份。
     */
    fun watchToken(c: Context): String? = sp(c).getString("watch_token", null)

    fun ensureWatchToken(c: Context): String {
        sp(c).getString("watch_token", null)?.let { return it }
        val t = UUID.randomUUID().toString().replace("-", "").take(24)
        sp(c).edit().putString("watch_token", t).apply()
        return t
    }

    /** 重新配对：作废旧令牌（手表必须重新配对才能再同步） */
    fun resetWatchToken(c: Context) {
        sp(c).edit().remove("watch_token").remove("watch_last_sync").apply()
    }

    /** 手表最后一次来同步的时间（设置页展示，便于确认"到底有没有同步过"） */
    fun watchLastSyncAt(c: Context): String? = sp(c).getString("watch_last_sync", null)
    fun setWatchLastSyncAt(c: Context, v: String) {
        sp(c).edit().putString("watch_last_sync", v).apply()
    }

    /** 手填的手表地址（mDNS 发现失败时的备用方案） */
    fun watchManualHost(c: Context): String? = sp(c).getString("watch_manual_host", null)
    fun setWatchManualHost(c: Context, v: String?) {
        sp(c).edit().putString("watch_manual_host", v).apply()
    }

    // ============ 配置中心防误改 ============

    /** 「解锁编辑」开关：默认关闭，避免误改模型配置（你说主密码只要防误改就够） */
    fun configUnlocked(c: Context): Boolean = sp(c).getBoolean("config_unlocked", false)
    fun setConfigUnlocked(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("config_unlocked", v).apply()
    }

    // ============ 每日推送补推标记 ============

    /** 记录"今天已经补推过"，避免每次切回 App 都弹一次（F5.1 兜底） */
    fun lastPushedDate(c: Context): String? = sp(c).getString("last_pushed_date", null)
    fun setLastPushedDate(c: Context, d: String) {
        sp(c).edit().putString("last_pushed_date", d).apply()
    }
}
