/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.app.Application
import com.genius.hyperlyrics.provider.OfficialProviderDexMethodQuery
import com.genius.hyperlyrics.provider.OfficialProviderDexTypeReference
import com.genius.hyperlyrics.provider.OfficialProviderDexTypeSource
import com.genius.hyperlyrics.provider.OfficialProviderMethodTarget
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class QQMusicTrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
)

internal data class QQMusicQueueSnapshot(
    val current: QQMusicTrackSnapshot,
    val next: QQMusicTrackSnapshot?,
)

internal data class QQMusicNextTrackProfile(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val cacheNamespace: String,
    val managerClassName: String,
    val singletonMethodName: String,
    val currentSongMethodName: String,
    val nextSongMethodName: String,
    val songInfoClassName: String,
    val songIdMethodName: String,
    val songTitleMethodName: String,
    val songArtistMethodName: String,
)

internal object QQMusicNextTrackProfiles {
    // Verified from the original QQ Music 20.6.5.8 APK DEX on 2026-08-06.
    // Exact descriptors:
    // Lcom/tencent/qqmusic/common/player/d;->z()Lcom/tencent/qqmusic/common/player/d;
    // ->M()/E()Lcom/tencent/qqmusicplayerprocess/songinfo/SongInfo;
    // SongInfo.D2()J, f3()Ljava/lang/String;, R3()Ljava/lang/String;.
    val V20_6_5_8 = QQMusicNextTrackProfile(
        packageName = QQMusicRuntimePlan.MOBILE_PACKAGE,
        versionName = "20.6.5.8",
        versionCode = 7228L,
        cacheNamespace = "qqmusic-mobile",
        managerClassName = "com.tencent.qqmusic.common.player.d",
        singletonMethodName = "z",
        currentSongMethodName = "M",
        nextSongMethodName = "E",
        songInfoClassName = "com.tencent.qqmusicplayerprocess.songinfo.SongInfo",
        songIdMethodName = "D2",
        songTitleMethodName = "f3",
        songArtistMethodName = "R3",
    )

    // Verified from the original QQ Music 20.7.5.8 APK DEX on 2026-08-18.
    // Exact descriptors:
    // Lcom/tencent/qqmusic/common/player/d;->B()Lcom/tencent/qqmusic/common/player/d;
    // ->O()/G()Lcom/tencent/qqmusicplayerprocess/songinfo/SongInfo;
    // SongInfo.I2()J, j3()Ljava/lang/String;, W3()Ljava/lang/String;.
    val V20_7_5_8 = QQMusicNextTrackProfile(
        packageName = QQMusicRuntimePlan.MOBILE_PACKAGE,
        versionName = "20.7.5.8",
        versionCode = 7308L,
        cacheNamespace = "qqmusic-mobile",
        managerClassName = "com.tencent.qqmusic.common.player.d",
        singletonMethodName = "B",
        currentSongMethodName = "O",
        nextSongMethodName = "G",
        songInfoClassName = "com.tencent.qqmusicplayerprocess.songinfo.SongInfo",
        songIdMethodName = "I2",
        songTitleMethodName = "j3",
        songArtistMethodName = "W3",
    )

    // Verified from the original QQ Music HD 6.12.0.5 APK DEX on 2026-08-11.
    // Exact descriptors:
    // Lcom/tencent/qqmusic/qplayer/core/player/MusicPlayerHelper;
    // ->a0()Lcom/tencent/qqmusic/qplayer/core/player/MusicPlayerHelper;
    // ->l0()/g0()Lcom/tencent/qqmusic/openapisdk/model/SongInfo;
    // SongInfo.getSongId()J, getSongName()/getSingerName()Ljava/lang/String;.
    val HD_V6_12_0_5 = QQMusicNextTrackProfile(
        packageName = QQMusicRuntimePlan.HD_PACKAGE,
        versionName = "6.12.0.5",
        versionCode = 6_120_005L,
        cacheNamespace = "qqmusic-hd",
        managerClassName = "com.tencent.qqmusic.qplayer.core.player.MusicPlayerHelper",
        singletonMethodName = "a0",
        currentSongMethodName = "l0",
        nextSongMethodName = "g0",
        songInfoClassName = "com.tencent.qqmusic.openapisdk.model.SongInfo",
        songIdMethodName = "getSongId",
        songTitleMethodName = "getSongName",
        songArtistMethodName = "getSingerName",
    )

    // Verified from the original Xiaomi Music (com.miui.player) 4.44.0.9 APK DEX on 2026-09-12.
    // Exact descriptors:
    // Lcom/tencent/qqmusicsdk/protocol/MusicPlayer;->getInstance()Lcom/tencent/qqmusicsdk/protocol/MusicPlayer;
    // ->getCurSong()/getNextSong()Lcom/tencent/qqmusicsdk/protocol/SongInfomation;
    // SongInfomation.getId()J, getName()/getSingerName()Ljava/lang/String;.
    // Unobfuscated QQ Music Lite SDK identifiers; each name verified against the
    // classes9.dex string pool (single occurrence per symbol).
    val MIUI_V4_44_0_9 = QQMusicNextTrackProfile(
        packageName = QQMusicRuntimePlan.MIUI_PACKAGE,
        versionName = "4.44.0.9",
        versionCode = 4_440_009L,
        cacheNamespace = "qqmusic-miui",
        managerClassName = "com.tencent.qqmusicsdk.protocol.MusicPlayer",
        singletonMethodName = "getInstance",
        currentSongMethodName = "getCurSong",
        nextSongMethodName = "getNextSong",
        songInfoClassName = "com.tencent.qqmusicsdk.protocol.SongInfomation",
        songIdMethodName = "getId",
        songTitleMethodName = "getName",
        songArtistMethodName = "getSingerName",
    )

    private val profiles = listOf(V20_6_5_8, V20_7_5_8, HD_V6_12_0_5, MIUI_V4_44_0_9)

    fun resolve(
        packageName: String,
        versionName: String,
        versionCode: Long,
    ): QQMusicNextTrackProfile? = profiles.firstOrNull { profile ->
        profile.packageName == packageName &&
            profile.versionName == versionName &&
            profile.versionCode == versionCode
    } ?: profiles
        .filter { profile -> profile.packageName == packageName }
        .maxByOrNull(QQMusicNextTrackProfile::versionCode)
}

internal class QQMusicNextTrackResolver private constructor(
    private val singletonMethod: Method,
    private val currentSongMethod: Method,
    private val nextSongMethod: Method,
    private val songIdMethod: Method,
    private val songTitleMethod: Method,
    private val songArtistMethod: Method,
) {
    fun resolve(): QQMusicQueueSnapshot? {
        val manager = singletonMethod.invoke(null) ?: return null
        val current = currentSongMethod.invoke(manager)?.let(::toTrack) ?: return null
        val next = nextSongMethod.invoke(manager)?.let(::toTrack)
        return QQMusicQueueSnapshot(current, next)
    }

    private fun toTrack(value: Any): QQMusicTrackSnapshot {
        val directId = runCatching { (songIdMethod.invoke(value) as Number).toLong().toString() }.getOrNull()
        val directTitle = runCatching { songTitleMethod.invoke(value) as? String }.getOrNull()?.trim()
        val directArtist = runCatching { songArtistMethod.invoke(value) as? String }.getOrNull()?.trim()

        if (!directId.isNullOrEmpty() && !directTitle.isNullOrEmpty()) {
            return QQMusicTrackSnapshot(
                id = directId,
                title = directTitle,
                artist = directArtist.orEmpty(),
            )
        }

        // Fallback 1: SongInfo.shortMessage() parse
        val fromShortMessage = runCatching { parseFromShortMessage(value) }.getOrNull()
        if (fromShortMessage != null && fromShortMessage.id.isNotEmpty() && fromShortMessage.title.isNotEmpty()) {
            return fromShortMessage
        }

        // Fallback 2: Direct field reflection (field b: Long)
        val fieldId = runCatching {
            val field = value.javaClass.getDeclaredField("b").apply { isAccessible = true }
            (field.get(value) as? Number)?.toLong()?.toString()
        }.getOrNull()

        return QQMusicTrackSnapshot(
            id = directId ?: fieldId ?: "",
            title = directTitle.orEmpty(),
            artist = directArtist.orEmpty(),
        )
    }

    companion object {
        private val SHORT_MESSAGE_REGEX = Regex(
            """id\s*=\s*(\d+)\s+name\s*=\s*(.*?)\s+singer\s*=\s*(.*?)\s+tmpPlayKey\s*=""",
            RegexOption.DOT_MATCHES_ALL,
        )

        internal fun parseFromShortMessage(value: Any): QQMusicTrackSnapshot? {
            val shortMessageMethod = runCatching {
                value.javaClass.getMethod("shortMessage").apply { isAccessible = true }
            }.getOrNull() ?: return null
            val raw = shortMessageMethod.invoke(value) as? String ?: return null
            return parseShortMessageText(raw)
        }

        internal fun parseShortMessageText(raw: String): QQMusicTrackSnapshot? {
            val match = SHORT_MESSAGE_REGEX.find(raw) ?: return null
            val id = match.groupValues[1].trim()
            val title = match.groupValues[2].trim()
            val artist = match.groupValues[3].trim()
            return QQMusicTrackSnapshot(id = id, title = title, artist = artist)
        }
        fun queries(application: Application): List<OfficialProviderDexMethodQuery>? {
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            return queries(
                packageName = application.packageName,
                versionName = packageInfo.versionName.orEmpty(),
                versionCode = packageInfo.longVersionCode,
            )
        }

        internal fun queries(
            packageName: String,
            versionName: String,
            versionCode: Long,
        ): List<OfficialProviderDexMethodQuery>? {
            val profile = QQMusicNextTrackProfiles.resolve(
                packageName,
                versionName,
                versionCode,
            ) ?: return null
            fun target(
                className: String,
                methodName: String,
                returnTypeName: String,
                isStatic: Boolean = false,
            ) = OfficialProviderMethodTarget(
                className = className,
                methodName = methodName,
                returnTypeName = returnTypeName,
                isStatic = isStatic,
            )
            val cacheNamespace = profile.cacheNamespace
            val managerType = OfficialProviderDexTypeReference(
                queryCacheKey = "$cacheNamespace-player-singleton-v4",
                source = OfficialProviderDexTypeSource.RETURN_TYPE,
            )
            val songInfoType = OfficialProviderDexTypeReference(
                queryCacheKey = "$cacheNamespace-current-song-v4",
                source = OfficialProviderDexTypeSource.RETURN_TYPE,
            )
            val songInfo = profile.songInfoClassName
            val isHd = profile.packageName == QQMusicRuntimePlan.HD_PACKAGE
            val isMiui = profile.packageName == QQMusicRuntimePlan.MIUI_PACKAGE
            return listOf(
                OfficialProviderDexMethodQuery(
                    cacheKey = managerType.queryCacheKey,
                    preferredTarget = target(
                        profile.managerClassName,
                        profile.singletonMethodName,
                        profile.managerClassName,
                        isStatic = true,
                    ),
                    declaringClassName = profile.managerClassName.takeIf { isHd || isMiui },
                    declaringClassNamePrefix = "com.tencent.qqmusic.common.player."
                        .takeUnless { isHd || isMiui },
                    requiredStrings = if (isHd || isMiui) {
                        emptyList()
                    } else {
                        listOf("MusicPlayerHelper CAN'T use in Play Process")
                    },
                    parameterTypeNames = emptyList(),
                    returnTypeMatchesDeclaringClass = true,
                    isStatic = true,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = songInfoType.queryCacheKey,
                    preferredTarget = target(
                        profile.managerClassName,
                        profile.currentSongMethodName,
                        songInfo,
                    ).takeUnless { isHd },
                    declaringClassReference = managerType,
                    requiredInvokedMethodNames = if (isHd || isMiui) {
                        emptyList()
                    } else {
                        listOf("getPlaySong")
                    },
                    requiredCallerMethodNames = if (isHd) {
                        listOf("getCurrentSongInfo")
                    } else {
                        emptyList()
                    },
                    forbiddenInvokedMethodDescriptors = if (isHd) {
                        listOf(HD_SONG_ID_METHOD_DESCRIPTOR)
                    } else {
                        emptyList()
                    },
                    parameterTypeNames = emptyList(),
                    returnTypeName = songInfo,
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "$cacheNamespace-next-song-v4",
                    preferredTarget = target(
                        profile.managerClassName,
                        profile.nextSongMethodName,
                        songInfo,
                    ).takeUnless { isHd },
                    declaringClassReference = managerType,
                    requiredInvokedMethodNames = if (isHd || isMiui) {
                        emptyList()
                    } else {
                        listOf("getNextSong")
                    },
                    requiredCallerMethodNames = if (isHd) {
                        listOf("getNextSongInfo")
                    } else {
                        emptyList()
                    },
                    parameterTypeNames = emptyList(),
                    returnTypeReference = songInfoType,
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "$cacheNamespace-song-id-v4",
                    preferredTarget = target(songInfo, profile.songIdMethodName, "long"),
                    declaringClassReference = songInfoType,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "long",
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "$cacheNamespace-song-title-v4",
                    preferredTarget = target(songInfo, profile.songTitleMethodName, "java.lang.String"),
                    declaringClassReference = songInfoType,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "java.lang.String",
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "$cacheNamespace-song-artist-v4",
                    preferredTarget = target(songInfo, profile.songArtistMethodName, "java.lang.String"),
                    declaringClassReference = songInfoType,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "java.lang.String",
                    isStatic = false,
                ),
            )
        }

        fun create(
            application: Application,
            targets: List<OfficialProviderMethodTarget>,
        ): QQMusicNextTrackResolver {
            require(targets.size == 6) { "QQ 音乐下一首目标数量错误" }
            val loader = application.classLoader
            val methods = targets.map { it.toMethod(loader) }
            val singletonMethod = methods[0]
            val currentSongMethod = methods[1]
            val nextSongMethod = methods[2]
            val songIdMethod = methods[3]
            val songTitleMethod = methods[4]
            val songArtistMethod = methods[5]
            val managerClass = singletonMethod.returnType
            val songInfoClass = currentSongMethod.returnType

            require(Modifier.isStatic(singletonMethod.modifiers))
            require(singletonMethod.parameterCount == 0)
            require(singletonMethod.declaringClass == managerClass)
            require(currentSongMethod.declaringClass == managerClass)
            require(nextSongMethod.declaringClass == managerClass)
            require(!Modifier.isStatic(currentSongMethod.modifiers) && currentSongMethod.parameterCount == 0)
            require(!Modifier.isStatic(nextSongMethod.modifiers) && nextSongMethod.parameterCount == 0)
            require(nextSongMethod.returnType == songInfoClass)
            require(songIdMethod.declaringClass == songInfoClass)
            require(songTitleMethod.declaringClass == songInfoClass)
            require(songArtistMethod.declaringClass == songInfoClass)
            require(!Modifier.isStatic(songIdMethod.modifiers) && songIdMethod.parameterCount == 0)
            require(!Modifier.isStatic(songTitleMethod.modifiers) && songTitleMethod.parameterCount == 0)
            require(!Modifier.isStatic(songArtistMethod.modifiers) && songArtistMethod.parameterCount == 0)
            require(songIdMethod.returnType == Long::class.javaPrimitiveType)
            require(songTitleMethod.returnType == String::class.java)
            require(songArtistMethod.returnType == String::class.java)
            return QQMusicNextTrackResolver(
                singletonMethod,
                currentSongMethod,
                nextSongMethod,
                songIdMethod,
                songTitleMethod,
                songArtistMethod,
            )
        }

        private fun OfficialProviderMethodTarget.toMethod(loader: ClassLoader): Method {
            val clazz = loader.loadClass(className)
            val parameters = parameterTypeNames.map { name ->
                when (name) {
                    "boolean" -> Boolean::class.javaPrimitiveType!!
                    "int" -> Int::class.javaPrimitiveType!!
                    "long" -> Long::class.javaPrimitiveType!!
                    else -> loader.loadClass(name)
                }
            }.toTypedArray()
            return clazz.getDeclaredMethod(methodName, *parameters).apply { isAccessible = true }
        }

        // Verified from the original QQ Music HD 6.12.0.5 DEX. The incorrect V() candidate
        // performs this public SongInfo ID lookup before a secondary playlist re-query, while
        // the real current-song l0() method returns MusicPlayListManager.u() directly.
        private const val HD_SONG_ID_METHOD_DESCRIPTOR =
            "Lcom/tencent/qqmusic/openapisdk/model/SongInfo;->getSongId()J"
    }
}
