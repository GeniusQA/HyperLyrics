/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

internal enum class NeteasePlaybackForwardingMode {
    AUTOMATIC,
    PRESERVE_BUFFERING,
    MANUAL_FALLBACK,
}

/** A successful timestamped anchor must not be disabled by the legacy boolean overload. */
internal fun neteasePlaybackForwardingMode(
    hasState: Boolean,
    buffering: Boolean,
    automaticAccepted: Boolean?,
): NeteasePlaybackForwardingMode = when {
    hasState && automaticAccepted == true -> NeteasePlaybackForwardingMode.AUTOMATIC
    buffering -> NeteasePlaybackForwardingMode.PRESERVE_BUFFERING
    else -> NeteasePlaybackForwardingMode.MANUAL_FALLBACK
}
