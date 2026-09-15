package com.genius.hyperlyrics.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.common.UIConstants

/**
 * 接收 hook（SystemUI）上报的「歌词内容 / 翻译」实际来源，并写入 App 本地偏好。
 *
 * 之所以用广播而不是 LSPosed 远程偏好：远程偏好只有「App 写 → hook 读」方向可靠，
 * hook 侧写入不会持久化，App 读不到（实测全设备无该键）。
 *
 * 写入 App 自己的 [UIConstants.PREF_NAME]，MetaData 页面直接读本地偏好即可。
 */
class LyricOriginReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RootConstants.ACTION_LYRIC_ORIGIN_CHANGED) return
        val contentOrigin = intent.getStringExtra(RootConstants.EXTRA_LYRIC_CONTENT_ORIGIN)
            ?: return
        val editor = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(RootConstants.KEY_HOOK_LYRIC_CONTENT_ORIGIN, contentOrigin)
            .putString(
                RootConstants.KEY_HOOK_TRANSLATION_ORIGIN,
                intent.getStringExtra(RootConstants.EXTRA_LYRIC_TRANSLATION_ORIGIN)
                    ?: RootConstants.TRANSLATION_ORIGIN_NONE,
            )
        intent.getStringExtra(RootConstants.EXTRA_LYRIC_PROVIDER_PACKAGE)
            ?.takeIf { it.isNotBlank() }
            ?.let { editor.putString(RootConstants.KEY_HOOK_CURRENT_LYRIC_PROVIDER, it) }
        editor.apply()
    }
}
