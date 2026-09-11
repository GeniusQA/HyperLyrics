package com.genius.hyperlyrics.root

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.genius.hyperlyrics.root.island.renderer.BaseIslandRenderer
import com.genius.hyperlyrics.root.mediacard.notification.NotificationMediaAodLyricHooker
import com.genius.hyperlyrics.root.utils.HookLogger
import com.genius.hyperlyrics.root.utils.MediaCardDiagnosticLogger

internal object SystemUiScreenStateMonitor {
    private const val TAG = "SystemUiScreenState"

    private var registeredApp: Application? = null
    private var receiver: BroadcastReceiver? = null

    fun initialize(app: Application) {
        if (registeredApp === app && receiver != null) return
        cleanup()

        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        MediaCardDiagnosticLogger.log(
                            stage = "screen",
                            event = "screen_on",
                            details = "action=${intent.action},source=${MediaCardDiagnosticLogger.identity(context)}",
                        )
                        HookLogger.d(TAG, "收到亮屏事件，刷新超级岛状态")
                        BaseIslandRenderer.onScreenInteractive()
                        NotificationMediaAodLyricHooker.onScreenInteractive()
                    }

                    Intent.ACTION_SCREEN_OFF -> {
                        MediaCardDiagnosticLogger.log(
                            stage = "screen",
                            event = "screen_off",
                            details = "action=${intent.action},source=${MediaCardDiagnosticLogger.identity(context)}",
                        )
                        HookLogger.d(TAG, "收到息屏事件，取消超级岛亮屏恢复任务")
                        BaseIslandRenderer.onScreenNonInteractive()
                        NotificationMediaAodLyricHooker.onScreenNonInteractive()
                    }

                    Intent.ACTION_USER_PRESENT -> {
                        MediaCardDiagnosticLogger.log(
                            stage = "screen",
                            event = "user_present",
                            details = "action=${intent.action},source=${MediaCardDiagnosticLogger.identity(context)}",
                        )
                        HookLogger.d(TAG, "收到解锁事件，隐藏锁屏歌词覆盖层")
                        NotificationMediaAodLyricHooker.hideLockScreenOverlays()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        app.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        registeredApp = app
        receiver = screenReceiver
    }

    fun cleanup() {
        val app = registeredApp
        val activeReceiver = receiver
        if (app != null && activeReceiver != null) {
            runCatching { app.unregisterReceiver(activeReceiver) }
                .onFailure { HookLogger.w(TAG, "注销屏幕状态监听失败", it) }
        }
        registeredApp = null
        receiver = null
    }
}
