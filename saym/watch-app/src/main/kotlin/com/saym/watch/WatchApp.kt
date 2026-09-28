package com.saym.watch

import android.app.Application

/**
 * 手表端 Application。
 *
 * ⚠️ 必须在 AndroidManifest 的 `<application>` 上写 `android:name=".WatchApp"`，
 * 否则这个类根本不会被系统创建（手机端就踩过这个坑，见 手机端实装说明.md）。
 *
 * 这里只做最轻量的初始化：
 *  - 初始化诊断日志（后面所有代码都要写日志）
 *  - 建通知渠道
 * **不**启动前台服务：Android 12+ 禁止在没有界面时启动前台服务（手机端也踩过这个坑）；
 * 常驻通知服务由 MainActivity 启动。
 */
class WatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DLog.init(this)
        DLog.i("WatchApp 启动：Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MODEL}")
        WatchNotify.ensureChannels(this)
    }
}
