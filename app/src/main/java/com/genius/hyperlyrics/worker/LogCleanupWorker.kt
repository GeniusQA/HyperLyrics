package com.genius.hyperlyrics.worker

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.genius.hyperlyrics.common.UIConstants
import com.genius.hyperlyrics.utils.LogManager
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台定时清理应用日志与模块日志的 Worker。
 *
 * 执行时会同时清空 [LogManager] 维护的应用日志文件，以及 LSPosed 模块日志
 * （/data/adb/lspd/log/modules*.log），并记录最后一次清理时间。
 */
class LogCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        // 幂等护栏：个别 ROM 会用多个 JobScheduler 作业并发启动同一逻辑任务
        // （dumpsys 里实测同一时刻 START 两个 jobId），清理动作（su 清 LSPosed 日志）
        // 只需执行一次；由 LogManager 的记录去重兜底历史不重复。
        val now = SystemClock.elapsedRealtime()
        val last = lastWorkerStartMs.getAndSet(now)
        if (last != 0L && now - last < CONCURRENT_START_WINDOW_MS) {
            return Result.success()
        }
        LogManager.clearAllLogs(context, LogManager.TRIGGER_SCHEDULED)
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(UIConstants.KEY_LOG_LAST_CLEANUP_TIME, System.currentTimeMillis())
            .apply()
        return Result.success()
    }

    companion object {
        /** 并发启动去重窗口：正常周期间隔（小时级）远大于此值。 */
        private const val CONCURRENT_START_WINDOW_MS = 60_000L
        private val lastWorkerStartMs = AtomicLong(0L)
    }
}
