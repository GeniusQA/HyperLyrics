/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

internal data class SpotifyTrackMetadata(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)

internal data class SpotifyTrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal data class SpotifyQueueSnapshot(
    val current: SpotifyTrackSnapshot,
    val next: SpotifyTrackSnapshot?,
)

internal object SpotifyTrackIdentity {
    private val base62TrackId = Regex("""[A-Za-z0-9]{22}""")

    fun candidates(value: String?): Set<String> {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return emptySet()
        return buildSet {
            add(raw)
            raw.substringAfterLast(':').substringBefore('?').takeIf(String::isNotBlank)?.let(::add)
            base62TrackId.findAll(raw).forEach { add(it.value) }
        }
    }

    fun normalize(value: String?): String = value.orEmpty()
        .lowercase()
        .filter(Char::isLetterOrDigit)

    fun requestUri(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val trackId = when {
            base62TrackId.matches(raw) -> raw
            raw.startsWith("spotify:track:") -> raw.substringAfter("spotify:track:")
                .substringBefore('?')
                .takeIf(base62TrackId::matches)
            "/track/" in raw -> raw.substringAfter("/track/")
                .substringBefore('?')
                .substringBefore('/')
                .takeIf(base62TrackId::matches)
            else -> null
        } ?: return null
        return "spotify:track:$trackId"
    }

    fun sameTrack(first: SpotifyTrackMetadata?, second: SpotifyTrackMetadata): Boolean {
        first ?: return false
        val firstIds = candidates(first.mediaId)
        val secondIds = candidates(second.mediaId)
        if (firstIds.isNotEmpty() && secondIds.isNotEmpty()) {
            return firstIds.intersect(secondIds).isNotEmpty()
        }

        val firstTitle = normalize(first.title)
        val secondTitle = normalize(second.title)
        if (firstTitle.isEmpty() || firstTitle != secondTitle) return false
        val firstArtist = normalize(first.artist)
        val secondArtist = normalize(second.artist)
        return firstArtist.isEmpty() || secondArtist.isEmpty() || firstArtist == secondArtist
    }
}

internal object SpotifyQueueBinding {
    fun align(
        metadata: SpotifyTrackMetadata?,
        snapshot: SpotifyQueueSnapshot?,
    ): SpotifyQueueSnapshot? {
        if (metadata == null || snapshot == null) return null
        val metadataIds = SpotifyTrackIdentity.candidates(metadata.mediaId)
        val queueIds = SpotifyTrackIdentity.candidates(snapshot.current.id)
        if (metadataIds.intersect(queueIds).isNotEmpty()) return snapshot

        val metadataTitle = SpotifyTrackIdentity.normalize(metadata.title)
        val queueTitle = SpotifyTrackIdentity.normalize(snapshot.current.title)
        if (metadataTitle.isEmpty() || metadataTitle != queueTitle) return null
        val metadataArtist = SpotifyTrackIdentity.normalize(metadata.artist)
        val queueArtist = SpotifyTrackIdentity.normalize(snapshot.current.artist)
        return snapshot.takeIf {
            metadataArtist.isEmpty() || queueArtist.isEmpty() || metadataArtist == queueArtist
        }
    }
}

internal object SpotifyQueueExtractor {
    fun extract(playerState: Any?): SpotifyQueueSnapshot? {
        playerState ?: return null
        if (!isPlayerState(playerState.javaClass)) return null
        val current = playerState.invokeNoArg("track").unwrapOptional()
            ?.let(::decodeContextTrack)
            ?: return null
        val next = playerState.invokeNoArg("nextTracks").asObjectSequence()
            .mapNotNull(::decodeContextTrack)
            .firstOrNull { candidate ->
                candidate.id !in DELIMITER_URIS && !sameTrack(current, candidate)
            }
        return SpotifyQueueSnapshot(current = current, next = next)
    }

    private fun decodeContextTrack(value: Any): SpotifyTrackSnapshot? {
        val uri = value.invokeNoArg("uri")?.toString()?.trim().orEmpty()
        if (uri.isBlank()) return null
        val metadata = value.invokeNoArg("metadata")
        return SpotifyTrackSnapshot(
            id = uri,
            title = metadata.metadataValue("title"),
            artist = metadata.metadataValue("artist_name"),
            album = metadata.metadataValue("album_title"),
            durationMs = metadata.metadataValue("duration").toLongOrNull()
                ?.takeIf { it > 0L } ?: -1L,
        )
    }

    private fun sameTrack(first: SpotifyTrackSnapshot, second: SpotifyTrackSnapshot): Boolean {
        if (first.id.isNotBlank() && second.id.isNotBlank()) return first.id == second.id
        return SpotifyTrackIdentity.normalize(first.title) == SpotifyTrackIdentity.normalize(second.title) &&
            SpotifyTrackIdentity.normalize(first.artist) == SpotifyTrackIdentity.normalize(second.artist)
    }

    private fun isPlayerState(type: Class<*>?): Boolean = generateSequence(type) { it.superclass }
        .any { it.name == SpotifyHookProfiles.PLAYER_STATE_CLASS }

    private fun Any?.unwrapOptional(): Any? {
        this ?: return null
        listOf("h", "b", "orNull", "get").forEach { name ->
            runCatching { javaClass.getMethod(name).invoke(this) }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    private fun Any?.asObjectSequence(): Sequence<Any> = when (this) {
        is Iterable<*> -> asSequence().filterNotNull()
        is Array<*> -> asSequence().filterNotNull()
        else -> emptySequence()
    }

    private fun Any?.metadataValue(key: String): String {
        this ?: return ""
        if (this is Map<*, *>) return get(key)?.toString().orEmpty()
        return runCatching {
            javaClass.getMethod("get", Any::class.java).invoke(this, key)?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun Any.invokeNoArg(name: String): Any? = runCatching {
        javaClass.getMethod(name).invoke(this)
    }.getOrNull()

    private val DELIMITER_URIS = setOf("spotify:delimiter", "spotify:meta:delimiter")
}
