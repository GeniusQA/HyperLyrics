package com.genius.hyperlyrics.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.genius.hyperlyrics.common.UIConstants
import java.util.concurrent.TimeUnit

/**
 * 日志自动清理任务调度器。
 */
object LogCleanupScheduler {

    private const val WORK_NAME = "log_cleanup_work"

    /** 旧版本「每 5 分钟（测试）」遗留的任务名，仅用于收尾清理。 */
    private const val LEGACY_TEST_WORK_NAME = "log_cleanup_test_work"

    /**
     * 根据当前保存的周期设置重新调度或取消任务。
     */
    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val intervalHours = prefs.getInt(UIConstants.KEY_LOG_AUTO_CLEANUP_INTERVAL, 0)
        schedule(context, intervalHours)
    }

    /**
     * 按指定周期（小时）调度任务。
     *
     * @param intervalHours 0 表示取消任务；其他值表示每隔多少小时执行一次。
     */
    fun schedule(context: Context, intervalHours: Int) {
        val workManager = WorkManager.getInstance(context)
        if (intervalHours <= 0) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<LogCleanupWorker>(
            intervalHours.toLong(),
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * 清理旧版本「每 5 分钟（测试）」遗留：取消测试任务并删除标记。
     *
     * 该临时测试项已从 UI 与调度器移除，这里只做一次性收尾，
     * 避免旧版本装过测试项的设备残留一个 5 分钟的一次性任务。
     */
    fun cleanupLegacyTestArtifacts(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_TEST_WORK_NAME)
        }
        runCatching {
            context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(UIConstants.KEY_LOG_AUTO_CLEANUP_TEST_MINUTES)
                .apply()
        }
    }
}
