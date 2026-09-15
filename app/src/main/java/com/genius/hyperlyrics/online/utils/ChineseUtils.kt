package com.genius.hyperlyrics.online.utils

import android.content.Context
import com.genius.hyperlyrics.BuildConfig
import com.genius.hyperlyrics.utils.LogManager
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 极轻量级繁简转换工具（仅支持 繁体 -> 简体）
 * 基于正向最大匹配算法，避免了引入完整 opencc4j 导致的体积暴增
 */
object ChineseUtils {
    @Volatile
    private var initialized = false
    
    private val phraseMap = HashMap<String, String>()
    private val charMap = HashMap<Char, Char>()
    private var maxPhraseLength = 1
    @Volatile
    private var moduleApkPath: String? = null
    // 记录字典加载失败时所用的 apk 路径：同一路径下只记一次日志、且允许在
    // setModuleApkPath 切换新路径后重试加载（避免失败后置 initialized=true 导致永久静默失效）。
    @Volatile
    private var failedApkPath: String? = null

    fun setModuleApkPath(path: String?) {
        if (path.isNullOrBlank()) return
        synchronized(this) {
            if (moduleApkPath == path && (phraseMap.isNotEmpty() || charMap.isNotEmpty())) return
            moduleApkPath = path
            phraseMap.clear()
            charMap.clear()
            maxPhraseLength = 1
            initialized = false
            failedApkPath = null
        }
    }

    private fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            // 两个字典相互独立加载：单个文件缺失不应拖垮另一个，且只有两个都成功
            // 才置 initialized=true，失败则保持 false 以便后续（路径切换后）重试。
            val phrasesOk = runCatching {
                openDictionary(context, "dictionary/TSPhrases.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val key = parts[0]
                            val value = parts[1]
                            phraseMap[key] = value
                            if (key.length > maxPhraseLength) {
                                maxPhraseLength = key.length
                            }
                        }
                    }
                }
            }.isSuccess

            val charsOk = runCatching {
                openDictionary(context, "dictionary/TSCharacters.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val key = parts[0]
                            val value = parts[1]
                            if (key.isNotEmpty() && value.isNotEmpty()) {
                                charMap[key[0]] = value[0]
                            }
                        }
                    }
                }
            }.isSuccess

            if (phrasesOk && charsOk) {
                initialized = true
                failedApkPath = null
            } else if (failedApkPath != moduleApkPath) {
                // 同一 apk 路径下仅记录一次，避免每次转换都刷日志。
                failedApkPath = moduleApkPath
                LogManager.e(
                    "ChineseUtils",
                    "字典加载失败 (phrasesOk=$phrasesOk, charsOk=$charsOk)",
                    Exception("moduleApkPath=${moduleApkPath ?: "<app assets>"}"),
                )
            }
        }
    }

    private fun openDictionary(context: Context, path: String): InputStream {
        runCatching { context.assets.open(path) }.getOrNull()?.let { return it }
        moduleApkPath?.let { apkPath ->
            runCatching {
                ZipFile(apkPath).use { apk ->
                    val entry = apk.getEntry("assets/$path")
                        ?: error("Module asset not found: $path")
                    ByteArrayInputStream(apk.getInputStream(entry).use(InputStream::readBytes))
                }
            }.getOrNull()?.let { return it }
        }
        return context.createPackageContext(
            BuildConfig.APPLICATION_ID,
            Context.CONTEXT_IGNORE_SECURITY
        ).assets.open(path)
    }

    /**
     * 将繁体中文转换为简体中文 (附带基于上下文词组的转换纠正)
     */
    fun toSimplified(context: Context, text: String): String {
        if (text.isEmpty()) return text
        ensureLoaded(context)
        
        val result = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched = false
            // 基于长度的词组最长正向匹配
            for (len in maxPhraseLength downTo 2) {
                if (i + len <= text.length) {
                    val sub = text.substring(i, i + len)
                    val mapped = phraseMap[sub]
                    if (mapped != null) {
                        result.append(mapped)
                        i += len
                        matched = true
                        break
                    }
                }
            }
            // 未命中词组，查单字映射，再查不到保留原字
            if (!matched) {
                val c = text[i]
                result.append(charMap[c] ?: c)
                i++
            }
        }
        return result.toString()
    }
}
