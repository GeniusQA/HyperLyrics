/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import android.app.Application
import android.os.Looper
import com.genius.hyperlyrics.provider.OfficialProviderDexMethodQuery
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal data class SaltPlayerTrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal data class SaltPlayerQueueStateSnapshot(
    val mode: SaltPlayerPlaybackMode,
    val normalQueue: List<SaltPlayerTrackSnapshot>,
    val normalIndex: Int,
    val randomQueue: List<SaltPlayerTrackSnapshot>,
    val randomIndex: Int,
    val readyToSave: Boolean,
)

internal data class SaltPlayerNextTrackResolution(
    val queueDecoded: Boolean,
    val nextTrack: SaltPlayerTrackSnapshot?,
    val detail: String,
)

internal enum class SaltPlayerPlaybackMode {
    CIRCLE,
    CIRCLE_END,
    REPEAT_ONE,
    RANDOM,
}

internal object SaltPlayerNextTrackSelector {
    fun select(
        state: SaltPlayerQueueStateSnapshot,
        currentId: String?,
        currentFallback: SaltPlayerTrackSnapshot? = null,
    ): SaltPlayerTrackSnapshot? {
        if (state.mode == SaltPlayerPlaybackMode.REPEAT_ONE) {
            return currentFallback?.takeIf { it.title.isNotBlank() }
                ?: currentQueueItem(state, currentId)
        }

        val random = state.mode == SaltPlayerPlaybackMode.RANDOM
        val queue = if (random) state.randomQueue else state.normalQueue
        if (queue.isEmpty()) return null
        val configuredIndex = if (random) state.randomIndex else state.normalIndex
        val currentIndex = resolveCurrentIndex(queue, configuredIndex, currentId)
        if (currentIndex !in queue.indices) return null
        if (random && queue.size > 1 && currentIndex == queue.lastIndex) return null
        return queue[(currentIndex + 1) % queue.size]
    }

    private fun currentQueueItem(
        state: SaltPlayerQueueStateSnapshot,
        currentId: String?,
    ): SaltPlayerTrackSnapshot? {
        val random = state.mode == SaltPlayerPlaybackMode.RANDOM
        val queue = if (random) state.randomQueue else state.normalQueue
        val configuredIndex = if (random) state.randomIndex else state.normalIndex
        return queue.getOrNull(resolveCurrentIndex(queue, configuredIndex, currentId))
    }

    private fun resolveCurrentIndex(
        queue: List<SaltPlayerTrackSnapshot>,
        configuredIndex: Int,
        currentId: String?,
    ): Int {
        val normalizedId = currentId?.trim().orEmpty()
        if (normalizedId.isEmpty()) return configuredIndex
        if (queue.getOrNull(configuredIndex)?.id == normalizedId) return configuredIndex
        return queue.indexOfFirst { it.id == normalizedId }
    }
}

/**
 * Read-only projection of Salt Player's queue StateFlow.
 *
 * Every obfuscated class and field is derived from the live object graph. The
 * only named members are stable Kotlin/Java contracts and Song getters.
 */
internal class SaltPlayerNextTrackResolver private constructor(
    private val stateFlowFields: List<Field>,
    private val stateFlowValueMethod: Method,
    private val profile: SaltPlayerHookProfile,
) {
    private val stateAccessors = ConcurrentHashMap<Class<*>, StateAccessors>()
    private val itemAccessors = ConcurrentHashMap<Class<*>, ItemAccessors>()

    fun resolve(current: SaltPlayerTrackMetadata): SaltPlayerTrackSnapshot? {
        requireMainThread()
        val snapshot = resolveQueueSnapshot(current) ?: return null
        return SaltPlayerNextTrackSelector.select(
            state = snapshot,
            currentId = current.id,
            currentFallback = current.toSnapshot(),
        )
    }

    fun resolveWithValidation(current: SaltPlayerTrackMetadata): SaltPlayerNextTrackResolution {
        requireMainThread()
        val snapshot = resolveQueueSnapshot(current)
            ?: return SaltPlayerNextTrackResolution(
                queueDecoded = false,
                nextTrack = null,
                detail = "queue_state_unavailable_or_invalid",
            )
        val next = SaltPlayerNextTrackSelector.select(
            state = snapshot,
            currentId = current.id,
            currentFallback = current.toSnapshot(),
        )
        return SaltPlayerNextTrackResolution(
            queueDecoded = true,
            nextTrack = next,
            detail = "mode=${snapshot.mode}, normal=${snapshot.normalQueue.size}, " +
                "random=${snapshot.randomQueue.size}, next=${next?.id}",
        )
    }

    private fun resolveQueueSnapshot(
        current: SaltPlayerTrackMetadata,
    ): SaltPlayerQueueStateSnapshot? {
        val state = stateFlowFields.asSequence()
            .mapNotNull { field ->
                val flow = runCatching { field.get(null) }.getOrNull() ?: return@mapNotNull null
                runCatching { stateFlowValueMethod.invoke(flow) }.getOrNull()
            }
            .firstOrNull { value -> StateAccessors.supports(value.javaClass) }
            ?: return null
        val accessors = stateAccessors.computeIfAbsent(state.javaClass, StateAccessors::create)
        return accessors.decode(
            state = state,
            current = current,
            profile = profile,
            itemAccessors = itemAccessors,
        )
    }

    private fun requireMainThread() {
        require(Looper.myLooper() == Looper.getMainLooper()) {
            "Salt Player 下一首解析必须在主线程执行"
        }
    }

    private data class StateAccessors(
        val modeField: Field,
        val normalQueueField: Field,
        val normalIndexField: Field,
        val randomQueueField: Field,
        val randomIndexField: Field,
        val readyToSaveField: Field?,
    ) {
        fun decode(
            state: Any,
            current: SaltPlayerTrackMetadata,
            profile: SaltPlayerHookProfile,
            itemAccessors: ConcurrentHashMap<Class<*>, ItemAccessors>,
        ): SaltPlayerQueueStateSnapshot? {
            val mode = modeField.get(state) as? Enum<*> ?: return null
            val normalQueue = decodeQueue(normalQueueField.get(state), profile, itemAccessors)
                ?: return null
            val randomQueue = decodeQueue(randomQueueField.get(state), profile, itemAccessors)
                ?: return null
            val normalIndex = (normalIndexField.get(state) as? Number)?.toInt() ?: return null
            val randomIndex = (randomIndexField.get(state) as? Number)?.toInt() ?: return null
            return SaltPlayerQueueStateSnapshot(
                mode = decodeMode(mode.name, profile.queue) ?: return null,
                normalQueue = normalQueue,
                normalIndex = alignIndex(normalQueue, normalIndex, current.id),
                randomQueue = randomQueue,
                randomIndex = alignIndex(randomQueue, randomIndex, current.id),
                readyToSave = readyToSaveField?.get(state) as? Boolean ?: false,
            )
        }

        companion object {
            fun supports(clazz: Class<*>): Boolean {
                val fields = instanceFields(clazz)
                return fields.count { it.type.isEnum } == 1 &&
                    fields.count { List::class.java.isAssignableFrom(it.type) } >= 2 &&
                    fields.count { it.type == Int::class.javaPrimitiveType } >= 2
            }

            fun create(clazz: Class<*>): StateAccessors {
                val fields = instanceFields(clazz)
                val modeField = fields.single { it.type.isEnum }
                val queueFields = fields.filter { List::class.java.isAssignableFrom(it.type) }
                val indexFields = fields.filter { it.type == Int::class.javaPrimitiveType }
                require(queueFields.size >= 2 && indexFields.size >= 2)

                val orderedPairs = queueFields.mapNotNull { queueField ->
                    val queuePosition = fields.indexOf(queueField)
                    indexFields.firstOrNull { fields.indexOf(it) > queuePosition }
                        ?.let { indexField -> queueField to indexField }
                }.distinctBy { it.second }
                val normalPair = orderedPairs.getOrNull(0) ?: (queueFields[0] to indexFields[0])
                val randomPair = orderedPairs.getOrNull(1) ?: (queueFields[1] to indexFields[1])
                return StateAccessors(
                    modeField = modeField,
                    normalQueueField = normalPair.first,
                    normalIndexField = normalPair.second,
                    randomQueueField = randomPair.first,
                    randomIndexField = randomPair.second,
                    readyToSaveField = fields.singleOrNull {
                        it.type == Boolean::class.javaPrimitiveType
                    },
                )
            }

            private fun decodeMode(
                value: String,
                profile: SaltPlayerQueueHookProfile,
            ): SaltPlayerPlaybackMode? = when (value) {
                profile.circleModeName -> SaltPlayerPlaybackMode.CIRCLE
                profile.circleEndModeName -> SaltPlayerPlaybackMode.CIRCLE_END
                profile.repeatOneModeName -> SaltPlayerPlaybackMode.REPEAT_ONE
                profile.randomModeName -> SaltPlayerPlaybackMode.RANDOM
                else -> null
            }

            private fun decodeQueue(
                value: Any?,
                profile: SaltPlayerHookProfile,
                accessors: ConcurrentHashMap<Class<*>, ItemAccessors>,
            ): List<SaltPlayerTrackSnapshot>? {
                val queue = value as? List<*> ?: return null
                return buildList(queue.size) {
                    queue.forEach { item ->
                        item ?: return@forEach
                        val itemAccessor = accessors.computeIfAbsent(item.javaClass) { clazz ->
                            ItemAccessors.create(clazz, item, profile.song)
                        }
                        add(itemAccessor.decode(item) ?: return null)
                    }
                }
            }

            private fun alignIndex(
                queue: List<SaltPlayerTrackSnapshot>,
                configuredIndex: Int,
                currentId: String?,
            ): Int {
                val id = currentId?.trim().orEmpty()
                if (id.isEmpty() || queue.getOrNull(configuredIndex)?.id == id) {
                    return configuredIndex
                }
                return queue.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: configuredIndex
            }
        }
    }

    private data class ItemAccessors(
        val songField: Field?,
        val idMethod: Method,
        val titleMethod: Method,
        val artistMethod: Method,
        val albumMethod: Method,
        val durationMethod: Method,
    ) {
        fun decode(item: Any): SaltPlayerTrackSnapshot? {
            val song = songField?.get(item) ?: item
            return SaltPlayerTrackSnapshot(
                id = idMethod.invoke(song) as? String ?: "",
                title = titleMethod.invoke(song) as? String ?: "",
                artist = artistMethod.invoke(song) as? String ?: "",
                album = albumMethod.invoke(song) as? String ?: "",
                durationMs = (durationMethod.invoke(song) as? Number)?.toLong() ?: -1L,
            )
        }

        companion object {
            fun create(
                itemClass: Class<*>,
                sample: Any,
                songProfile: SaltPlayerSongHookProfile,
            ): ItemAccessors {
                val songField = instanceFields(itemClass).firstOrNull { field ->
                    val value = runCatching { field.get(sample) }.getOrNull() ?: return@firstOrNull false
                    hasSongContract(value.javaClass, songProfile)
                }
                val songClass = songField?.get(sample)?.javaClass
                    ?: itemClass.takeIf { hasSongContract(it, songProfile) }
                    ?: error("Salt Player 队列项未找到 Song 运行对象")
                return ItemAccessors(
                    songField = songField,
                    idMethod = songClass.getMethod(songProfile.idGetterName).accessible(),
                    titleMethod = songClass.getMethod(songProfile.titleGetterName).accessible(),
                    artistMethod = songClass.getMethod(songProfile.artistGetterName).accessible(),
                    albumMethod = songClass.getMethod(songProfile.albumGetterName).accessible(),
                    durationMethod = songClass.getMethod(songProfile.durationGetterName).accessible(),
                )
            }

            private fun hasSongContract(
                clazz: Class<*>,
                profile: SaltPlayerSongHookProfile,
            ): Boolean = listOf(
                profile.idGetterName,
                profile.titleGetterName,
                profile.artistGetterName,
                profile.albumGetterName,
                profile.durationGetterName,
            ).all { name -> clazz.methods.any { method -> method.name == name && method.parameterCount == 0 } }
        }
    }

    companion object {
        const val CONTROLLER_FALLBACK_CACHE_KEY = "salt-player-music-controller-v1"

        /**
         * Original Salt Player 12.1.1 DEX contains exactly one matching method:
         * MusicController.<obfuscated>(Song, long, long, Long): void.
         * The method is used only to recover its declaring class; queue fields are
         * still selected and validated from the live StateFlow object structure.
         */
        fun controllerFallbackQuery(
            profile: SaltPlayerHookProfile,
        ): OfficialProviderDexMethodQuery = OfficialProviderDexMethodQuery(
            cacheKey = CONTROLLER_FALLBACK_CACHE_KEY,
            declaringClassNamePrefix =
                profile.musicControllerClassName.substringBeforeLast('.') + ".",
            parameterTypeNames = listOf(
                profile.song.className,
                "long",
                "long",
                "java.lang.Long",
            ),
            returnTypeName = "void",
            isStatic = true,
        )

        fun create(
            application: Application,
            profile: SaltPlayerHookProfile,
            controllerClassName: String = profile.musicControllerClassName,
        ): SaltPlayerNextTrackResolver {
            require(Looper.myLooper() == Looper.getMainLooper()) {
                "Salt Player 下一首解析器必须在主线程创建"
            }
            val loader = application.classLoader
            val controllerClass = loadInitializedClass(controllerClassName, loader)
            val stateFlowClass = loader.loadClass(profile.stateFlowClassName)
            val stateFlowFields = controllerClass.declaredFields
                .filter { field ->
                    Modifier.isStatic(field.modifiers) && stateFlowClass.isAssignableFrom(field.type)
                }
                .onEach { it.isAccessible = true }
            require(stateFlowFields.isNotEmpty()) { "Salt Player 未找到队列 StateFlow" }
            val stateFlowValueMethod = stateFlowClass.getMethod("getValue").accessible()
            return SaltPlayerNextTrackResolver(stateFlowFields, stateFlowValueMethod, profile)
        }

        internal fun loadInitializedClass(
            className: String,
            classLoader: ClassLoader,
        ): Class<*> = Class.forName(className, true, classLoader)
    }
}

private fun SaltPlayerTrackMetadata.toSnapshot(): SaltPlayerTrackSnapshot? {
    val resolvedTitle = title?.trim().orEmpty()
    if (resolvedTitle.isEmpty()) return null
    return SaltPlayerTrackSnapshot(
        id = id?.trim().orEmpty(),
        title = resolvedTitle,
        artist = artist?.trim().orEmpty(),
        album = album?.trim().orEmpty(),
        durationMs = durationMs.takeIf { it >= 0L } ?: -1L,
    )
}

private fun instanceFields(clazz: Class<*>): List<Field> =
    generateSequence(clazz) { current -> current.superclass }
        .flatMap { current -> current.declaredFields.asSequence() }
        .filterNot { field -> Modifier.isStatic(field.modifiers) }
        .onEach { field -> field.isAccessible = true }
        .toList()

private fun Method.accessible(): Method = apply { isAccessible = true }
