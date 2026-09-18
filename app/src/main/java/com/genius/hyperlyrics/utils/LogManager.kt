package com.genius.hyperlyrics.utils

import android.content.Context
import android.util.Log
import com.genius.hyperlyrics.BuildConfig
import com.genius.hyperlyrics.R
import com.genius.hyperlyrics.common.HyperLogger
import com.genius.hyperlyrics.common.LogLevelPolicy
import com.genius.hyperlyrics.ui.page.log.LogEntry
import com.genius.hyperlyrics.common.UIConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.regex.Pattern
import kotlin.concurrent.read
import kotlin.concurrent.write

object LogManager : HyperLogger {
    private const val LOG_FILE_NAME = "app_logs.log"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L // 2MB
    private const val MAX_DISPLAY_ENTRY_CHARS = 128 * 1024
    private const val MAX_DISPLAY_TOTAL_CHARS = 4 * 1024 * 1024
    private const val MAX_DISPLAY_ENTRIES = 2_000
    private const val MAX_XPOSED_SOURCE_BYTES = 16 * 1024 * 1024L
    private const val TRUNCATION_MARKER = "\n... [truncated]"

    private val lock = ReentrantReadWriteLock()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null
    private var appContext: Context? = null

    private class BoundedLogBuffer {
        private data class RetainedEntry(
            val entry: LogEntry,
            val retentionKey: String,
            val sequence: Long
        )

        private val entries = PriorityQueue<RetainedEntry>(
            compareBy<RetainedEntry> { it.retentionKey }.thenBy { it.sequence }
        )
        private var retainedChars = 0
        private var nextSequence = 0L

        fun add(entry: LogEntry, retentionKey: String = entry.timestamp) {
            val entryChars = entry.message.length + entry.rawLog.length
            entries.add(RetainedEntry(entry, retentionKey, nextSequence++))
            retainedChars += entryChars
            while (entries.size > MAX_DISPLAY_ENTRIES || retainedChars > MAX_DISPLAY_TOTAL_CHARS) {
                val removed = entries.remove().entry
                retainedChars -= removed.message.length + removed.rawLog.length
            }
        }

        fun toList(): List<LogEntry> = entries.map { it.entry }
    }

    private data class XposedLogFiles(
        val directory: String,
        val files: List<String>
    )

    data class CleanupFileResult(
        val path: String,
        val success: Boolean
    )

    data class CleanupRecord(
        val timestamp: Long,
        val trigger: String,
        val files: List<CleanupFileResult>
    ) {
        fun overallSuccess(): Boolean = files.isNotEmpty() && files.all { it.success }
    }

    const val TRIGGER_MANUAL = "manual"
    const val TRIGGER_SCHEDULED = "scheduled"
    private const val MAX_HISTORY_RECORDS = 50

    /**
     * 清理记录去重窗口：同触发器在此间隔内的第二条记录视为同一次执行的重复写入，
     * 不再落盘。背景：WorkManager 在个别 ROM 上会把同一逻辑任务经多个 JobScheduler
     * 作业并发启动（实测同一秒内 START 两个 jobId），导致「清理记录」出现成对重复。
     */
    private const val CLEANUP_RECORD_DEDUPE_WINDOW_MS = 3_000L

    /** 清理记录的读改写锁：saveCleanupRecord 的读取-合并-写入必须原子，否则并发时丢记录。 */
    private val cleanupHistoryLock = Any()

    /** 读取已保存的清理历史。 */
    fun getCleanupHistory(context: Context): List<CleanupRecord> {
        return try {
            val file = File(context.filesDir, "cleanup_history.json")
            if (!file.exists() || file.length() == 0L) return emptyList()
            val json = JSONObject(file.readText())
            val array = json.optJSONArray("records") ?: return emptyList()
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val timestamp = obj.optLong("timestamp", 0L)
                val trigger = obj.optString("trigger", TRIGGER_MANUAL)
                val filesArray = obj.optJSONArray("files") ?: JSONArray()
                val files = (0 until filesArray.length()).mapNotNull { fIndex ->
                    val fObj = filesArray.optJSONObject(fIndex) ?: return@mapNotNull null
                    CleanupFileResult(
                        path = fObj.optString("path", ""),
                        success = fObj.optBoolean("success", false)
                    )
                }
                if (timestamp > 0L) CleanupRecord(timestamp, trigger, files) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 保存一条新的清理记录，并截断到最大条数。 */
    private fun saveCleanupRecord(context: Context, record: CleanupRecord) {
        synchronized(cleanupHistoryLock) {
            val existing = getCleanupHistory(context).toMutableList()
            // 去重：同触发器在窗口内的第二条记录是同一次清理的重复执行（WorkManager
            // 并发启动同一任务所致），跳过写入，避免「清理记录」里出现成对同秒条目。
            val newest = existing.firstOrNull()
            if (newest != null &&
                newest.trigger == record.trigger &&
                kotlin.math.abs(record.timestamp - newest.timestamp) <= CLEANUP_RECORD_DEDUPE_WINDOW_MS
            ) {
                return
            }
            existing.add(0, record)
            while (existing.size > MAX_HISTORY_RECORDS) existing.removeAt(existing.lastIndex)
            try {
                val array = JSONArray()
                existing.forEach { r ->
                    val filesArray = JSONArray()
                    r.files.forEach { f ->
                        filesArray.put(
                            JSONObject().apply {
                                put("path", f.path)
                                put("success", f.success)
                            }
                        )
                    }
                    array.put(
                        JSONObject().apply {
                            put("timestamp", r.timestamp)
                            put("trigger", r.trigger)
                            put("files", filesArray)
                        }
                    )
                }
                val file = File(context.filesDir, "cleanup_history.json")
                file.writeText(JSONObject().put("records", array).toString())
            } catch (_: Exception) {
            }
        }
    }

    private class LogSourceException(message: String) : Exception(message)

    fun init(context: Context) {
        appContext = context.applicationContext
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, LOG_FILE_NAME)
    }

    // ========================= 写入 =========================

    override fun d(tag: String, msg: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, msg)
        if (shouldWrite("D")) writeLog("D", tag, msg)
    }

    override fun i(tag: String, msg: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(tag, msg)
        writeLog("I", tag, msg)
    }

    override fun w(tag: String, msg: String, e: Throwable?) {
        if (!BuildConfig.DEBUG) return
        val fullMsg = if (e != null) "$msg: ${e.message}" else msg
        Log.w(tag, fullMsg, e)
        writeLog("W", tag, fullMsg)
    }

    override fun e(tag: String, msg: String, e: Throwable?) {
        if (!BuildConfig.DEBUG) return
        val fullMsg = if (e != null) "$msg: ${e.message}" else msg
        Log.e(tag, fullMsg, e)
        writeLog("E", tag, fullMsg)
    }

    /** 清空应用日志文件，返回是否成功（文件不存在也视为成功）。 */
    fun clearLogs(): Boolean {
        val file = logFile ?: return false
        return lock.write {
            try {
                if (file.exists()) file.writeText("")
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 清空 LSPosed 模块日志（/data/adb/lspd/log/modules*.log），
     * 返回每个文件的成功/失败状态；无 root 或日志文件不存在时返回空列表。
     */
    fun clearModuleLogs(context: Context): List<CleanupFileResult> {
        val source = try {
            findXposedLogFiles(context)
        } catch (_: Exception) {
            return emptyList()
        }
        val files = source.files
        if (files.isEmpty()) return emptyList()
        val filesArg = files.joinToString(" ") { shellQuote(it) }
        val cmd = "for f in $filesArg; do if > \"\$f\" 2>/dev/null; " +
            "then echo \"OK:\$f\"; else echo \"FAIL:\$f\"; fi; done"
        val results = mutableListOf<CleanupFileResult>()
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("OK:") ->
                            results.add(CleanupFileResult(line.removePrefix("OK:"), true))
                        line.startsWith("FAIL:") ->
                            results.add(CleanupFileResult(line.removePrefix("FAIL:"), false))
                    }
                }
            }
            process.waitFor()
        }.onFailure {
            // root 或 su 不可用：清掉可能的部分结果，统一标记为失败。
            results.clear()
        }
        if (results.isEmpty()) {
            files.forEach { results.add(CleanupFileResult(it, false)) }
        }
        return results
    }

    /**
     * 同时清空应用日志与 LSPosed 模块日志，并记录一条清理历史。
     *
     * 记录里会带上每个实际清理到的文件与成功状态，供「清理记录」弹窗展示。
     */
    fun clearAllLogs(context: Context, trigger: String = TRIGGER_MANUAL) {
        val appSuccess = clearLogs()
        val results = mutableListOf<CleanupFileResult>()
        logFile?.absolutePath?.takeIf { it.isNotBlank() }?.let { appLogPath ->
            results.add(CleanupFileResult(appLogPath, appSuccess))
        }
        results.addAll(clearModuleLogs(context))
        saveCleanupRecord(
            context,
            CleanupRecord(
                timestamp = System.currentTimeMillis(),
                trigger = trigger,
                files = results,
            ),
        )
    }

    private fun shouldWrite(level: String): Boolean {
        val prefs = appContext?.getSharedPreferences(
            UIConstants.PREF_NAME,
            Context.MODE_PRIVATE,
        ) ?: return true
        val storedLevel = prefs.takeIf { it.contains(UIConstants.KEY_LOG_LEVEL) }
            ?.getInt(UIConstants.KEY_LOG_LEVEL, UIConstants.DEFAULT_LOG_LEVEL)
        val storedBuildKind = prefs.getString(UIConstants.KEY_LOG_LEVEL_BUILD_KIND, null)
        val logLevel = LogLevelPolicy.effectiveLevel(
            storedLevel = storedLevel,
            storedBuildKind = storedBuildKind,
            debugBuild = BuildConfig.DEBUG,
        )
        if (logLevel == LogLevelPolicy.LEVEL_DEBUG) return true
        return level == "I" || level == "W" || level == "E" || level == "C"
    }

    private fun writeLog(level: String, tag: String, message: String) {
        val file = logFile ?: return
        lock.write {
            try {
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    trimLogFile(file)
                }
                val timestamp = dateFormat.format(Date())
                val line = "$timestamp $level/$tag: $message\n"
                file.appendText(line)
            } catch (_: Exception) {
            }
        }
    }

    private fun trimLogFile(file: File) {
        try {
            val tailBytes = RandomAccessFile(file, "r").use { input ->
                val start = (input.length() - MAX_LOG_SIZE / 2).coerceAtLeast(0)
                input.seek(start)
                if (start > 0) input.readLine()
                val remaining = (input.length() - input.filePointer).toInt()
                ByteArray(remaining).also(input::readFully)
            }
            file.outputStream().use { it.write(tailBytes) }
        } catch (_: Exception) {
        }
    }

    // ========================= 读取 =========================

    suspend fun readAppLogs(): List<LogEntry> = withContext(Dispatchers.IO) {
        val file = logFile ?: return@withContext emptyList()
        if (!file.exists()) return@withContext emptyList()

        val entries = BoundedLogBuffer()
        val regex = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([DIWEC])/(\S+): (.+)$""")

        lock.read {
            try {
                file.forEachLine { line ->
                    val match = regex.find(line)
                    if (match != null) {
                        val (time, level, tag, msg) = match.destructured
                        entries.add(
                            LogEntry(
                                timestamp = time,
                                level = level,
                                tag = tag,
                                message = truncate(msg, MAX_DISPLAY_ENTRY_CHARS),
                                source = "HyperLyrics",
                                rawLog = truncate(line, MAX_DISPLAY_ENTRY_CHARS)
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
        entries.toList().sortedByDescending { it.timestamp }.mapIndexed { index, entry ->
            entry.copy(id = "app_log_${index}_${entry.timestamp}")
        }
    }

    suspend fun readModuleLogs(context: Context): List<LogEntry> {
        return readXposedLogs(context)
    }

    suspend fun exportLogs(
        context: Context,
        isAppLog: Boolean,
        selectedLevel: String,
        writer: Writer
    ): Int = withContext(Dispatchers.IO) {
        if (isAppLog) {
            exportAppLogs(selectedLevel, writer)
        } else {
            exportXposedLogs(context, selectedLevel, writer)
        }
    }

    private fun exportAppLogs(selectedLevel: String, writer: Writer): Int {
        val file = logFile ?: return 0
        if (!file.exists()) return 0
        return lock.read {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                LogExportStream.copyAppLogs(reader, selectedLevel, writer)
            }
        }
    }

    private fun exportXposedLogs(context: Context, selectedLevel: String, writer: Writer): Int {
        val source = findXposedLogFiles(context)
        val process = Runtime.getRuntime().exec(
            arrayOf("su", "-c", buildXposedSourceCommand(source.files))
        )
        try {
            val exportedEntries = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                LogExportStream.copyXposedLogs(
                    reader = reader,
                    selectedLevel = selectedLevel,
                    cacheDir = context.cacheDir,
                    writer = writer
                )
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException("LSPosed log process exited with code $exitCode")
            }
            return exportedEntries
        } catch (e: Exception) {
            if (process.isAlive) {
                process.destroyForcibly()
                runCatching { process.waitFor() }
            }
            throw e
        }
    }

    private fun findXposedLogFiles(context: Context): XposedLogFiles {
        val logDir = "/data/adb/lspd/log"
        val checkProcess = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "ls -d $logDir 2>/dev/null || echo '__NONE__'")
        )
        val foundDir = BufferedReader(InputStreamReader(checkProcess.inputStream))
            .readLines().firstOrNull { it.isNotBlank() && it != "__NONE__" }
        checkProcess.waitFor()
        if (foundDir == null) throw LogSourceException(context.getString(R.string.lsposed_not_found))

        val listProcess = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "find $foundDir -name 'modules*.log' -type f 2>/dev/null")
        )
        val logFiles = BufferedReader(InputStreamReader(listProcess.inputStream))
            .readLines().filter { it.isNotBlank() }.sorted()
        listProcess.waitFor()
        if (logFiles.isEmpty()) {
            throw LogSourceException(context.getString(R.string.format_log_files_not_found, foundDir))
        }
        return XposedLogFiles(foundDir, logFiles)
    }

    private suspend fun readXposedLogs(context: Context): List<LogEntry> = withContext(Dispatchers.IO) {
        val entries = BoundedLogBuffer()
        var matchedEntryCount = 0
        try {
            val source = findXposedLogFiles(context)
            val dirsArg = source.directory
            val logFiles = source.files

            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", buildXposedSourceCommand(logFiles))
            )

            val timeRegex = Pattern.compile("^(?:\\[\\s*)?(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3})")
            val levelRegex = Pattern.compile("\\s+([VDIWEC])/")
            val moduleTagRegex = Pattern.compile("com\\.genius\\.hyperlyrics[^\\]]*\\][ \\t]*\\[([^\\]]+)]")

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val currentBlock = java.lang.StringBuilder()
                var currentBlockMatches = false
                var currentBlockTruncated = false

                fun appendToCurrentBlock(line: String) {
                    if (line.contains("hyperlyrics", ignoreCase = true)) {
                        currentBlockMatches = true
                    }
                    val separatorChars = if (currentBlock.isNotEmpty()) 1 else 0
                    val remaining = MAX_DISPLAY_ENTRY_CHARS - currentBlock.length - separatorChars
                    if (remaining <= 0) {
                        currentBlockTruncated = true
                        return
                    }
                    if (separatorChars == 1) currentBlock.append('\n')
                    if (line.length <= remaining) {
                        currentBlock.append(line)
                    } else {
                        currentBlock.append(line, 0, remaining)
                        currentBlockTruncated = true
                    }
                }

                fun processCurrentBlock() {
                    if (!currentBlockMatches) return
                    val blockStr = buildString(currentBlock.length + TRUNCATION_MARKER.length) {
                        append(currentBlock)
                        if (currentBlockTruncated) append(TRUNCATION_MARKER)
                    }

                    val firstLine = blockStr.lineSequence().firstOrNull() ?: ""

                    val timeMatcher = timeRegex.matcher(firstLine)
                    val rawTime = if (timeMatcher.find()) timeMatcher.group(1) ?: context.getString(R.string.unknown_time) else context.getString(R.string.unknown_time)
                    val time = if (rawTime.length >= 19 && rawTime != context.getString(R.string.unknown_time)) rawTime.substring(5).replace('T', ' ') else rawTime

                    val levelMatcher = levelRegex.matcher(firstLine)
                    val level = if (levelMatcher.find()) levelMatcher.group(1) ?: "I" else "I"
                    if (level == "V") return

                    // 提取模块标签作为 source，用 matcher.end() 定位消息起始
                    val moduleTagMatcher = moduleTagRegex.matcher(firstLine)
                    val source: String
                    val messageStart: Int
                    if (moduleTagMatcher.find()) {
                        source = moduleTagMatcher.group(1) ?: "HyperLyrics"
                        messageStart = moduleTagMatcher.end()
                    } else {
                        source = "HyperLyrics"
                        val lastBracket = firstLine.lastIndexOf(']')
                        messageStart = if (lastBracket != -1) lastBracket + 1 else 0
                    }

                    val headerMsg = firstLine.substring(messageStart).trim()
                    val remainingLines = if (blockStr.contains('\n')) blockStr.substringAfter('\n') else ""
                    val message = if (remainingLines.isNotBlank()) "$headerMsg\n$remainingLines" else headerMsg

                    entries.add(
                        LogEntry(
                            time,
                            level,
                            context.getString(R.string.tag_lsposed),
                            truncate(message.trim(), MAX_DISPLAY_ENTRY_CHARS),
                            source = source,
                            rawLog = blockStr
                        ),
                        retentionKey = rawTime
                    )
                    matchedEntryCount++
                }

                reader.lineSequence().forEach { line ->
                    if (timeRegex.matcher(line).find()) {
                        if (currentBlock.isNotEmpty()) {
                            processCurrentBlock()
                            currentBlock.clear()
                            currentBlockMatches = false
                            currentBlockTruncated = false
                        }
                    }
                    appendToCurrentBlock(line)
                }
                if (currentBlock.isNotEmpty()) {
                    processCurrentBlock()
                }
            }
            process.waitFor()

            if (matchedEntryCount == 0) {
                val msg = context.getString(R.string.format_logs_scanned_no_match, logFiles.size, dirsArg)
                entries.add(LogEntry("NOW", "I", context.getString(R.string.tag_logger), msg, rawLog = msg))
            }
        } catch (e: LogSourceException) {
            val msg = e.message.orEmpty()
            entries.add(LogEntry("NOW", "W", context.getString(R.string.tag_logger), msg, rawLog = msg))
        } catch (e: Exception) {
            val msg = if (e.message?.contains("Permission denied") == true ||
                          e.message?.contains("su:") == true ||
                          e.message?.contains("not found") == true) {
                context.getString(R.string.no_root_permission)
            } else {
                context.getString(R.string.format_log_read_failed, e.message)
            }
            entries.add(LogEntry("NOW", "E", context.getString(R.string.tag_logger), msg, rawLog = msg))
        }
        val sortedList = entries.toList().sortedByDescending { it.timestamp }
        sortedList.mapIndexed { index, entry ->
            entry.copy(id = "log_${index}_${entry.timestamp}")
        }
    }

    private fun truncate(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val contentChars = (maxChars - TRUNCATION_MARKER.length).coerceAtLeast(0)
        return value.take(contentChars) + TRUNCATION_MARKER
    }

    internal fun buildXposedSourceCommand(files: List<String>): String {
        val orderedFiles = files.sorted().joinToString(" ", transform = ::shellQuote)
        return "cat $orderedFiles 2>/dev/null | tail -c $MAX_XPOSED_SOURCE_BYTES"
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
