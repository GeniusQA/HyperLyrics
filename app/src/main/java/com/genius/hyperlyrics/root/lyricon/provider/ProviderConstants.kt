/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.genius.hyperlyrics.root.lyricon.provider

/**
 * 常量定义类，用于 Provider 与中心服务交互。
 */
object ProviderConstants {

    /** 默认歌词更新间隔 */
    const val DEFAULT_POSITION_UPDATE_INTERVAL: Long = 1000L / 24

    internal const val DEBUG: Boolean = false

    const val SYSTEM_UI_PACKAGE_NAME: String = "com.android.systemui"
}
