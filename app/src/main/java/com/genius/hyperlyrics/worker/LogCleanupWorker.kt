package com.genius.hyperlyrics.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.genius.hyperlyrics.common.UIConstants
import com.genius.hyperlyrics.utils.LogManager

/**
 * 后台定时清理应用日志与模块日志的 Worker。
 *
 * 执行时会同时清空 [LogManager] 维护的应用日志文件，以及 LSPosed 模块日志
 * （/data/adb/lspd/log/modules*.log），并记录最后一次清理时间。
 */
class LogCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        LogManager.clearAllLogs(context, LogManager.TRIGGER_SCHEDULED)
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(UIConstants.KEY_LOG_LAST_CLEANUP_TIME, System.currentTimeMillis())
            .apply()
        // 临时测试：短周期任务是一次性的，执行完按同一间隔续期下一次。
        val testMinutes = prefs.getInt(UIConstants.KEY_LOG_AUTO_CLEANUP_TEST_MINUTES, 0)
        if (testMinutes > 0) {
            LogCleanupScheduler.scheduleTestMinutes(context, testMinutes)
        }
        return Result.success()
    }
}
