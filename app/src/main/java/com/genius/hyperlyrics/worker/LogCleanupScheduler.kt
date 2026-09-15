package com.genius.hyperlyrics.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.genius.hyperlyrics.common.UIConstants
import java.util.concurrent.TimeUnit

/**
 * 日志自动清理任务调度器。
 */
object LogCleanupScheduler {

    private const val WORK_NAME = "log_cleanup_work"

    /** 临时测试用的短周期任务名（正式发布前应连同 [scheduleTestMinutes] 一起移除）。 */
    private const val TEST_WORK_NAME = "log_cleanup_test_work"

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
     * 临时测试：按「分钟」间隔触发日志清理。
     *
     * WorkManager 的周期任务下限是 15 分钟，因此这里用「一次性任务 + 执行完自续期」实现，
     * 以便用 5 分钟这种短间隔快速验证「定时清理」链路。
     * 切回正式周期或关闭时，需调用 [scheduleTestMinutes](context, 0) 取消。
     *
     * @param minutes 0 表示取消测试任务。
     */
    fun scheduleTestMinutes(context: Context, minutes: Int) {
        val workManager = WorkManager.getInstance(context)
        if (minutes <= 0) {
            workManager.cancelUniqueWork(TEST_WORK_NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<LogCleanupWorker>()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(
            TEST_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
