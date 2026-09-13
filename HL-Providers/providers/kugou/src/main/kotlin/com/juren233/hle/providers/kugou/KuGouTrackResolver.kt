/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class KuGouTrackMetadata(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
) {
    val identity: String
        get() = mediaId?.takeIf(String::isNotBlank)
            ?: sha256Hex("${title.orEmpty()}\u0000${artist.orEmpty()}\u0000$durationMs")

    val directHash: String?
        get() = mediaId?.trim()
            ?.takeIf { KUGOU_HASH.matches(it) }
            ?.uppercase(Locale.ROOT)

    val albumAudioId: Long?
        get() = mediaId?.trim()
            ?.removePrefix("MUSIC_")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

    val isSearchable: Boolean
        get() = !title.isNullOrBlank() || directHash != null || albumAudioId != null

    fun keyword(): String = when {
        !artist.isNullOrBlank() && !title.isNullOrBlank() -> "$artist - $title"
        !title.isNullOrBlank() -> title
        else -> ""
    }.take(200)

    private companion object {
        val KUGOU_HASH = Regex("[0-9a-fA-F]{32}")
    }
}

internal object KuGouTrackUpdatePolicy {
    fun shouldReloadLyrics(
        previous: KuGouTrackMetadata,
        next: KuGouTrackMetadata,
    ): Boolean = previous.identity != next.identity
}

internal data class KuGouSearchCandidate(
    val downloadId: String,
    val accessKey: String,
    val contentType: Int,
    val title: String?,
    val artist: String?,
    val durationMs: Long,
    val serverScore: Int,
)

internal object KuGouCandidateSelector {
    /**
     * 全部通过严格评分门槛的候选，按得分降序、同分保持服务器顺序。
     * 首个元素与旧单候选选择（首个最高分且 ≥ [MINIMUM_SCORE]）完全一致；
     * 无标题、仅凭哈希/专辑音频 ID 直连时保持服务器权威顺序不重排。
     */
    fun rank(
        track: KuGouTrackMetadata,
        candidates: List<KuGouSearchCandidate>,
    ): List<KuGouSearchCandidate> {
        if (track.title.isNullOrBlank() &&
            (track.directHash != null || track.albumAudioId != null)
        ) {
            return candidates
        }
        return candidates
            .map { it to score(track, it) }
            .filter { it.second >= MINIMUM_SCORE }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    internal fun score(track: KuGouTrackMetadata, candidate: KuGouSearchCandidate): Int {
        val wantedTitle = normalize(track.title)
        val candidateTitle = normalize(candidate.title)
        if (wantedTitle.isEmpty() || candidateTitle.isEmpty()) return Int.MIN_VALUE

        var score = when {
            wantedTitle == candidateTitle -> 65
            wantedTitle.contains(candidateTitle) || candidateTitle.contains(wantedTitle) -> 40
            else -> tokenSimilarity(wantedTitle, candidateTitle)
        }

        val wantedArtists = artistTokens(track.artist)
        val candidateArtists = artistTokens(candidate.artist)
        if (wantedArtists.isNotEmpty()) {
            score += when {
                wantedArtists == candidateArtists -> 30
                wantedArtists.intersect(candidateArtists).isNotEmpty() -> 18
                else -> -35
            }
        }

        if (track.durationMs > 0L && candidate.durationMs > 0L) {
            val difference = abs(track.durationMs - candidate.durationMs)
            score += when {
                difference <= 2_000L -> 20
                difference <= 5_000L -> 15
                difference <= 10_000L -> 8
                difference > 30_000L -> -25
                else -> 0
            }
        }

        score += candidate.serverScore.coerceIn(0, 100) / 10
        return score
    }

    internal fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return Normalizer.normalize(
            value.replace("&nbsp;", " ", ignoreCase = true)
                .replace("&amp;", "&", ignoreCase = true)
                .replace("\\u0026", "&", ignoreCase = true),
            Normalizer.Form.NFKC,
        ).lowercase(Locale.ROOT)
            .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    }

    private fun artistTokens(value: String?): Set<String> {
        if (value.isNullOrBlank()) return emptySet()
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .split(Regex("[&/,;、]+"))
            .map(::normalize)
            .filter(String::isNotEmpty)
            .toSet()
    }

    private fun tokenSimilarity(first: String, second: String): Int {
        val firstTokens = first.windowed(size = 2, step = 1, partialWindows = true).toSet()
        val secondTokens = second.windowed(size = 2, step = 1, partialWindows = true).toSet()
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return 0
        val intersection = firstTokens.intersect(secondTokens).size
        val union = firstTokens.union(secondTokens).size
        return intersection * 35 / union
    }

    private const val MINIMUM_SCORE = 70
}

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
