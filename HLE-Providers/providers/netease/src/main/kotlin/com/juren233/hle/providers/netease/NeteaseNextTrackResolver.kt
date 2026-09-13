/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import android.app.Application
import com.genius.hyperlyrics.provider.OfficialProviderDexMethodQuery
import com.genius.hyperlyrics.provider.OfficialProviderDexTypeReference
import com.genius.hyperlyrics.provider.OfficialProviderDexTypeSource
import com.genius.hyperlyrics.provider.OfficialProviderMethodTarget
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class NeteaseNextTrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal data class NeteaseNextTrackProfile(
    val versionName: String,
    val versionCode: Long,
    val serviceClassName: String,
    val playerManagerClassName: String,
    val musicInfoClassName: String,
    val simpleMusicInfoClassName: String,
    val playerManagerAccessorName: String,
    val nextMusicMethodName: String,
    val toSimpleMusicInfoMethodName: String,
    val idMethodName: String,
    val titleMethodName: String,
    val artistMethodName: String,
    val albumMethodName: String,
    val durationMethodName: String,
)

internal object NeteaseNextTrackProfiles {
    // Verified from the original NetEase Cloud Music 9.5.61 APK DEX on 2026-08-06.
    // Exact descriptors:
    // MainProcessPlayService.E1()Ltr0/z; -> tr0.z.g()L.../MusicInfo;
    // MusicInfo.toSimpleMusicInfo()L.../meta/virtual/SimpleMusicInfo;.
    val V9_5_61 = NeteaseNextTrackProfile(
        versionName = "9.5.61",
        versionCode = 9_005_061L,
        serviceClassName = "com.netease.cloudmusic.service.MainProcessPlayService",
        playerManagerClassName = "tr0.z",
        musicInfoClassName = "com.netease.cloudmusic.meta.MusicInfo",
        simpleMusicInfoClassName = "com.netease.cloudmusic.meta.virtual.SimpleMusicInfo",
        playerManagerAccessorName = "E1",
        nextMusicMethodName = "g",
        toSimpleMusicInfoMethodName = "toSimpleMusicInfo",
        idMethodName = "getId",
        titleMethodName = "getMusicName",
        artistMethodName = "getSingerName",
        albumMethodName = "getAlbumName",
        durationMethodName = "getDuration",
    )

    // Verified from the original NetEase Cloud Music 9.5.70 APK DEX on 2026-08-18.
    // Exact descriptors:
    // MainProcessPlayService.E1()Lvr0/z; -> vr0.z.g()L.../MusicInfo;
    // MusicInfo.toSimpleMusicInfo()L.../meta/virtual/SimpleMusicInfo;.
    val V9_5_70 = NeteaseNextTrackProfile(
        versionName = "9.5.70",
        versionCode = 9_005_070L,
        serviceClassName = "com.netease.cloudmusic.service.MainProcessPlayService",
        playerManagerClassName = "vr0.z",
        musicInfoClassName = "com.netease.cloudmusic.meta.MusicInfo",
        simpleMusicInfoClassName = "com.netease.cloudmusic.meta.virtual.SimpleMusicInfo",
        playerManagerAccessorName = "E1",
        nextMusicMethodName = "g",
        toSimpleMusicInfoMethodName = "toSimpleMusicInfo",
        idMethodName = "getId",
        titleMethodName = "getMusicName",
        artistMethodName = "getSingerName",
        albumMethodName = "getAlbumName",
        durationMethodName = "getDuration",
    )

    fun resolve(versionName: String, versionCode: Long): NeteaseNextTrackProfile =
        when {
            versionCode >= 9_005_070L || versionName.startsWith("9.5.7") -> V9_5_70
            versionCode == 9_005_061L || versionName == "9.5.61" -> V9_5_61
            else -> V9_5_70
        }
}

internal class NeteaseNextTrackResolver private constructor(
    private val playerManagerAccessor: Method,
    private val nextMusicMethod: Method,
    private val toSimpleMusicInfoMethod: Method,
    private val idMethod: Method,
    private val titleMethod: Method,
    private val artistMethod: Method,
    private val albumMethod: Method,
    private val durationMethod: Method,
) {
    fun resolve(): NeteaseNextTrackSnapshot? {
        val manager = playerManagerAccessor.invoke(null) ?: return null
        val musicInfo = nextMusicMethod.invoke(manager) ?: return null
        val simpleMusicInfo = toSimpleMusicInfoMethod.invoke(musicInfo) ?: return null
        return NeteaseNextTrackSnapshot(
            id = (idMethod.invoke(simpleMusicInfo) as Number).toLong().toString(),
            title = titleMethod.invoke(simpleMusicInfo) as? String ?: "",
            artist = artistMethod.invoke(simpleMusicInfo) as? String ?: "",
            album = albumMethod.invoke(simpleMusicInfo) as? String ?: "",
            durationMs = (durationMethod.invoke(simpleMusicInfo) as Number).toLong(),
        )
    }

    companion object {
        fun queries(application: Application): List<OfficialProviderDexMethodQuery> {
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val profile = NeteaseNextTrackProfiles.resolve(
                packageInfo.versionName.orEmpty(),
                packageInfo.longVersionCode,
            )
            return queries(profile)
        }

        internal fun queries(profile: NeteaseNextTrackProfile): List<OfficialProviderDexMethodQuery> {
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
            val managerType = OfficialProviderDexTypeReference(
                queryCacheKey = "netease-player-manager-accessor-v4",
                source = OfficialProviderDexTypeSource.RETURN_TYPE,
            )
            val musicInfoType = OfficialProviderDexTypeReference(
                queryCacheKey = "netease-next-music-v3",
                source = OfficialProviderDexTypeSource.RETURN_TYPE,
            )
            val simpleMusicInfoType = OfficialProviderDexTypeReference(
                queryCacheKey = "netease-simple-music-v3",
                source = OfficialProviderDexTypeSource.RETURN_TYPE,
            )
            return listOf(
                OfficialProviderDexMethodQuery(
                    cacheKey = managerType.queryCacheKey,
                    declaringClassName = profile.serviceClassName,
                    requiredCallerMethodNames = listOf("getRealNextMusic"),
                    parameterTypeNames = emptyList(),
                    isStatic = true,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = musicInfoType.queryCacheKey,
                    declaringClassReference = managerType,
                    requiredCallerMethodNames = listOf("getRealNextMusic"),
                    parameterTypeNames = emptyList(),
                    returnTypeName = profile.musicInfoClassName,
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = simpleMusicInfoType.queryCacheKey,
                    preferredTarget = target(
                        profile.musicInfoClassName,
                        profile.toSimpleMusicInfoMethodName,
                        profile.simpleMusicInfoClassName,
                    ),
                    declaringClassReference = musicInfoType,
                    parameterTypeNames = emptyList(),
                    returnTypeName = profile.simpleMusicInfoClassName,
                    isStatic = false,
                ),
                queryGetter(
                    "id",
                    profile.simpleMusicInfoClassName,
                    profile.idMethodName,
                    "long",
                    simpleMusicInfoType,
                ),
                queryGetter(
                    "title",
                    profile.simpleMusicInfoClassName,
                    profile.titleMethodName,
                    "java.lang.String",
                    simpleMusicInfoType,
                ),
                queryGetter(
                    "artist",
                    profile.simpleMusicInfoClassName,
                    profile.artistMethodName,
                    "java.lang.String",
                    simpleMusicInfoType,
                ),
                queryGetter(
                    "album",
                    profile.simpleMusicInfoClassName,
                    profile.albumMethodName,
                    "java.lang.String",
                    simpleMusicInfoType,
                ),
                queryGetter(
                    "duration",
                    profile.simpleMusicInfoClassName,
                    profile.durationMethodName,
                    "long",
                    simpleMusicInfoType,
                ),
            )
        }

        private fun queryGetter(
            key: String,
            className: String,
            methodName: String,
            returnTypeName: String,
            declaringClassReference: OfficialProviderDexTypeReference,
        ) = OfficialProviderDexMethodQuery(
            cacheKey = "netease-simple-$key-v3",
            preferredTarget = OfficialProviderMethodTarget(
                className = className,
                methodName = methodName,
                returnTypeName = returnTypeName,
                isStatic = false,
            ),
            declaringClassReference = declaringClassReference,
            parameterTypeNames = emptyList(),
            returnTypeName = returnTypeName,
            isStatic = false,
        )

        fun create(
            application: Application,
            targets: List<OfficialProviderMethodTarget>,
        ): NeteaseNextTrackResolver {
            require(targets.size == 8) { "网易云下一首目标数量错误" }
            val loader = application.classLoader
            val methods = targets.map { it.toMethod(loader) }
            val playerManagerAccessor = methods[0]
            val nextMusicMethod = methods[1]
            val toSimpleMusicInfoMethod = methods[2]
            val idMethod = methods[3]
            val titleMethod = methods[4]
            val artistMethod = methods[5]
            val albumMethod = methods[6]
            val durationMethod = methods[7]
            val playerManagerClass = playerManagerAccessor.returnType
            val musicInfoClass = nextMusicMethod.returnType
            val simpleMusicInfoClass = toSimpleMusicInfoMethod.returnType

            require(Modifier.isStatic(playerManagerAccessor.modifiers))
            require(playerManagerAccessor.returnType == playerManagerClass)
            require(nextMusicMethod.returnType == musicInfoClass)
            require(toSimpleMusicInfoMethod.returnType == simpleMusicInfoClass)
            require(idMethod.returnType == Long::class.javaPrimitiveType)
            require(titleMethod.returnType == String::class.java)
            require(artistMethod.returnType == String::class.java)
            require(albumMethod.returnType == String::class.java)
            require(durationMethod.returnType == Long::class.javaPrimitiveType)
            return NeteaseNextTrackResolver(
                playerManagerAccessor,
                nextMusicMethod,
                toSimpleMusicInfoMethod,
                idMethod,
                titleMethod,
                artistMethod,
                albumMethod,
                durationMethod,
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
    }
}
