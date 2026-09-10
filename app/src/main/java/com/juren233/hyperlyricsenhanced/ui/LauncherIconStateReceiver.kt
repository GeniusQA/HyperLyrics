package com.juren233.hyperlyricsenhanced.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.juren233.hyperlyricsenhanced.common.UIConstants

/**
 * 应用更新完成后自愈桌面入口状态：
 * 「隐藏桌面图标」依赖双 alias 互斥（LauncherAlias=桌面图标 / LauncherEntry=无图标入口），
 * 升级安装会保留组件状态但新 alias 保持 manifest 默认值，可能出现双入口同时禁用导致
 * LSPosed 模块详情打开按钮失效。此处按用户持久化意图重新应用组件状态。
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
                pm.setComponentEnabledSetting(
                    component("ui.LauncherEntry"),
                    if (hidden) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}
