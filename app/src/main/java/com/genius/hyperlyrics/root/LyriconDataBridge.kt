package com.genius.hyperlyrics.root

import android.os.SystemClock
import com.genius.hyperlyrics.common.lyric.LyricMetadataKeys
import com.genius.hyperlyrics.lyric.model.RichLyricLine
import com.genius.hyperlyrics.lyric.model.Song
import com.genius.hyperlyrics.lyric.model.extensions.TimingNavigator
import com.genius.hyperlyrics.lyric.model.interfaces.IRichLyricLine
import com.genius.hyperlyrics.lyric.model.lyricMetadataOf
import com.genius.hyperlyrics.lyric.source.StateResetter
import com.genius.hyperlyrics.lyric.view.InterludeTracker
import com.genius.hyperlyrics.lyric.view.SongPreprocessor
import com.genius.hyperlyrics.lyric.view.TimedLine
import com.genius.hyperlyrics.lyric.view.TitleSlot
import com.genius.hyperlyrics.provider.OfficialProviderCatalog
import com.genius.hyperlyrics.root.utils.DisplayDiagnosticLogger
import com.genius.hyperlyrics.root.utils.HookLogger
import com.genius.hyperlyrics.root.utils.MediaCardDiagnosticLogger

object LyriconDataBridge : StateResetter {

    private val playbackPositionEstimator = PlaybackPositionEstimator()

    /** 后续歌词最多缓存条数（渲染层再按用户设置的「歌词行数上限」裁剪）。 */
    private const val MAX_UPCOMING_LYRIC_LINES = 8

    val versionCounter = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var currentSong: Song? = null

    @Volatile
    var currentSongName: String? = null

    @Volatile
    var currentLyric: String? = null

    @Volatile
    var currentLyricLine: IRichLyricLine? = null

    @Volatile
    var currentNextLyricLine: IRichLyricLine? = null

    /**
     * 当前行之后的后续歌词，供多行歌词渲染使用。
     *
     * 只缓存 [MAX_UPCOMING_LYRIC_LINES] 条，渲染层再按用户设置的
     * 「歌词行数上限」裁剪，避免长列表常驻内存。
     */
    @Volatile
    var currentUpcomingLyricLines: List<IRichLyricLine> = emptyList()
        private set

    @Volatile
    private var currentUnmergedLyricLine: IRichLyricLine? = null

    @Volatile
    internal var currentInterludeType: InterludeTracker.Type? = null
        private set

    @Volatile
    var currentPosition: Long = 0L

    @Volatile
    var currentPlaybackState: Boolean? = null
        private set

    @Volatile
    var activePackageName: String? = null

    @Volatile
    var currentLyricPackageName: String? = null

    /** 是否处于纯文本模式（部分 Provider 通过 onSendText 推送） */
    @Volatile
    var isTextMode: Boolean = false

    /** AI 翻译完成后的回调，由 LyriconSource 设置 */
    var onAiTranslationComplete: (() -> Unit)? = null

    fun updateLyricPackage(packageName: String?) {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "package_update_begin",
            details = "incomingPackage=${MediaCardDiagnosticLogger.sanitize(packageName)}",
        )
        activePackageName = packageName
        currentLyricPackageName = packageName
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = if (packageName.isNullOrBlank()) "skipped" else "accepted",
            reason = if (packageName.isNullOrBlank()) "package_missing" else "package_updated",
        )
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "package_update_complete",
            details = "package=${MediaCardDiagnosticLogger.sanitize(packageName)}",
        )
    }

    private var timingNavigator: TimingNavigator<TimedLine> = TimingNavigator(emptyArray())
    private var unmergedTimingNavigator: TimingNavigator<TimedLine> = TimingNavigator(emptyArray())
    private var interludeTracker = InterludeTracker()
    private var currentInterlude: InterludeTracker.Interlude? = null
    private var currentInterludeLine: IRichLyricLine? = null

    fun updateSong(song: Song?) {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "song_update_begin",
            details = "incomingId=${MediaCardDiagnosticLogger.sanitize(song?.id)},incomingTitle=${MediaCardDiagnosticLogger.sanitize(song?.name)},incomingLines=${song?.lyrics.orEmpty().size}",
        )
        HookLogger.d("LyriconDataBridge", "歌曲变更: ${song?.name}")
        isTextMode = false
        currentSong = song
        currentSongName = song?.name
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        currentUpcomingLyricLines = emptyList()
        currentUnmergedLyricLine = null
        currentPosition = 0L
        playbackPositionEstimator.reset()
        currentInterludeType = null
        currentInterlude = null
        currentInterludeLine = null

        versionCounter.incrementAndGet()

        if (song != null) {
            prepareSong(song)
        } else {
            timingNavigator = TimingNavigator(emptyArray())
            unmergedTimingNavigator = TimingNavigator(emptyArray())
            interludeTracker = InterludeTracker()
        }
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = if (song == null) "cleared" else "accepted",
            reason = if (song == null) "song_cleared" else if (song.lyrics.isNullOrEmpty()) {
                "song_without_lyrics"
            } else {
                "song_updated"
            },
        )
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "song_update_complete",
            reason = if (song == null) "cleared" else "prepared",
            details = "incomingId=${MediaCardDiagnosticLogger.sanitize(song?.id)},version=${versionCounter.get()}",
        )
    }

    fun replaceSameSongContent(song: Song): Boolean {
        val previousSong = currentSong ?: return false
        if (!isSameSong(previousSong, song)) return false

        HookLogger.d("LyriconDataBridge", "同曲内容更新: ${song.name}")
        isTextMode = false
        // 同一首歌被重新发布（如播放中再次收到元数据/原生歌词）时，
        // 若新歌词缺失翻译/发音而旧歌词有，则沿位置把旧翻译/发音搬过来，
        // 避免「放着放着翻译整段消失」。
        val mergedSong = mergeLyricEnrichment(previousSong, song)
        currentSong = mergedSong
        currentSongName = mergedSong.name
        prepareSong(mergedSong)
        versionCounter.incrementAndGet()
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = "accepted",
            reason = "same_song_content_replaced",
        )
        return true
    }

    /**
     * 行数一致时，把上一首歌词里已有的翻译/发音沿行序搬运到新歌词，
     * 仅当新行对应字段为空时补充，绝不覆盖已有内容。
     */
    private fun mergeLyricEnrichment(previous: Song, next: Song): Song {
        val prevLines = previous.lyrics
        val nextLines = next.lyrics
        if (prevLines.isNullOrEmpty() || nextLines.isNullOrEmpty()) return next
        if (prevLines.size != nextLines.size) return next
        val merged = prevLines.zip(nextLines).map { (prev, cur) ->
            val translation = if (cur.translation.isNullOrBlank()) prev.translation else cur.translation
            val roma = if (cur.roma.isNullOrBlank()) prev.roma else cur.roma
            if (translation != cur.translation || roma != cur.roma) {
                cur.copy(translation = translation, roma = roma)
            } else {
                cur
            }
        }
        return next.copy(lyrics = merged)
    }

    fun applyTranslation(translatedSong: Song) {
        currentSong = translatedSong
        prepareSong(translatedSong)
    }

    private fun prepareSong(song: Song) {
        val mergeOverlappingLyrics =
            currentLyricPackageName == OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME
        val lines = SongPreprocessor(
            placeholder = TitleSlot.NAME_ARTIST,
            mergeOverlappingLyrics = mergeOverlappingLyrics,
        ).prepare(song)
        val unmergedLines = if (mergeOverlappingLyrics) {
            SongPreprocessor(
                placeholder = TitleSlot.NAME_ARTIST,
                mergeOverlappingLyrics = false,
            ).prepare(song)
        } else {
            lines
        }
        timingNavigator = TimingNavigator(lines.toTypedArray())
        unmergedTimingNavigator = TimingNavigator(unmergedLines.toTypedArray())
        interludeTracker = InterludeTracker(lines)
    }

    fun updatePosition(position: Long): Boolean {
        playbackPositionEstimator.update(position, monotonicTimeMs())
        val changed = applyPosition(position)
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "position_applied",
            details = "position=$position,lyricChanged=$changed",
            positionSample = true,
        )
        return changed
    }

    fun updateEstimatedPosition(position: Long): Boolean = applyPosition(position)

    fun estimatedPosition(): Long? =
        playbackPositionEstimator.estimate(monotonicTimeMs())

    fun updatePlaybackState(isPlaying: Boolean) {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "playback_state_update",
            details = "isPlaying=$isPlaying,previous=$currentPlaybackState",
        )
        currentPlaybackState = isPlaying
        playbackPositionEstimator.setPlaying(isPlaying, monotonicTimeMs())
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = "accepted",
            reason = "playback_state_updated",
        )
    }

    private fun monotonicTimeMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: RuntimeException) {
        // Local JVM tests use android.jar stubs; Android uses the suspend-aware clock above.
        System.nanoTime() / 1_000_000L
    }

    private fun applyPosition(position: Long): Boolean {
        currentPosition = position
        if (isTextMode) {
            DisplayDiagnosticLogger.log("BRIDGE", "skipped", "text_mode")
            return false
        }
        val song = currentSong ?: run {
            DisplayDiagnosticLogger.log("BRIDGE", "skipped", "no_song")
            return false
        }
        val lyrics = song.lyrics
        if (lyrics.isNullOrEmpty()) {
            DisplayDiagnosticLogger.log("BRIDGE", "skipped", "no_lyrics")
            return false
        }

        val foundLine = timingNavigator.lineAtOrPrevious(position)
        currentUnmergedLyricLine = unmergedTimingNavigator.lineAtOrPrevious(position)

        val previousLine = currentLyricLine
        val previousInterlude = currentInterlude
        val interlude = interludeTracker.evaluate(position, foundLine, previousInterlude)
        currentInterlude = interlude
        currentInterludeType = interlude?.type

        val displayLine = if (interlude != null) {
            if (interlude == previousInterlude) {
                currentInterludeLine
            } else {
                RichLyricLine(
                    begin = interlude.start,
                    end = interlude.end - 1L,
                    duration = interlude.duration,
                    metadata = lyricMetadataOf(
                        LyricMetadataKeys.INSTRUMENTAL to "true",
                        LyricMetadataKeys.INSTRUMENTAL_TYPE to interlude.type.name.lowercase()
                    ),
                    text = "•••",
                    words = emptyList()
                ).also { currentInterludeLine = it }
            }
        } else {
            currentInterludeLine = null
            foundLine
        }

        currentLyricLine = displayLine
        currentNextLyricLine = interlude?.next ?: foundLine?.next
        currentUpcomingLyricLines = resolveUpcomingLines(foundLine)
        val newText = displayLine?.text ?: currentLyric ?: ""
        val changed = displayLine !== previousLine || newText != currentLyric

        currentLyric = newText
        if (changed) {
            DisplayDiagnosticLogger.log(
                channel = "BRIDGE",
                result = if (displayLine == null) "skipped" else "accepted",
                reason = if (displayLine == null) "no_line_for_position" else "line_changed",
            )
        }
        return changed
    }

    /**
     * 解析 [anchor] 之后的后续歌词，供多行歌词渲染使用。
     *
     * 优先沿预处理阶段串好的 next 链取；链不可用时回退为按歌词列表顺序取后续行。
     */
    private fun resolveUpcomingLines(anchor: TimedLine?): List<IRichLyricLine> {
        if (anchor == null) return emptyList()
        val chained = ArrayList<IRichLyricLine>(MAX_UPCOMING_LYRIC_LINES)
        var cursor = anchor.next
        while (cursor != null && chained.size < MAX_UPCOMING_LYRIC_LINES) {
            chained.add(cursor.line)
            cursor = cursor.next
        }
        if (chained.isNotEmpty()) return chained

        val lyrics = currentSong?.lyrics ?: return emptyList()
        val index = lyrics.indexOfFirst { it.begin == anchor.begin && it.text == anchor.text }
            .takeIf { it >= 0 }
            ?: lyrics.indexOfFirst { it.begin == anchor.begin }
        if (index < 0) return emptyList()
        val end = (index + 1 + MAX_UPCOMING_LYRIC_LINES).coerceAtMost(lyrics.size)
        if (index + 1 >= end) return emptyList()
        return lyrics.subList(index + 1, end)
    }

    fun updateLyric(text: String?) {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "plain_text_update",
            details = "textLen=${text?.length ?: 0}",
        )
        isTextMode = true
        currentInterlude = null
        currentInterludeLine = null
        currentInterludeType = null
        currentLyric = text
        currentLyricLine = if (!text.isNullOrBlank()) {
            val lines = text.lines()
            RichLyricLine(
                text = lines.first(),
                translation = lines.getOrNull(1)
            )
        } else {
            null
        }
        currentUnmergedLyricLine = currentLyricLine
        currentNextLyricLine = null
        currentUpcomingLyricLines = emptyList()
    }

    fun updateLyricLine(line: IRichLyricLine) {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "lyric_line_update_begin",
            details = "line=${MediaCardDiagnosticLogger.identity(line)},begin=${line.begin},end=${line.end},textLen=${line.text?.length ?: 0}",
        )
        isTextMode = false
        currentInterlude = null
        currentInterludeLine = null
        currentInterludeType = null
        val preparedLine = findPreparedLine(line)
        val expectedLine = timingNavigator.findPreviousEntry(currentPosition)
        val callbackLine = preparedLine ?: line
        if (expectedLine != null && callbackLine.begin < expectedLine.begin) {
            DisplayDiagnosticLogger.log(
                "BRIDGE",
                "skipped",
                "stale_callback_line",
                extra = "callbackBegin=${callbackLine.begin}, expectedBegin=${expectedLine.begin}",
            )
            MediaCardDiagnosticLogger.log(
                stage = "bridge",
                event = "lyric_line_update_dropped",
                reason = "stale_callback_line",
                details = "callbackBegin=${callbackLine.begin},expectedBegin=${expectedLine.begin}",
            )
            return
        }
        if (preparedLine != null && currentPosition >= preparedLine.end) {
            DisplayDiagnosticLogger.log(
                "BRIDGE",
                "skipped",
                "expired_callback_line",
                extra = "callbackEnd=${preparedLine.end}",
            )
            MediaCardDiagnosticLogger.log(
                stage = "bridge",
                event = "lyric_line_update_dropped",
                reason = "expired_callback_line",
                details = "callbackEnd=${preparedLine.end},currentPosition=$currentPosition",
            )
            return
        }

        currentLyricLine = preparedLine ?: line
        currentUnmergedLyricLine = line
        currentNextLyricLine = preparedLine?.next
        currentUpcomingLyricLines = resolveUpcomingLines(preparedLine)
        currentLyric = currentLyricLine?.text
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = "accepted",
            reason = if (preparedLine == null) "callback_line_unmatched" else "callback_line_matched",
        )
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "lyric_line_update_complete",
            reason = if (preparedLine == null) "callback_line_unmatched" else "callback_line_matched",
            details = "currentBegin=${currentLyricLine?.begin},currentEnd=${currentLyricLine?.end}",
        )
    }

    override fun clearState() {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "clear_begin",
            details = "oldSongId=${MediaCardDiagnosticLogger.sanitize(currentSong?.id)},oldPosition=$currentPosition,oldPlaying=$currentPlaybackState",
        )
        currentSong = null
        currentSongName = null
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        currentUpcomingLyricLines = emptyList()
        currentUnmergedLyricLine = null
        currentInterludeType = null
        currentInterlude = null
        currentInterludeLine = null
        currentPosition = 0L
        currentPlaybackState = null
        activePackageName = null
        currentLyricPackageName = null
        isTextMode = false
        timingNavigator = TimingNavigator(emptyArray())
        unmergedTimingNavigator = TimingNavigator(emptyArray())
        interludeTracker = InterludeTracker()
        playbackPositionEstimator.reset()
        DisplayDiagnosticLogger.clear("BRIDGE")

        versionCounter.incrementAndGet()
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "clear_complete",
            details = "version=${versionCounter.get()}",
        )
    }

    private fun findPreparedLine(line: IRichLyricLine): TimedLine? {
        var matched: TimedLine? = null
        timingNavigator.forEachAt(line.begin) { candidate ->
            if (
                candidate.text == line.text ||
                    candidate.secondary == line.text
            ) {
                matched = candidate
            }
        }
        return matched
    }

    /**
     * Returns the current display group for the Island.
     *
     * The next-line preference controls only the optional preview rendered by
     * IslandSlotContentAssembler. It must not replace an Apple Music merged
     * current group with the unmerged line, otherwise concurrent vocals and
     * overlapping lines disappear when the preference is disabled.
     */
    fun currentLyricLineForIsland(nextLyricLineEnabled: Boolean): IRichLyricLine? =
        currentLyricLine

    private fun TimingNavigator<TimedLine>.lineAtOrPrevious(position: Long): TimedLine? =
        findPreviousEntry(position)

    private fun isSameSong(first: Song, second: Song): Boolean {
        val firstId = first.id?.takeIf { it.isNotBlank() }
        val secondId = second.id?.takeIf { it.isNotBlank() }
        if (firstId != null || secondId != null) {
            return firstId != null && secondId != null && firstId == secondId
        }
        return first.name?.trim()?.equals(second.name?.trim(), ignoreCase = true) == true &&
            first.artist?.trim()?.equals(second.artist?.trim(), ignoreCase = true) == true
    }

}
