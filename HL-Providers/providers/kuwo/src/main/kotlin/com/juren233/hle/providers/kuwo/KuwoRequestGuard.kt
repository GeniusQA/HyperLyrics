/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

/** Prevents an older asynchronous lyric request from publishing after a track switch. */
internal class KuwoRequestGuard {
    @Volatile
    private var currentKey: String? = null

    @Synchronized
    fun select(key: String): Boolean {
        if (currentKey == key) return false
        currentKey = key
        return true
    }

    fun isCurrent(key: String): Boolean = currentKey == key

    @Synchronized
    fun clear() {
        currentKey = null
    }
}
