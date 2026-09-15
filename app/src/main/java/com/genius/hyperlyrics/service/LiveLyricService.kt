package com.genius.hyperlyrics.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import com.genius.hyperlyrics.common.lyric.LyricSplitter
import com.genius.hyperlyrics.lyric.ConfigRepository
import com.genius.hyperlyrics.lyric.DynamicLyricData
import com.genius.hyperlyrics.service.source.AppLyricSink
import com.genius.hyperlyrics.service.source.MetadataSource
import com.genius.hyperlyrics.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LiveLyricService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var metadataSource: MetadataSource
    private lateinit var appLyricSink: AppLyricSink
    private lateinit var notificationPresenter: NotificationPresenter
    private var screenStateReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this

        val textPaint = createTextPaint()
        val lyricSplitter = LyricSplitter(textPaint, resources.displayMetrics)

        notificationPresenter = NotificationPresenter(this, serviceScope, lyricSplitter)
        notificationPresenter.register()

        ConfigRepository.initWhitelist(this)

        val componentName = ComponentName(this, LiveLyricService::class.java)
        metadataSource = MetadataSource(
            context = this,
            scope = serviceScope,
            componentName = componentName,
            onMediaSessionAccessLost = {
                LogManager.w("LiveLyricService", "媒体会话访问失效，正在自动重绑通知监听服务")
                ensureListenerBound(this)
            },
        )
        appLyricSink = AppLyricSink(this, serviceScope, notificationPresenter)

        appLyricSink.startCollecting(metadataSource.lyricUpdateFlow, metadataSource.newSongFlow)

        serviceScope.launch {
            combine(
                DynamicLyricData.musicState,
                DynamicLyricData.progressFlow.onStart { emit(0f) },
                ConfigRepository.whitelistState
            ) { state, _, _ -> state }.collect { state ->
                notificationPresenter.updateState(state, force = false)
            }
        }

        // 亮灭屏刷新改由系统广播驱动：原先用 500ms 常驻轮询检测亮灭屏，
        // 每次都取 DisplayManager/PowerManager，服务常驻期间等于永久 2Hz 唤醒。
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_SCREEN_OFF,
                    Intent.ACTION_USER_PRESENT,
                    -> notificationPresenter.refreshClassicAodSongInfo()
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        metadataSource.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance === this) {
            activeInstance = null
        }
        appLyricSink.stop()
        screenStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenStateReceiver = null
        metadataSource.disconnect()
        notificationPresenter.unregister()
        notificationPresenter.clearNotifications()
        serviceScope.cancel()
    }

    private fun createTextPaint(): Paint {
        val islandBitmapHeight = 128
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
            textSize = 100f
            val rawHeight = fontMetrics.descent - fontMetrics.ascent
            textSize = 100f * (islandBitmapHeight.toFloat() / rawHeight)
        }
    }

    companion object {
        @Volatile
        private var activeInstance: LiveLyricService? = null

        fun requestClassicAodRefresh(context: Context) {
            val service = activeInstance
            if (service == null) {
                ensureListenerBound(context)
                return
            }
            service.serviceScope.launch {
                LogManager.d("LiveLyricService", "收到 SystemUI AOD 焦点通知刷新请求")
                service.metadataSource.refreshNow()
                service.notificationPresenter.refreshClassicAodSongInfo()
            }
        }

        fun ensureListenerBound(context: Context) {
            LogManager.d("LiveLyricService", "正在尝试静默重连 NotificationListenerService")
            try {
                val pm = context.packageManager
                val cn = ComponentName(context, LiveLyricService::class.java)
                pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                requestRebind(cn)
            } catch (e: Exception) {
                LogManager.e("LiveLyricService", "静默重连失败", e)
            }
        }
    }
}
