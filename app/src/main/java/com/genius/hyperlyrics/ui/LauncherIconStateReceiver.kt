package com.genius.hyperlyrics.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.genius.hyperlyrics.common.UIConstants

/**
 * 应用更新完成后自愈桌面入口状态：
 * 「隐藏桌面图标」= 禁用 LauncherAlias（唯一的 LAUNCHER 入口，图标与点击热区一起消失），
 * 升级安装会保留组件状态，此处按用户持久化意图重新应用。
 * 模块入口由 MainActivity 的 LSPosed MODULE_SETTINGS category 提供，不依赖 LAUNCHER 组件。
 */
class LauncherIconStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val hidden = prefs.getBoolean(
            UIConstants.KEY_HIDE_LAUNCHER_ICON,
            UIConstants.DEFAULT_HIDE_LAUNCHER_ICON
        )
        applyLauncherState(context, hidden)
    }

    companion object {
        fun applyLauncherState(context: Context, hidden: Boolean) {
            runCatching {
                val pm = context.packageManager
                fun component(name: String) = ComponentName(context, "${context.packageName}.$name")
                pm.setComponentEnabledSetting(
                    component("ui.LauncherAlias"),
                    if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}
