/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import android.app.Application
import android.os.Looper
import com.genius.hyperlyrics.provider.OfficialProviderDexMethodQuery
import com.genius.hyperlyrics.provider.OfficialProviderDexTypeReference
import com.genius.hyperlyrics.provider.OfficialProviderDexTypeSource
import com.genius.hyperlyrics.provider.OfficialProviderMethodTarget
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class KuwoTrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal data class KuwoQueueSnapshot(
    val current: KuwoTrackSnapshot,
    val next: KuwoTrackSnapshot?,
)

internal object KuwoNextTrackBinding {
    fun align(
        metadata: KuwoTrackMetadata?,
        snapshot: KuwoQueueSnapshot?,
    ): KuwoQueueSnapshot? {
        if (metadata == null || snapshot == null) return null
        val mediaRid = KuwoTrackIdResolver.directRid(metadata.mediaId)
        if (mediaRid != null) return snapshot.takeIf { it.current.id == mediaRid.toString() }

        val mediaTitle = KuwoTrackIdResolver.normalize(metadata.title)
        val queueTitle = KuwoTrackIdResolver.normalize(snapshot.current.title)
        return snapshot.takeIf {
            mediaTitle.isNotEmpty() && mediaTitle == queueTitle
        }
    }
}

/**
 * Main-thread read of Kuwo's own current/next-content API.
 * Every reflected identifier is supplied by [KuwoHookProfile].
 */
internal class KuwoNextTrackResolver private constructor(
    private val singletonMethod: Method,
    private val currentMusicMethod: Method,
    private val nextContentMethod: Method,
    private val preferredFields: KuwoMusicHookProfile,
) {
    @Volatile
    private var fieldAccessors: MusicFieldAccessors? = null

    fun resolve(metadata: KuwoTrackMetadata): KuwoQueueSnapshot? {
        require(Looper.myLooper() == Looper.getMainLooper()) {
            "酷我下一首解析必须在主线程执行"
        }
        val manager = singletonMethod.invoke(null) ?: return null
        val currentValue = currentMusicMethod.invoke(manager) ?: return null
        val accessors = fieldAccessors
            ?.takeIf { it.ownerClass.isInstance(currentValue) }
            ?: MusicFieldAccessors.resolve(
                currentValue = currentValue,
                metadata = metadata,
                preferred = preferredFields,
            ).also { fieldAccessors = it }
        val current = decodeMusic(currentValue, accessors) ?: return null
        val next = nextContentMethod.invoke(manager)?.let { value ->
            decodeMusic(value, accessors)
        }
        return KuwoQueueSnapshot(current = current, next = next)
    }

    private fun decodeMusic(
        value: Any,
        accessors: MusicFieldAccessors,
    ): KuwoTrackSnapshot? {
        if (!accessors.ownerClass.isInstance(value)) return null
        val durationSeconds = (accessors.durationSecondsField.get(value) as Number).toLong()
        return KuwoTrackSnapshot(
            id = (accessors.ridField.get(value) as Number).toLong()
                .takeIf { it > 0L }?.toString().orEmpty(),
            title = accessors.titleField.get(value) as? String ?: "",
            artist = accessors.artistField.get(value) as? String ?: "",
            album = accessors.albumField?.get(value) as? String ?: "",
            durationMs = durationSeconds.takeIf { it > 0L }?.times(1_000L) ?: -1L,
        )
    }

    private data class MusicFieldAccessors(
        val ownerClass: Class<*>,
        val ridField: Field,
        val titleField: Field,
        val artistField: Field,
        val albumField: Field?,
        val durationSecondsField: Field,
    ) {
        companion object {
            fun resolve(
                currentValue: Any,
                metadata: KuwoTrackMetadata,
                preferred: KuwoMusicHookProfile,
            ): MusicFieldAccessors {
                val ownerClass = currentValue.javaClass
                val fields = generateSequence(ownerClass) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .onEach { it.isAccessible = true }
                    .toList()
                val directRid = KuwoTrackIdResolver.directRid(metadata.mediaId)
                val rid = fields.resolveField(preferred.ridFieldName) { field ->
                    directRid != null &&
                        (field.get(currentValue) as? Number)?.toLong() == directRid
                }
                val title = fields.resolveField(preferred.titleFieldName) { field ->
                    sameText(field.get(currentValue), metadata.title)
                }
                val artist = fields.resolveField(preferred.artistFieldName) { field ->
                    sameText(field.get(currentValue), metadata.artist)
                }
                val album = fields.resolveOptionalField(preferred.albumFieldName) { field ->
                    sameText(field.get(currentValue), metadata.album)
                }
                val duration = fields.resolveField(preferred.durationSecondsFieldName) { field ->
                    val value = (field.get(currentValue) as? Number)?.toLong() ?: return@resolveField false
                    val expectedMs = metadata.durationMs.takeIf { it > 0L } ?: return@resolveField false
                    kotlin.math.abs(value * 1_000L - expectedMs) <= 2_000L
                }
                require(rid.type == Long::class.javaPrimitiveType)
                require(title.type == String::class.java)
                require(artist.type == String::class.java)
                require(album == null || album.type == String::class.java)
                require(duration.type == Int::class.javaPrimitiveType)
                return MusicFieldAccessors(ownerClass, rid, title, artist, album, duration)
            }

            private fun List<Field>.resolveField(
                preferredName: String,
                matches: (Field) -> Boolean,
            ): Field = resolveOptionalField(preferredName, matches)
                ?: error("酷我 Music 字段语义解析失败: preferred=$preferredName")

            private fun List<Field>.resolveOptionalField(
                preferredName: String,
                matches: (Field) -> Boolean,
            ): Field? {
                firstOrNull { it.name == preferredName && runCatching { matches(it) }.getOrDefault(false) }
                    ?.let { return it }
                return filter { field -> runCatching { matches(field) }.getOrDefault(false) }
                    .singleOrNull()
            }

            private fun sameText(value: Any?, expected: String?): Boolean {
                val normalizedExpected = KuwoTrackIdResolver.normalize(expected)
                return normalizedExpected.isNotEmpty() &&
                    KuwoTrackIdResolver.normalize(value as? String) == normalizedExpected
            }
        }
    }

    companion object {
        fun queries(application: Application): List<OfficialProviderDexMethodQuery> {
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val profile = KuwoHookProfiles.resolve(
                packageInfo.versionName.orEmpty(),
                packageInfo.longVersionCode,
            )
            return queries(profile)
        }

        internal fun queries(profile: KuwoHookProfile): List<OfficialProviderDexMethodQuery> {
            fun target(methodName: String, returnTypeName: String, isStatic: Boolean = false) =
                OfficialProviderMethodTarget(
                    className = profile.playback.managerClassName,
                    methodName = methodName,
                    returnTypeName = returnTypeName,
                    isStatic = isStatic,
                )
            val managerType = OfficialProviderDexTypeReference(
                queryCacheKey = "kuwo-next-content-v3",
                source = OfficialProviderDexTypeSource.DECLARING_CLASS,
            )
            return listOf(
                OfficialProviderDexMethodQuery(
                    cacheKey = managerType.queryCacheKey,
                    preferredTarget = target(
                        profile.playback.nextContentMethodName,
                        profile.playback.contentClassName,
                    ),
                    declaringClassNamePrefix = "cn.kuwo.",
                    requiredStrings = listOf(
                        "随机模式，获取歌曲下一曲,随机索引空，现在生成",
                    ),
                    parameterTypeNames = emptyList(),
                    returnTypeName = profile.playback.contentClassName,
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "kuwo-player-singleton-v3",
                    preferredTarget = target(
                        profile.playback.singletonMethodName,
                        profile.playback.managerClassName,
                        isStatic = true,
                    ),
                    declaringClassReference = managerType,
                    parameterTypeNames = emptyList(),
                    returnTypeMatchesDeclaringClass = true,
                    isStatic = true,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "kuwo-current-music-v3",
                    preferredTarget = target(
                        profile.playback.currentMusicMethodName,
                        profile.playback.musicClassName,
                    ),
                    declaringClassReference = managerType,
                    parameterTypeNames = emptyList(),
                    returnTypeName = profile.playback.musicClassName,
                    isStatic = false,
                ),
            )
        }

        fun create(
            application: Application,
            targets: List<OfficialProviderMethodTarget>,
        ): KuwoNextTrackResolver {
            require(Looper.myLooper() == Looper.getMainLooper()) {
                "酷我下一首解析器必须在主线程创建"
            }
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val profile = KuwoHookProfiles.resolve(
                packageInfo.versionName.orEmpty(),
                packageInfo.longVersionCode,
            )
            require(targets.size == 3) { "酷我下一首目标数量错误" }
            val loader = application.classLoader
            val contentClass = loader.loadClass(profile.playback.contentClassName)
            val methods = targets.map { it.toMethod(loader) }
            val nextContentMethod = methods[0]
            val singletonMethod = methods[1]
            val currentMusicMethod = methods[2]
            val managerClass = singletonMethod.returnType

            require(Modifier.isStatic(singletonMethod.modifiers))
            require(singletonMethod.returnType == managerClass)
            require(contentClass.isAssignableFrom(currentMusicMethod.returnType))
            require(nextContentMethod.returnType == contentClass)

            return KuwoNextTrackResolver(
                singletonMethod = singletonMethod,
                currentMusicMethod = currentMusicMethod,
                nextContentMethod = nextContentMethod,
                preferredFields = profile.music,
            )
        }

        private fun Field.accessible(): Field = apply { isAccessible = true }

        private fun Method.accessible(): Method = apply { isAccessible = true }

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
            return clazz.getDeclaredMethod(methodName, *parameters).accessible()
        }
    }
}
