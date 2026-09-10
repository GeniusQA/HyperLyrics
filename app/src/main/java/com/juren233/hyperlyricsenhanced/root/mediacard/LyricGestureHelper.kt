package com.juren233.hyperlyricsenhanced.root.mediacard

import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

/**
 * 歌词区手势播放控制：原生三按钮隐藏后，由歌词文本行承担交互。
 *
 * - 长按任意歌词行 = 播放/暂停
 * - 双击歌词行左半区 = 上一首；右半区 = 下一首
 *
 * 手势命令直接经 MediaSession transport 下发，与原生按钮等价。未命中的手势
 * 不做额外消费：横滑关闭卡片由通知栈父级的 onInterceptTouchEvent 接管，即使
 * 本视图已消费 DOWN，父级仍可中途拦截并下发 ACTION_CANCEL，滑动关闭不受影响。
 */
object LyricGestureHelper {
    private const val TAG = "LyricGestureHelper"

    fun attach(vararg lyricRows: View) {
        val anchor = lyricRows.firstOrNull() ?: return
        val context: Context = anchor.context ?: return
        val transport = MediaTransportController(context)
        lyricRows.forEach { row ->
            // View.dispatchTouchEvent 只在 CLICKABLE/LONG_CLICKABLE 视图上回调
            // OnTouchListener，缺失标志会导致手势不生效、点击穿透到卡片 clickIntent。
            row.isClickable = true
            row.isLongClickable = true
            val detector = GestureDetector(
                row.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(event: MotionEvent): Boolean {
                        val next = event.x >= row.width / 2f
                        transport.skip(next)
                        return true
                    }

                    override fun onLongPress(event: MotionEvent) {
                        transport.togglePlayPause()
                    }
                },
            )
            row.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
            }
        }
        HookLogger.i(TAG, "歌词区手势已挂载: rows=${lyricRows.size}")
    }

    private class MediaTransportController(private val context: Context) {
        fun togglePlayPause() {
            val target = resolveController() ?: return
            runCatching {
                val playing = target.playbackState?.state == PlaybackState.STATE_PLAYING
                if (playing) {
                    target.transportControls.pause()
                } else {
                    target.transportControls.play()
                }
            }.onFailure { HookLogger.w(TAG, "手势切换播放状态失败: ${it.message}") }
        }

        fun skip(next: Boolean) {
            val target = resolveController() ?: return
            runCatching {
                if (next) {
                    target.transportControls.skipToNext()
                } else {
                    target.transportControls.skipToPrevious()
                }
            }.onFailure { HookLogger.w(TAG, "手势切歌失败: next=$next, ${it.message}") }
        }

        /**
         * SystemUI 持有 MEDIA_CONTENT_CONTROL，可无监听组件直接枚举活动会话。
         * 选择顺序：当前歌词来源包名 → 正在播放的会话 → 任意活动会话。
         */
        private fun resolveController(): android.media.session.MediaController? {
            val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
            val sessions = runCatching { manager.getActiveSessions(null) }
                .onFailure { HookLogger.w(TAG, "枚举媒体会话失败: ${it.message}") }
                .getOrDefault(emptyList())
            if (sessions.isEmpty()) return null
            val preferred = LyriconDataBridge.currentLyricPackageName
                ?: LyriconDataBridge.activePackageName
            return sessions.firstOrNull { it.packageName == preferred }
                ?: sessions.firstOrNull {
                    it.playbackState?.state == PlaybackState.STATE_PLAYING
                }
                ?: sessions.firstOrNull()
        }
    }
}
