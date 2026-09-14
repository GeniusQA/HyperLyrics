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
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(UIConstants.KEY_LOG_LAST_CLEANUP_TIME, System.currentTimeMillis())
            .apply()
        return Result.success()
    }
}
