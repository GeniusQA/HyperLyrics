/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

internal data class KuwoPlaybackHookProfile(
    val managerClassName: String,
    val contentClassName: String,
    val musicClassName: String,
    val singletonMethodName: String,
    val currentMusicMethodName: String,
    val nextContentMethodName: String,
)

internal data class KuwoMusicHookProfile(
    val ridFieldName: String,
    val titleFieldName: String,
    val artistFieldName: String,
    val albumFieldName: String,
    val durationSecondsFieldName: String,
)

internal data class KuwoHookProfile(
    val versionName: String,
    val versionCode: Long,
    val playback: KuwoPlaybackHookProfile,
    val music: KuwoMusicHookProfile,
)

/** Verified templates for every Kuwo runtime identifier used by the Provider. */
internal object KuwoHookProfiles {
    // Verified from the original Kuwo Music 12.1.8.2 (12182) APK DEX on 2026-08-07.
    // Exact descriptors:
    // Lcn/kuwo/mod/playcontrol/n;->L()Lcn/kuwo/mod/playcontrol/n;
    // Lcn/kuwo/mod/playcontrol/n;->S()Lcn/kuwo/base/bean/Music;
    // Lcn/kuwo/mod/playcontrol/n;->g0()Lcn/kuwo/base/bean/IContent;.
    // g0() is Kuwo's own read path for the next item and covers sequential, list-end,
    // single-pass and randomized queues. Music fields below are public DEX fields.
    val V12_1_8_2 = KuwoHookProfile(
        versionName = "12.1.8.2",
        versionCode = 12_182L,
        playback = KuwoPlaybackHookProfile(
            managerClassName = "cn.kuwo.mod.playcontrol.n",
            contentClassName = "cn.kuwo.base.bean.IContent",
            musicClassName = "cn.kuwo.base.bean.Music",
            singletonMethodName = "L",
            currentMusicMethodName = "S",
            nextContentMethodName = "g0",
        ),
        music = KuwoMusicHookProfile(
            ridFieldName = "rid",
            titleFieldName = "name",
            artistFieldName = "artist",
            albumFieldName = "album",
            durationSecondsFieldName = "duration",
        ),
    )

    // Verified from the original Kuwo Music 12.2.2.0 (12220) APK DEX on 2026-09-04
    // (classes8.dex). The 12.2.2.0 obfuscation pass shifted every playcontrol name:
    // Lcn/kuwo/mod/playcontrol/n;->O()Lcn/kuwo/mod/playcontrol/n;   (singleton, was L)
    // Lcn/kuwo/mod/playcontrol/n;->X()Lcn/kuwo/base/bean/Music;     (current music, was S;
    //     returns the field written by every playback entry such as U0/Y1/a1/b1)
    // Lcn/kuwo/mod/playcontrol/n;->k0()Lcn/kuwo/base/bean/IContent; (next content, was g0;
    //     the only ()IContent method containing the
    //     "随机模式，获取歌曲下一曲,随机索引空，现在生成" anchor)
    // The old names are still present but repurposed (L()I, S()I, g0()MusicList), so exact
    // preferred-target lookup fails safely into the DexKit path; k0/Y/Z ambiguity is avoided
    // by the next-content requiredStrings anchor. Music bean fields are unchanged.
    val V12_2_2_0 = KuwoHookProfile(
        versionName = "12.2.2.0",
        versionCode = 12_220L,
        playback = KuwoPlaybackHookProfile(
            managerClassName = "cn.kuwo.mod.playcontrol.n",
            contentClassName = "cn.kuwo.base.bean.IContent",
            musicClassName = "cn.kuwo.base.bean.Music",
            singletonMethodName = "O",
            currentMusicMethodName = "X",
            nextContentMethodName = "k0",
        ),
        music = KuwoMusicHookProfile(
            ridFieldName = "rid",
            titleFieldName = "name",
            artistFieldName = "artist",
            albumFieldName = "album",
            durationSecondsFieldName = "duration",
        ),
    )

    private val exactProfiles = listOf(V12_1_8_2, V12_2_2_0)

    fun resolve(versionName: String, versionCode: Long): KuwoHookProfile =
        exactProfiles.firstOrNull {
            it.versionName == versionName && it.versionCode == versionCode
        } ?: exactProfiles.maxBy(KuwoHookProfile::versionCode)
}
