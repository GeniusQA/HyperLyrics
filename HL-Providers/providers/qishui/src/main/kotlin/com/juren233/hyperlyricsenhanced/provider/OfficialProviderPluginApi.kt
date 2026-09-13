/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.provider

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState

/** Minimal API surface used by the Qishui SystemUI-only Provider Pack. */
interface OfficialProviderSystemMediaPlugin {
    fun installSystemMedia(host: OfficialProviderSystemMediaHost)

    fun releaseSystemMedia()
}

interface OfficialProviderSystemMediaHost {
    val application: Application
    val playerPackageName: String

    fun subscribe(callback: OfficialProviderSystemMediaCallback): OfficialProviderSystemMediaSubscription
}

fun interface OfficialProviderSystemMediaCallback {
    fun onMediaChanged(metadata: MediaMetadata?, playbackState: PlaybackState?)
}

fun interface OfficialProviderSystemMediaSubscription {
    fun release()
}
