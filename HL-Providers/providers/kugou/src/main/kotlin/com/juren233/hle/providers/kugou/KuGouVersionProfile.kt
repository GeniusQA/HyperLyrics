/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

/**
 * 酷狗 App 版本画像。
 *
 * 现代版本（酷狗音乐 20.7.5 / 概念版 5.2.4 起）的歌词与播放队列包保留了可读类名
 * （`com.kugou.framework.lyric.LyricManager`、`com.kugou.common.player.manager.QueuePlayerManager`、
 * `IMedia`、`getNextMedia`），可以用语义锚 + DexKit 精确解析。
 *
 * 更早的版本（例如概念版 2.5.5，versionCode 10597）歌词类整体混淆为
 * `com.kugou.framework.lyric.a/b/c/.../l`，歌词包内仅 `l.v(Z)V` 携带
 * “file is not krc or lyc or txt file” 锚点且没有文件路径入参，播放队列也没有
 * `QueuePlayerManager`/`IMedia`/`getNextMedia`——语义锚查询必然 count=0。
 * 这类版本改走框架层文件读取兜底（见 `KuGouPluginEntry` 的旧版歌词监听）。
 */
internal object KuGouVersionProfile {

    const val FULL_PACKAGE = "com.kugou.android"
    const val LITE_PACKAGE = "com.kugou.android.lite"

    /** 酷狗音乐（full）已标定基线：20.7.5 DEX 取证版本。 */
    const val FULL_MODERN_MIN_VERSION_CODE = 20_759L

    /** 酷狗概念版（lite）已标定基线：5.2.4 DEX 取证版本。 */
    const val LITE_MODERN_MIN_VERSION_CODE = 11_540L

    /**
     * 是否可以使用现代语义锚查询。
     *
     * 只有达到已取证基线（或更高）的版本才下发 DexKit 查询：低于基线时内部类名与
     * 签名完全不同，查询只会稳定返回 count=0，并连带触发自修复与失败日志。
     */
    fun supportsModernDexProfiles(packageName: String, versionCode: Long): Boolean = when (packageName) {
        FULL_PACKAGE -> versionCode >= FULL_MODERN_MIN_VERSION_CODE
        LITE_PACKAGE -> versionCode >= LITE_MODERN_MIN_VERSION_CODE
        else -> false
    }
}

/**
 * 旧版兜底的歌词文件路径识别。
 *
 * 旧版无法定位到 KuGou 内部的歌词加载方法，改为观察框架层 `java.io.FileInputStream`
 * 构造器；该回调位于极高的热路径上（App 内所有文件打开都会经过），因此这里只做
 * 零分配的扩展名判断。
 */
internal object KuGouLyricFilePathPolicy {

    private const val KRC_SUFFIX = ".krc"
    private const val LRC_SUFFIX = ".lrc"

    fun isLyricFilePath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return path.endsWith(KRC_SUFFIX, ignoreCase = true) ||
            path.endsWith(LRC_SUFFIX, ignoreCase = true)
    }
}

/**
 * 旧版歌词文件命中去重。
 *
 * 框架层 Hook 会在同一次歌词加载中观察到多次文件打开（探测 + 完整读取），
 * 且同一文件在歌曲重播时会再次打开；这里按「路径 + 时间窗」去重：
 * 时间窗内的重复打开直接忽略，超出时间窗则允许重新解析并发布。
 */
internal class KuGouLyricFileHitGuard(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private val hits = LinkedHashMap<String, Long>()

    fun shouldHandle(path: String, nowMs: Long): Boolean = synchronized(hits) {
        val last = hits[path]
        if (last != null && nowMs - last < ttlMs) return false
        hits.remove(path)
        hits[path] = nowMs
        while (hits.size > capacity) {
            val oldest = hits.keys.firstOrNull() ?: break
            hits.remove(oldest)
        }
        true
    }

    internal companion object {
        const val DEFAULT_CAPACITY = 8
        const val DEFAULT_TTL_MS = 2_000L
    }
}
