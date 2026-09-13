/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal enum class SaltPlayerLocalLyricsSource {
    SIDECAR_LRC,
    EMBEDDED,
}

internal data class SaltPlayerLocalLyricsResult(
    val document: SaltPlayerLyricsDocument,
    val source: SaltPlayerLocalLyricsSource,
    val mediaUri: String,
)

internal data class SaltPlayerMediaCandidate(
    val mediaUri: String,
    val mediaId: Long?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val displayName: String?,
    val relativePath: String?,
    val dataPath: String?,
)

/**
 * Conservatively identifies a local MediaStore item from standard MediaSession metadata.
 *
 * A title and duration match are mandatory because Salt Player 12.1.1 does not publish a
 * MediaStore id or URI. If several local files remain plausible, the selector refuses to
 * guess unless artist/album/file-name evidence gives one candidate a clear lead.
 */
internal object SaltPlayerMediaCandidateSelector {
    fun select(
        metadata: SaltPlayerTrackMetadata,
        candidates: List<SaltPlayerMediaCandidate>,
    ): SaltPlayerMediaCandidate? {
        val scored = candidates.mapNotNull { candidate ->
            score(metadata, candidate)?.let { score -> ScoredCandidate(candidate, score) }
        }.sortedWith(
            compareByDescending<ScoredCandidate> { it.score }
                .thenBy { durationDelta(metadata, it.candidate) }
                .thenBy { it.candidate.mediaId ?: Long.MAX_VALUE },
        )
        val best = scored.firstOrNull() ?: return null
        val runnerUp = scored.getOrNull(1) ?: return best.candidate
        return best.candidate.takeIf { best.score - runnerUp.score >= MIN_SCORE_LEAD }
    }

    internal fun isHighConfidenceMatch(
        metadata: SaltPlayerTrackMetadata,
        candidate: SaltPlayerMediaCandidate,
    ): Boolean = score(metadata, candidate) != null

    private fun score(
        metadata: SaltPlayerTrackMetadata,
        candidate: SaltPlayerMediaCandidate,
    ): Int? {
        val expectedTitle = normalize(metadata.title)
        val candidateTitle = normalize(candidate.title)
        if (expectedTitle.isEmpty() || candidateTitle != expectedTitle) return null

        val expectedDuration = metadata.durationMs
        val candidateDuration = candidate.durationMs
        if (expectedDuration <= 0L || candidateDuration <= 0L) return null
        val durationDelta = abs(candidateDuration - expectedDuration)
        if (durationDelta > MAX_DURATION_DELTA_MS) return null

        var score = 100
        score += 40 - (durationDelta * 20L / MAX_DURATION_DELTA_MS).toInt()
        if (sameNonBlank(metadata.artist, candidate.artist)) score += 30
        if (sameNonBlank(metadata.album, candidate.album)) score += 20
        if (normalize(candidate.displayName?.substringBeforeLast('.')) == expectedTitle) score += 10
        return score
    }

    private fun durationDelta(
        metadata: SaltPlayerTrackMetadata,
        candidate: SaltPlayerMediaCandidate,
    ): Long = if (metadata.durationMs > 0L && candidate.durationMs > 0L) {
        abs(metadata.durationMs - candidate.durationMs)
    } else {
        Long.MAX_VALUE
    }

    private fun sameNonBlank(left: String?, right: String?): Boolean {
        val normalizedLeft = normalize(left)
        return normalizedLeft.isNotEmpty() && normalizedLeft == normalize(right)
    }

    private fun normalize(value: String?): String = Normalizer
        .normalize(value.orEmpty(), Normalizer.Form.NFKC)
        .replace(ZERO_WIDTH, "")
        .trim()
        .replace(WHITESPACE, " ")
        .lowercase(Locale.ROOT)

    private data class ScoredCandidate(
        val candidate: SaltPlayerMediaCandidate,
        val score: Int,
    )

    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_DURATION_DELTA_MS = 2_000L
    private const val MIN_SCORE_LEAD = 15
}

/** Reads sidecar or embedded lyrics without loading any Salt Player implementation class. */
internal class SaltPlayerMediaStoreResolver(application: Application) {
    private val contentResolver = application.contentResolver

    fun load(metadata: SaltPlayerTrackMetadata): SaltPlayerLocalLyricsResult? {
        val media = resolveMedia(metadata) ?: return null

        readSidecarLyrics(media)?.let { text ->
            SaltPlayerLrcParser.parse(text, metadata.durationMs)?.let { document ->
                return SaltPlayerLocalLyricsResult(
                    document = document,
                    source = SaltPlayerLocalLyricsSource.SIDECAR_LRC,
                    mediaUri = media.mediaUri,
                )
            }
        }

        readEmbeddedLyrics(media)?.let { text ->
            SaltPlayerLrcParser.parse(text, metadata.durationMs)?.let { document ->
                return SaltPlayerLocalLyricsResult(
                    document = document,
                    source = SaltPlayerLocalLyricsSource.EMBEDDED,
                    mediaUri = media.mediaUri,
                )
            }
        }
        return null
    }

    private fun resolveMedia(metadata: SaltPlayerTrackMetadata): SaltPlayerMediaCandidate? {
        resolvePublishedUri(metadata)?.let { return it }
        resolvePublishedMediaId(metadata)?.let { return it }

        metadata.title?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (metadata.durationMs <= 0L) return null
        val lowerDuration = (metadata.durationMs - QUERY_DURATION_WINDOW_MS).coerceAtLeast(0L)
        val upperDuration = metadata.durationMs + QUERY_DURATION_WINDOW_MS
        val candidates = queryCandidates(
            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            selection = "${MediaStore.Audio.Media.DURATION} BETWEEN ? AND ?",
            selectionArgs = arrayOf(lowerDuration.toString(), upperDuration.toString()),
        )
        return SaltPlayerMediaCandidateSelector.select(metadata, candidates)
    }

    private fun resolvePublishedUri(metadata: SaltPlayerTrackMetadata): SaltPlayerMediaCandidate? {
        val uri = metadata.mediaUri?.trim()?.takeIf(String::isNotEmpty)?.let(Uri::parse)
            ?: return null
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            ContentResolver.SCHEME_CONTENT -> queryCandidates(uri).singleOrNull()
                ?: SaltPlayerMediaCandidate(
                    mediaUri = uri.toString(),
                    mediaId = null,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    durationMs = metadata.durationMs,
                    displayName = uri.lastPathSegment,
                    relativePath = null,
                    dataPath = null,
                )
            ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.takeIf(File::isFile)?.let { file ->
                SaltPlayerMediaCandidate(
                    mediaUri = uri.toString(),
                    mediaId = null,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    durationMs = metadata.durationMs,
                    displayName = file.name,
                    relativePath = null,
                    dataPath = file.absolutePath,
                )
            }
            else -> null
        }
    }

    private fun resolvePublishedMediaId(metadata: SaltPlayerTrackMetadata): SaltPlayerMediaCandidate? {
        val mediaId = metadata.id?.trim()?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
        return queryCandidates(uri).singleOrNull()?.takeIf {
            SaltPlayerMediaCandidateSelector.isHighConfidenceMatch(metadata, it)
        }
    }

    private fun queryCandidates(
        uri: Uri,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): List<SaltPlayerMediaCandidate> = runCatching {
        contentResolver.query(
            uri,
            AUDIO_PROJECTION,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toMediaCandidate(uri))
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun Cursor.toMediaCandidate(queryUri: Uri): SaltPlayerMediaCandidate {
        val id = longOrNull(BaseColumns._ID)
        val queryAlreadyTargetsItem = queryUri.lastPathSegment?.toLongOrNull() != null
        val itemUri = if (id != null && !queryAlreadyTargetsItem) {
            ContentUris.withAppendedId(queryUri, id)
        } else {
            queryUri
        }
        return SaltPlayerMediaCandidate(
            mediaUri = itemUri.toString(),
            mediaId = id,
            title = stringOrNull(MediaStore.Audio.Media.TITLE),
            artist = stringOrNull(MediaStore.Audio.Media.ARTIST),
            album = stringOrNull(MediaStore.Audio.Media.ALBUM),
            durationMs = longOrNull(MediaStore.Audio.Media.DURATION) ?: 0L,
            displayName = stringOrNull(MediaStore.Audio.Media.DISPLAY_NAME),
            relativePath = stringOrNull(MediaStore.Audio.Media.RELATIVE_PATH),
            dataPath = stringOrNull(MediaStore.Audio.Media.DATA),
        )
    }

    private fun readSidecarLyrics(media: SaltPlayerMediaCandidate): String? {
        val displayName = media.displayName?.takeIf { it.contains('.') } ?: return null
        val sidecarName = displayName.substringBeforeLast('.') + ".lrc"

        runCatching {
            media.dataPath?.let(::File)?.parentFile?.listFiles()?.firstOrNull {
                it.isFile && it.name.equals(sidecarName, ignoreCase = true)
            }?.let { file -> readBounded(FileInputStream(file)) }
        }.getOrNull()?.let { return it }

        val relativePath = media.relativePath ?: return null
        val mediaUri = Uri.parse(media.mediaUri)
        val volumeName = runCatching { MediaStore.getVolumeName(mediaUri) }
            .getOrDefault(MediaStore.VOLUME_EXTERNAL)
        val filesUri = MediaStore.Files.getContentUri(volumeName)
        return runCatching {
            contentResolver.query(
                filesUri,
                arrayOf(BaseColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? COLLATE NOCASE",
                arrayOf(relativePath, sidecarName),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID))
                readTextUri(ContentUris.withAppendedId(filesUri, id))
            }
        }.getOrNull()
    }

    private fun readEmbeddedLyrics(media: SaltPlayerMediaCandidate): String? {
        media.dataPath?.let(::File)?.takeIf(File::isFile)?.let { file ->
            runCatching {
                FileInputStream(file).use { input ->
                    SaltPlayerEmbeddedLyricsReader.read(input.channel, file.name)
                }
            }.getOrNull()?.let { return it }
        }

        val uri = Uri.parse(media.mediaUri)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return runCatching {
            val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                SaltPlayerEmbeddedLyricsReader.read(
                    channel = input.channel,
                    fileName = media.displayName.orEmpty(),
                )
            }
        }.getOrNull()
    }

    private fun readTextUri(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.let(::readBounded)
    }.getOrNull()

    private fun readBounded(input: InputStream): String? = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_LRC_BYTES) return@use null
            output.write(buffer, 0, count)
        }
        SaltPlayerEmbeddedLyricsReader.decodeBestEffort(output.toByteArray())
    }

    private fun Cursor.stringOrNull(columnName: String): String? =
        getColumnIndex(columnName).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.longOrNull(columnName: String): Long? =
        getColumnIndex(columnName).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    private companion object {
        val AUDIO_PROJECTION = arrayOf(
            BaseColumns._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATA,
        )

        const val QUERY_DURATION_WINDOW_MS = 2_000L
        const val MAX_LRC_BYTES = 4 * 1024 * 1024
    }
}
