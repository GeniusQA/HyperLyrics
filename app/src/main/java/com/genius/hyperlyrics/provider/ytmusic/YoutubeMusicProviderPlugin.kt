/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.provider.ytmusic

import com.genius.hyperlyrics.provider.OfficialProviderHost
import com.genius.hyperlyrics.provider.OfficialProviderPlugin

/**
 * Built-in YouTube Music provider.
 *
 * The standalone Lyricon module (`io.github.proify.lyricon.ytmprovider`) observed the player's
 * public MediaSession and filled lyrics from an online source. This provider keeps the same
 * observation contract but is compiled into HyperLyrics, so no separate APK (and no
 * Provider Pack signature) is required. Lyrics are resolved by the shared online pipeline
 * instead of the module's private request path.
 */
class YoutubeMusicProviderPlugin : OfficialProviderPlugin {
    override fun install(host: OfficialProviderHost) {
        YoutubeMusicProviderRuntime(host).install()
    }
}
