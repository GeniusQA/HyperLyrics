/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import java.text.Normalizer
import kotlin.math.abs

internal data class KuwoTrackMetadata(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
) {
    fun stableSearchKey(): String = listOf(title, artist, album)
        .joinToString("|") { KuwoTrackIdResolver.normalize(it) } + "|${durationMs.coerceAtLeast(0L)}"
}

internal data class KuwoSearchCandidate(
    val rid: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationSeconds: Long,
)

internal object KuwoTrackIdResolver {
    fun directRid(mediaId: String?): Long? = mediaId?.trim()
        ?.removePrefix("MUSIC_")
        ?.toLongOrNull()
        ?.takeIf { it > 0L }

    fun chooseCandidate(
        track: KuwoTrackMetadata,
        candidates: List<KuwoSearchCandidate>,
    ): KuwoSearchCandidate? = candidates
        .map { it to score(track, it) }
        .maxByOrNull { it.second }
        ?.takeIf { it.second >= MINIMUM_SCORE }
        ?.first

    internal fun score(track: KuwoTrackMetadata, candidate: KuwoSearchCandidate): Int {
        val wantedTitle = normalize(track.title)
        val candidateTitle = normalize(candidate.title)
        if (wantedTitle.isEmpty() || candidateTitle.isEmpty()) return Int.MIN_VALUE

        var score = when {
            wantedTitle == candidateTitle -> 65
            wantedTitle.contains(candidateTitle) || candidateTitle.contains(wantedTitle) -> 40
            else -> tokenSimilarity(wantedTitle, candidateTitle)
        }

        val wantedArtist = normalize(track.artist)
        val candidateArtist = normalize(candidate.artist)
        if (wantedArtist.isNotEmpty()) {
            score += when {
                wantedArtist == candidateArtist -> 30
                artistTokens(wantedArtist).intersect(artistTokens(candidateArtist)).isNotEmpty() -> 18
                else -> -35
            }
        }

        val wantedAlbum = normalize(track.album)
        val candidateAlbum = normalize(candidate.album)
        if (wantedAlbum.isNotEmpty() && wantedAlbum == candidateAlbum) score += 10

        if (track.durationMs > 0L && candidate.durationSeconds > 0L) {
            val difference = abs(track.durationMs - candidate.durationSeconds * 1_000L)
            score += when {
                difference <= 2_000L -> 20
                difference <= 5_000L -> 15
                difference <= 10_000L -> 8
                difference > 30_000L -> -25
                else -> 0
            }
        }
        return score
    }

    internal fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return Normalizer.normalize(
            value.replace("&nbsp;", " ", ignoreCase = true)
                .replace("&amp;", "&", ignoreCase = true)
                .replace("\\u0026", "&", ignoreCase = true),
            Normalizer.Form.NFKC,
        ).lowercase()
            .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    }

    private fun tokenSimilarity(first: String, second: String): Int {
        val firstTokens = first.windowed(size = 2, step = 1, partialWindows = true).toSet()
        val secondTokens = second.windowed(size = 2, step = 1, partialWindows = true).toSet()
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return 0
        val intersection = firstTokens.intersect(secondTokens).size
        val union = firstTokens.union(secondTokens).size
        return intersection * 35 / union
    }

    private fun artistTokens(value: String): Set<String> = value
        .split(Regex("[&/,;、]+"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

    private const val MINIMUM_SCORE = 70
}
