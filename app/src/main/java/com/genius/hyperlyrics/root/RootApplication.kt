package com.genius.hyperlyrics.root

import android.app.Application
import android.content.Context
import android.content.Intent
import com.genius.hyperlyrics.BuildConfig
import com.genius.hyperlyrics.common.LogLevelPolicy
import com.genius.hyperlyrics.common.PreferenceDiagnostics
import com.genius.hyperlyrics.common.PrefsBridge
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.common.UIConstants
import com.genius.hyperlyrics.provider.OfficialProviderScopeManager
import com.genius.hyperlyrics.ui.utils.AppUtils
import com.genius.hyperlyrics.ui.utils.LocaleUtils
import com.genius.hyperlyrics.utils.LogManager
import com.genius.hyperlyrics.worker.LogCleanupScheduler
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RootApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        LocaleUtils.clearLegacyPlatformLocale(this)
        AppUtils.initPredictiveBackGesture(this)
        applyBuildDefaultLogLevel()
        LogManager.init(this)
        PrefsBridge.init(this)
        appContext = this
        // 收尾：清理旧版本「每 5 分钟（测试）」遗留的任务与标记。
        runCatching { LogCleanupScheduler.cleanupLegacyTestArtifacts(this) }

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                _xposedServiceBound.value = true
                LogManager.i("PrefsBridge", "xposed_service_bound")
                syncAllPreferences(this@RootApplication)
                OfficialProviderScopeManager.requestConfiguredScopes(service)
                refreshXposedScope()
            }
            override fun onServiceDied(service: XposedService) {
                xposedService = null
                _xposedServiceBound.value = false
                LogManager.w("PrefsBridge", "xposed_service_died")
                _xposedScope.value = emptySet()
                OfficialProviderScopeManager.onServiceDied()
            }
        })
    }

    private fun applyBuildDefaultLogLevel() {
        val prefs = getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val currentBuildKind = LogLevelPolicy.buildKind(BuildConfig.DEBUG)
        if (prefs.getString(UIConstants.KEY_LOG_LEVEL_BUILD_KIND, null) == currentBuildKind) {
            return
        }
        prefs.edit()
            .putInt(
                UIConstants.KEY_LOG_LEVEL,
                LogLevelPolicy.defaultLevel(BuildConfig.DEBUG),
            )
            .putString(UIConstants.KEY_LOG_LEVEL_BUILD_KIND, currentBuildKind)
            .commit()
    }

    companion object {
        
        @JvmStatic
        var xposedService: XposedService? = null
            private set

        @JvmStatic
        fun syncPreference(group: String, key: String, value: Any?) {
            if (BuildConfig.DEBUG) {
                LogManager.i(
                    "PrefsBridge",
                    "sync_request group=$group key=$key " +
                        "type=${PreferenceDiagnostics.typeName(value)} " +
                        "value=${PreferenceDiagnostics.formatValue(key, value)}",
                )
            }
            val remotePrefs = try {
                xposedService?.getRemotePreferences(group)
            } catch (error: Exception) {
                LogManager.w("PrefsBridge", "remote_preferences_failed group=$group", error)
                null
            }
            if (remotePrefs == null) {
                LogManager.w("PrefsBridge", "sync_skipped reason=remote_unavailable group=$group key=$key")
                return
            }

            remotePrefs.edit().apply {
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                }
                apply()
            }
            if (BuildConfig.DEBUG) {
                val readBack = runCatching { remotePrefs.all[key] }
                    .getOrElse { error -> "<readback_failed:${error.javaClass.simpleName}>" }
                LogManager.i(
                    "PrefsBridge",
                    "sync_queued group=$group key=$key " +
                        "remote_readback=${PreferenceDiagnostics.formatValue(key, readBack)}",
                )
            }
            broadcastPreferenceChange(group, key, value)
        }

        private fun broadcastPreferenceChange(group: String, key: String, value: Any?) {
            if (group != UIConstants.PREF_NAME) return
            if (!LivePreferenceRefreshPolicy.contains(key)) return
            val intent = Intent(RootConstants.ACTION_REMOTE_PREFERENCE_CHANGED)
                .setPackage("com.android.systemui")
                .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_GROUP, group)
                .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_KEY, key)
            when (value) {
                null -> intent.putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "clear")
                is Boolean -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "boolean")
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_BOOLEAN, value)
                is Int -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "int")
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_INT, value)
                is Long -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "long")
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_LONG, value)
                is Float -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "float")
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_FLOAT, value)
                is String -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "string")
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_STRING, value)
                is Set<*> -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "string_set")
                    .putStringArrayListExtra(
                        RootConstants.EXTRA_REMOTE_PREFERENCE_STRING_SET,
                        ArrayList(value.filterIsInstance<String>()),
                    )
                else -> return
            }
            runCatching { appContext?.sendBroadcast(intent) }
                .onFailure { error ->
                    LogManager.w("PrefsBridge", "preference_broadcast_failed key=$key", error)
                }
        }

        @JvmStatic
        private fun syncAllPreferences(context: Context) {
            val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, MODE_PRIVATE)
            val allEntries = prefs.all
            LogManager.i("PrefsBridge", "sync_all_begin count=${allEntries.size}")
            if (allEntries.isEmpty()) {
                LogManager.i("PrefsBridge", "sync_all_end count=0")
                return
            }

            allEntries.forEach { (key, value) ->
                syncPreference(UIConstants.PREF_NAME, key, value)
            }
            LogManager.i("PrefsBridge", "sync_all_end count=${allEntries.size}")
        }

        @JvmStatic
        fun syncAllPreferences() {
            val context = appContext ?: return
            syncAllPreferences(context)
        }

        @JvmStatic
        internal fun currentContext(): Context? = appContext

        private var appContext: Context? = null

        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _xposedScope = MutableStateFlow<Set<String>>(emptySet())

        /** 当前 LSPosed 作用域（HyperLyrics 已勾选的应用包名）。 */
        @JvmStatic
        val xposedScope: StateFlow<Set<String>> = _xposedScope.asStateFlow()

        // LSPosed 服务绑定状态的可观察镜像：xposedService 本体是普通静态变量、
        // 由 onServiceBind/onServiceDied 异步赋值，Compose 直接读它会错过绑定时机
        // （首帧读到 null → 相关开关误置灰，且后续绑定不触发重组）。
        // UI 层请收集 [xposedServiceBound] 以获得响应式更新。
        private val _xposedServiceBound = MutableStateFlow(xposedService != null)

        /** LSPosed 服务是否已绑定（组合安全的响应式状态）。 */
        @JvmStatic
        val xposedServiceBound: StateFlow<Boolean> = _xposedServiceBound.asStateFlow()

        /**
         * 重新读取 LSPosed 作用域；服务未绑定或读取失败时置空。
         * `XposedService.scope` 是跨进程调用，放到 IO 线程执行。
         */
        @JvmStatic
        fun refreshXposedScope() {
            val service = xposedService
            if (service == null) {
                _xposedScope.value = emptySet()
                return
            }
            serviceScope.launch {
                val scope = runCatching { service.scope.toSet() }
                    .onFailure { error ->
                        LogManager.w("PrefsBridge", "xposed_scope_read_failed", error)
                    }
                    .getOrDefault(emptySet<String>())
                _xposedScope.value = scope
            }
        }
    }
}
